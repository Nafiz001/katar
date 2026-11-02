package dev.nafiz.katar.booking;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.config.KatarProperties;
import dev.nafiz.katar.inventory.Journeys;
import dev.nafiz.katar.inventory.Legs;
import dev.nafiz.katar.inventory.SeatInventory;
import dev.nafiz.katar.queue.Sales;
import dev.nafiz.katar.queue.Sales.Sale;
import dev.nafiz.katar.queue.WaitingRoom.Admitted;

/**
 * Seats held for an admitted buyer while they pay. A hold claims the seat's
 * legs in the inventory and records who has them until when; the sweeper gives
 * the legs back if payment never comes.
 */
@Service
public class Holds {

    private final JdbcClient db;
    private final SeatInventory inventory;
    private final Journeys journeys;
    private final Sales sales;
    private final Clock clock;
    private final KatarProperties props;

    public Holds(JdbcClient db, SeatInventory inventory, Journeys journeys, Sales sales, Clock clock,
            KatarProperties props) {
        this.db = db;
        this.inventory = inventory;
        this.journeys = journeys;
        this.sales = sales;
        this.clock = clock;
        this.props = props;
    }

    /**
     * Holds a specific seat ({@code coach} and {@code number}) or, with no
     * number, the first free seat in the coach, for travel between two
     * stations.
     */
    @Transactional
    public Hold hold(Admitted who, String from, String to, String coach, Integer number) {
        Sale sale = sales.get(who.saleId());
        int a = journeys.seqOf(sale.journeyId(), from);
        int b = journeys.seqOf(sale.journeyId(), to);
        if (b <= a) {
            throw KatarException.unprocessable("bad_route", "the train reaches " + from + " after " + to);
        }
        long mask = Legs.mask(a, b);

        // One person's holds are made one at a time, so the seat limit cannot
        // be beaten by firing four requests in parallel. Other people are not
        // affected: the lock is keyed by person.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "hold|" + sale.id() + "|" + who.person())
                .query(Object.class).single();
        Instant now = clock.instant();
        int mine = db.sql("""
                SELECT count(*) FROM hold
                WHERE sale_id = :sale AND person = :person
                  AND (state = 'BOOKED' OR (state = 'HELD' AND expires_at > :now))
                """)
                .param("sale", sale.id())
                .param("person", who.person())
                .param("now", Timestamp.from(now))
                .query(Integer.class)
                .single();
        if (mine >= props.maxSeatsPerPerson()) {
            throw KatarException.conflict("seat_limit",
                    "one person may hold at most " + props.maxSeatsPerPerson() + " seats on a train");
        }

        long seatId;
        if (number != null) {
            seatId = journeys.seatId(sale.journeyId(), coach, number);
            if (!inventory.claim(seatId, mask)) {
                throw KatarException.conflict("seat_taken", "that seat is taken for part of your journey");
            }
        } else {
            int coachId = db.sql("""
                    SELECT c.id FROM coach c JOIN journey j ON j.train_id = c.train_id
                    WHERE j.id = :journey AND c.code = :code
                    """)
                    .param("journey", sale.journeyId())
                    .param("code", coach)
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(() -> KatarException.notFound("coach " + coach));
            seatId = inventory.claimAny(sale.journeyId(), coachId, mask);
            if (seatId < 0) {
                throw KatarException.conflict("coach_full", "no seat in " + coach + " is free for your journey");
            }
        }

        UUID id = UUID.randomUUID();
        Instant expires = now.plus(props.holdTtl());
        db.sql("""
                INSERT INTO hold (id, sale_id, seat_id, person, from_seq, to_seq, mask, created_at, expires_at)
                VALUES (:id, :sale, :seat, :person, :a, :b, :mask, :now, :expires)
                """)
                .param("id", id)
                .param("sale", sale.id())
                .param("seat", seatId)
                .param("person", who.person())
                .param("a", a)
                .param("b", b)
                .param("mask", mask)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expires))
                .update();
        return get(id);
    }

    /** Gives a held seat back before it expires. */
    @Transactional
    public void release(Admitted who, UUID holdId) {
        Long[] seatAndMask = db.sql("""
                UPDATE hold SET state = 'RELEASED'
                WHERE id = :id AND person = :person AND state = 'HELD' AND booking_id IS NULL
                RETURNING seat_id, mask
                """)
                .param("id", holdId)
                .param("person", who.person())
                .query((rs, n) -> new Long[] { rs.getLong(1), rs.getLong(2) })
                .optional()
                .orElseThrow(() -> KatarException.conflict("hold_gone", "that hold is not yours to release"));
        inventory.release(seatAndMask[0], seatAndMask[1]);
    }

    /**
     * Expires holds whose time is up and gives their legs back. Works in
     * batches with SKIP LOCKED, so several app instances can sweep at once
     * without waiting on each other, and a hold being paid for right now (its
     * row locked by the payment) is simply left for the next sweep.
     *
     * @return how many holds expired
     */
    @Transactional
    public int sweep() {
        Instant now = clock.instant();
        List<long[]> expired = db.sql("""
                UPDATE hold h SET state = 'EXPIRED'
                FROM (SELECT id FROM hold WHERE state = 'HELD' AND expires_at <= :now
                      ORDER BY expires_at LIMIT 500 FOR UPDATE SKIP LOCKED) due
                WHERE h.id = due.id
                RETURNING h.seat_id, h.mask
                """)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new long[] { rs.getLong(1), rs.getLong(2) })
                .list();
        for (long[] e : expired) {
            inventory.release(e[0], e[1]);
        }
        // a booking that lost a seat before it was paid cannot be completed
        db.sql("""
                UPDATE booking b SET state = 'CANCELLED'
                WHERE b.state = 'AWAITING_PAYMENT'
                  AND EXISTS (SELECT 1 FROM hold h WHERE h.booking_id = b.id AND h.state = 'EXPIRED')
                """).update();
        return expired.size();
    }

    public Hold get(UUID id) {
        return db.sql("""
                SELECT h.id, h.state, h.expires_at, h.from_seq, h.to_seq, c.code AS coach, s.number, c.class
                FROM hold h JOIN seat s ON s.id = h.seat_id JOIN coach c ON c.id = s.coach_id
                WHERE h.id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new Hold(rs.getObject("id", UUID.class), rs.getString("coach"), rs.getInt("number"),
                        rs.getString("class"), rs.getInt("from_seq"), rs.getInt("to_seq"), rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional()
                .orElseThrow(() -> KatarException.notFound("hold " + id));
    }

    public record Hold(UUID id, String coach, int number, String travelClass, int fromSeq, int toSeq, String state,
            Instant expiresAt) {
    }
}

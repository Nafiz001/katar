package dev.nafiz.katar.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.config.KatarProperties;
import dev.nafiz.katar.config.Signer;
import dev.nafiz.katar.queue.Sales.Sale;

/**
 * The queue in front of a sale.
 *
 * <p>Joining costs one {@code nextval} and one insert: no row is locked and
 * nobody else's place moves. Before the sale opens there is no order at all;
 * early joiners are shuffled when it opens (see {@link Admitter}), so being
 * first through the door at 07:50 is worth nothing over 07:59.
 *
 * <p>Once your ticket is at or below the sale's watermark you may claim an
 * admission pass: a signed token that every seat endpoint requires.
 */
@Service
public class WaitingRoom {

    private final JdbcClient db;
    private final Sales sales;
    private final Clock clock;
    private final KatarProperties props;
    private final Signer passes;
    /** Sales seen shuffled. The flag only ever turns on, so once seen it need not be read again. */
    private final Map<Long, Sale> shuffled = new ConcurrentHashMap<>();

    public WaitingRoom(JdbcClient db, Sales sales, Clock clock, KatarProperties props) {
        this.db = db;
        this.sales = sales;
        this.clock = clock;
        this.props = props;
        this.passes = new Signer(props.passSecret());
    }

    /**
     * A person's place in a sale's queue. Joining again returns the same
     * place, so refreshing the page or opening ten tabs gains nothing.
     */
    @Transactional
    public Place join(long saleId, String person) {
        Instant now = clock.instant();
        // Shared with every other joiner; only the opening shuffle takes it
        // exclusively, so nobody can join "between" the shuffle and the
        // first numbered ticket.
        db.sql("SELECT pg_advisory_xact_lock_shared(:key)").param("key", lockKey(saleId)).query(Object.class).single();
        Sale sale = shuffled.get(saleId);
        if (sale == null) {
            sale = sales.get(saleId);
            if (sale.shuffled()) {
                shuffled.put(saleId, sale);
            }
        }
        if (!now.isBefore(sale.closesAt())) {
            throw KatarException.conflict("sale_closed", "this sale has closed");
        }
        if (sale.shuffled()) {
            // The common case during a rush: one statement takes a ticket and
            // returns the new place. Only someone already in line falls
            // through to the lookup below. (A conflict still spends one
            // sequence number: tickets can skip, never repeat.)
            Optional<Place> joined = db.sql("WITH ins AS (INSERT INTO queue_entry (sale_id, ticket, person, joined_at) "
                    + "SELECT :sale, nextval('" + sale.ticketSeq() + "'), :person, :now "
                    + "WHERE NOT EXISTS (SELECT 1 FROM queue_entry WHERE sale_id = :sale AND person = :person) "
                    + "ON CONFLICT (sale_id, person) DO NOTHING RETURNING ticket, state) "
                    + "SELECT ins.ticket, ins.state, s.admitted_upto, s.opens_at FROM ins, sale s WHERE s.id = :sale")
                    .param("sale", saleId)
                    .param("person", person)
                    .param("now", Timestamp.from(now))
                    .query((rs, n) -> Place.of(saleId, rs.getLong("ticket"), rs.getString("state"),
                            rs.getLong("admitted_upto"), rs.getTimestamp("opens_at").toInstant(), null))
                    .optional();
            if (joined.isPresent()) {
                return joined.get();
            }
        } else {
            db.sql("""
                    INSERT INTO queue_entry (sale_id, person, joined_at) VALUES (:sale, :person, :now)
                    ON CONFLICT (sale_id, person) DO NOTHING
                    """)
                    .param("sale", saleId)
                    .param("person", person)
                    .param("now", Timestamp.from(now))
                    .update();
        }
        return find(saleId, person).orElseThrow();
    }

    /** Forgets which sales were seen shuffled; for tests, which reuse sale ids (production never does). */
    public void clearCache() {
        shuffled.clear();
    }

    /** Where a person stands, without joining. */
    public Optional<Place> find(long saleId, String person) {
        return db.sql("""
                SELECT q.ticket, q.state, q.pass_expires, s.admitted_upto, s.opens_at
                FROM queue_entry q JOIN sale s ON s.id = q.sale_id
                WHERE q.sale_id = :sale AND q.person = :person
                """)
                .param("sale", saleId)
                .param("person", person)
                .query((rs, n) -> {
                    long ticket = rs.getLong("ticket");
                    Long t = rs.wasNull() ? null : ticket;
                    Timestamp exp = rs.getTimestamp("pass_expires");
                    return Place.of(saleId, t, rs.getString("state"), rs.getLong("admitted_upto"),
                            rs.getTimestamp("opens_at").toInstant(), exp == null ? null : exp.toInstant());
                })
                .optional();
    }

    /**
     * Issues the admission pass of someone whose turn has come. Claiming again
     * returns a pass with the same expiry: the clock starts once.
     */
    @Transactional
    public Pass claimPass(long saleId, String person) {
        Instant now = clock.instant();
        Instant expires = now.plus(props.passTtl());
        int changed = db.sql("""
                UPDATE queue_entry q
                SET state = 'ADMITTED',
                    admitted_at = coalesce(q.admitted_at, :now),
                    pass_expires = coalesce(q.pass_expires, :expires)
                FROM sale s
                WHERE s.id = q.sale_id AND q.sale_id = :sale AND q.person = :person
                  AND q.ticket IS NOT NULL AND q.ticket <= s.admitted_upto
                  AND q.state IN ('WAITING', 'ADMITTED')
                  AND (q.pass_expires IS NULL OR q.pass_expires > :now)
                """)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expires))
                .param("sale", saleId)
                .param("person", person)
                .update();
        Place place = find(saleId, person).orElseThrow(() -> KatarException.forbidden("not_in_queue",
                "join the queue first"));
        if (changed == 0) {
            if ("EXPIRED".equals(place.state()) || "DONE".equals(place.state())
                    || (place.passExpires() != null && !place.passExpires().isAfter(now))) {
                throw KatarException.forbidden("pass_expired", "your turn has passed; join the queue again later");
            }
            throw KatarException.forbidden("not_your_turn", "you are still in the queue");
        }
        String token = passes.sign(saleId + "|" + place.ticket() + "|" + person + "|" + place.passExpires().getEpochSecond());
        return new Pass(token, place.passExpires());
    }

    /**
     * Checks a pass and returns whose it is. The signature proves katar issued
     * it; the database check proves it is still in force (a finished or
     * expired person cannot keep booking with an old token).
     */
    public Admitted verify(long saleId, String token) {
        String[] parts = passes.open(token)
                .map(p -> p.split("\\|"))
                .filter(p -> p.length == 4)
                .orElseThrow(() -> KatarException.forbidden("bad_pass", "that admission pass is not valid"));
        long sale = Long.parseLong(parts[0]);
        long expires = Long.parseLong(parts[3]);
        if (sale != saleId) {
            throw KatarException.forbidden("bad_pass", "that pass is for a different sale");
        }
        if (clock.instant().getEpochSecond() >= expires) {
            throw KatarException.forbidden("pass_expired", "your admission pass has expired");
        }
        String state = db.sql("SELECT state FROM queue_entry WHERE sale_id = :sale AND person = :person")
                .param("sale", saleId)
                .param("person", parts[2])
                .query(String.class)
                .optional()
                .orElse("");
        if (!"ADMITTED".equals(state)) {
            throw KatarException.forbidden("pass_expired", "your admission pass is no longer in force");
        }
        return new Admitted(saleId, Long.parseLong(parts[1]), parts[2]);
    }

    /** A person finished buying: their place in the booking room is freed for the next. */
    public void finish(long saleId, String person) {
        db.sql("UPDATE queue_entry SET state = 'DONE' WHERE sale_id = :sale AND person = :person AND state = 'ADMITTED'")
                .param("sale", saleId)
                .param("person", person)
                .update();
    }

    static long lockKey(long saleId) {
        return 0x6B61746172L << 24 ^ saleId; // "katar" in the high bits, so it cannot clash with other advisory locks
    }

    public record Place(long saleId, Long ticket, String state, Long ahead, boolean admitted, Instant opensAt,
            Instant passExpires) {

        static Place of(long saleId, Long ticket, String state, long admittedUpto, Instant opensAt, Instant passExpires) {
            boolean in = ticket != null && ticket <= admittedUpto;
            Long ahead = ticket == null ? null : Math.max(0, ticket - admittedUpto - 1);
            return new Place(saleId, ticket, state, ahead, in, opensAt, passExpires);
        }
    }

    public record Pass(String token, Instant expiresAt) {
    }

    public record Admitted(long saleId, long ticket, String person) {
    }
}

package dev.nafiz.katar.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.config.KatarProperties;
import dev.nafiz.katar.queue.WaitingRoom;
import dev.nafiz.katar.queue.WaitingRoom.Admitted;

/**
 * Turns a person's held seats into one payment, and a confirmed payment into
 * a ticket.
 *
 * <p>Creating a booking is idempotent: the client sends an Idempotency-Key,
 * and the same key always gets the same booking back. A retry after a timeout
 * cannot make a second booking; the same key with different seats is refused
 * rather than silently ignored.
 */
@Service
public class Bookings {

    private final JdbcClient db;
    private final Clock clock;
    private final KatarProperties props;
    private final TicketSigner tickets;
    private final WaitingRoom room;
    private final TransactionTemplate tx;

    public Bookings(JdbcClient db, Clock clock, KatarProperties props, TicketSigner tickets, WaitingRoom room,
            TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.tickets = tickets;
        this.room = room;
    }

    public Booking create(Admitted who, String idempotencyKey, List<UUID> holdIds) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw KatarException.unprocessable("idempotency_key_required", "send an Idempotency-Key header");
        }
        if (holdIds == null || holdIds.isEmpty()) {
            throw KatarException.unprocessable("no_seats", "choose at least one held seat");
        }
        String requestHash = hash(who.person() + "|" + holdIds.stream().map(UUID::toString).sorted().toList());
        Optional<Booking> existing = byKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), who, requestHash);
        }
        try {
            // its own transaction: the holds are locked, priced, attached and the
            // booking inserted all or nothing
            return tx.execute(status -> createOnce(who, idempotencyKey, requestHash, holdIds));
        } catch (DuplicateKeyException race) {
            // another request with the same key won the insert a moment ago
            return replay(byKey(idempotencyKey).orElseThrow(), who, requestHash);
        } catch (KatarException e) {
            // A retry that waited on the first request's row locks finds the
            // holds already attached, to the booking that request made. That
            // is the booking to return, not a refusal.
            Optional<Booking> made = "hold_gone".equals(e.code()) ? byKey(idempotencyKey) : Optional.empty();
            if (made.isPresent()) {
                return replay(made.get(), who, requestHash);
            }
            throw e;
        }
    }

    private Booking createOnce(Admitted who, String key, String requestHash, List<UUID> holdIds) {
        Instant now = clock.instant();
        List<HeldSeat> held = db.sql("""
                SELECT h.id, h.from_seq, h.to_seq, f.per_leg_poisha
                FROM hold h
                JOIN seat s ON s.id = h.seat_id
                JOIN coach c ON c.id = s.coach_id
                JOIN fare f ON f.train_id = c.train_id AND f.class = c.class
                WHERE h.id IN (:ids) AND h.person = :person AND h.sale_id = :sale
                  AND h.state = 'HELD' AND h.booking_id IS NULL AND h.expires_at > :now
                FOR UPDATE OF h
                """)
                .param("ids", holdIds)
                .param("person", who.person())
                .param("sale", who.saleId())
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new HeldSeat(rs.getObject("id", UUID.class),
                        (long) (rs.getInt("to_seq") - rs.getInt("from_seq")) * rs.getInt("per_leg_poisha")))
                .list();
        if (held.size() != holdIds.size()) {
            throw KatarException.conflict("hold_gone", "a held seat has expired or is not yours");
        }
        long amount = held.stream().mapToLong(HeldSeat::price).sum();
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO booking (id, sale_id, person, idempotency_key, request_hash, amount_poisha, created_at)
                VALUES (:id, :sale, :person, :key, :hash, :amount, :now)
                """)
                .param("id", id)
                .param("sale", who.saleId())
                .param("person", who.person())
                .param("key", key)
                .param("hash", requestHash)
                .param("amount", amount)
                .param("now", Timestamp.from(now))
                .update();
        // The seats now wait for the payment, with a fresh hold window to pay in.
        db.sql("UPDATE hold SET booking_id = :booking, expires_at = :until WHERE id IN (:ids)")
                .param("booking", id)
                .param("until", Timestamp.from(now.plus(props.holdTtl())))
                .param("ids", holdIds)
                .update();
        return get(id);
    }

    private Booking replay(Booking booking, Admitted who, String requestHash) {
        String stored = db.sql("SELECT request_hash FROM booking WHERE id = :id").param("id", booking.id())
                .query(String.class).single();
        if (!stored.equals(requestHash)) {
            throw KatarException.unprocessable("idempotency_key_reused",
                    "that Idempotency-Key was used for a different request");
        }
        return booking;
    }

    /**
     * Records a payment the gateway confirmed. Paying twice, or a webhook
     * delivered twice, changes nothing the second time. A payment for a
     * booking that was cancelled (its seats expired first) is reported back so
     * it can be refunded rather than leaving someone paid with no seat.
     */
    @Transactional
    public PaymentOutcome markPaid(UUID bookingId, String paymentRef, long amountPoisha) {
        Booking booking = db.sql("SELECT id FROM booking WHERE id = :id FOR UPDATE").param("id", bookingId)
                .query(UUID.class).optional().map(this::get)
                .orElseThrow(() -> KatarException.notFound("booking " + bookingId));
        switch (booking.state()) {
            case "PAID":
                return PaymentOutcome.ALREADY_PAID;
            case "CANCELLED":
                return PaymentOutcome.REFUND;
            default:
                break;
        }
        if (amountPoisha != booking.amountPoisha()) {
            throw KatarException.unprocessable("wrong_amount", "the payment does not match the booking");
        }
        Instant now = clock.instant();
        int seats = db.sql("UPDATE hold SET state = 'BOOKED' WHERE booking_id = :id AND state = 'HELD'")
                .param("id", bookingId).update();
        if (seats != booking.seats()) {
            // a seat expired between the gateway taking the money and this call
            db.sql("UPDATE booking SET state = 'CANCELLED' WHERE id = :id").param("id", bookingId).update();
            return PaymentOutcome.REFUND;
        }
        db.sql("UPDATE booking SET state = 'PAID', payment_ref = :ref, paid_at = :now WHERE id = :id")
                .param("ref", paymentRef)
                .param("now", Timestamp.from(now))
                .param("id", bookingId)
                .update();
        db.sql("INSERT INTO ticket (booking_id, code, issued_at) VALUES (:id, :code, :now)")
                .param("id", bookingId)
                .param("code", tickets.sign(ticketPayload(bookingId, now)))
                .param("now", Timestamp.from(now))
                .update();
        room.finish(booking.saleId(), booking.person());
        return PaymentOutcome.PAID;
    }

    /** What an inspector's phone reads off the code: train, date, seats and stations. */
    private String ticketPayload(UUID bookingId, Instant issued) {
        List<String> seats = db.sql("""
                SELECT c.code || '-' || s.number || ':' || a.code || '-' || z.code
                FROM hold h
                JOIN seat s ON s.id = h.seat_id
                JOIN coach c ON c.id = s.coach_id
                JOIN journey j ON j.id = s.journey_id
                JOIN route_stop ra ON ra.train_id = j.train_id AND ra.seq = h.from_seq
                JOIN route_stop rz ON rz.train_id = j.train_id AND rz.seq = h.to_seq
                JOIN station a ON a.id = ra.station_id
                JOIN station z ON z.id = rz.station_id
                WHERE h.booking_id = :id
                ORDER BY c.position, s.number
                """)
                .param("id", bookingId)
                .query(String.class)
                .list();
        String train = db.sql("""
                SELECT t.number || '|' || j.service_date
                FROM booking b JOIN sale sa ON sa.id = b.sale_id
                JOIN journey j ON j.id = sa.journey_id JOIN train t ON t.id = j.train_id
                WHERE b.id = :id
                """)
                .param("id", bookingId)
                .query(String.class)
                .single();
        return "katar1|" + bookingId + "|" + train + "|" + String.join(",", seats) + "|" + issued.getEpochSecond();
    }

    public Booking get(UUID id) {
        return db.sql("""
                SELECT b.id, b.sale_id, b.person, b.state, b.amount_poisha, t.code,
                       (SELECT count(*) FROM hold h WHERE h.booking_id = b.id) AS seats
                FROM booking b LEFT JOIN ticket t ON t.booking_id = b.id
                WHERE b.id = :id
                """)
                .param("id", id)
                .query((rs, n) -> new Booking(rs.getObject("id", UUID.class), rs.getLong("sale_id"),
                        rs.getString("person"), rs.getString("state"), rs.getLong("amount_poisha"),
                        rs.getInt("seats"), rs.getString("code")))
                .optional()
                .orElseThrow(() -> KatarException.notFound("booking " + id));
    }

    private Optional<Booking> byKey(String key) {
        return db.sql("SELECT id FROM booking WHERE idempotency_key = :key").param("key", key)
                .query(UUID.class).optional().map(this::get);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record HeldSeat(UUID id, long price) {
    }

    public record Booking(UUID id, long saleId, String person, String state, long amountPoisha, int seats,
            String ticketCode) {
    }

    public enum PaymentOutcome {
        PAID, ALREADY_PAID, REFUND
    }
}

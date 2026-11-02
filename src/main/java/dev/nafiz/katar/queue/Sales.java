package dev.nafiz.katar.queue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.nafiz.katar.config.KatarException;

/** Ticket sales, each with its own waiting room. */
@Repository
public class Sales {

    private final JdbcClient db;

    public Sales(JdbcClient db) {
        this.db = db;
    }

    /**
     * Opens a sale for a journey, with its own ticket sequence. A sequence per
     * sale is what makes a place in line O(1) to compute: ticket numbers of
     * one sale are consecutive, so position = ticket - admitted_upto.
     */
    @Transactional
    public Sale create(long journeyId, Instant opensAt, Instant closesAt, int admitPerSec, int maxActive) {
        long id = db.sql("""
                INSERT INTO sale (journey_id, opens_at, closes_at, admit_per_sec, max_active, ticket_seq)
                VALUES (:journey, :opens, :closes, :rate, :max, '')
                RETURNING id
                """)
                .param("journey", journeyId)
                .param("opens", Timestamp.from(opensAt))
                .param("closes", Timestamp.from(closesAt))
                .param("rate", admitPerSec)
                .param("max", maxActive)
                .query(Long.class)
                .single();
        String seq = "sale_" + id + "_ticket"; // built from a generated number only, never from input
        db.sql("CREATE SEQUENCE " + seq + " MINVALUE 1 START 1 CACHE 1").update();
        db.sql("UPDATE sale SET ticket_seq = :seq WHERE id = :id").param("seq", seq).param("id", id).update();
        return get(id);
    }

    public Sale get(long id) {
        return db.sql("SELECT * FROM sale WHERE id = :id")
                .param("id", id)
                .query(Sales::map)
                .optional()
                .orElseThrow(() -> KatarException.notFound("sale " + id));
    }

    public Sale forJourney(long journeyId) {
        return db.sql("SELECT * FROM sale WHERE journey_id = :journey")
                .param("journey", journeyId)
                .query(Sales::map)
                .optional()
                .orElseThrow(() -> KatarException.notFound("a sale for journey " + journeyId));
    }

    /** Sales whose window contains the instant. */
    public List<Sale> openAt(Instant now) {
        return db.sql("SELECT * FROM sale WHERE opens_at <= :now AND closes_at > :now ORDER BY id")
                .param("now", Timestamp.from(now))
                .query(Sales::map)
                .list();
    }

    private static Sale map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Sale(rs.getLong("id"), rs.getLong("journey_id"), rs.getTimestamp("opens_at").toInstant(),
                rs.getTimestamp("closes_at").toInstant(), rs.getInt("admit_per_sec"), rs.getInt("max_active"),
                rs.getLong("admitted_upto"), rs.getBoolean("shuffled"), rs.getString("ticket_seq"));
    }

    public record Sale(long id, long journeyId, Instant opensAt, Instant closesAt, int admitPerSec, int maxActive,
            long admittedUpto, boolean shuffled, String ticketSeq) {

        public boolean isOpen(Instant now) {
            return !now.isBefore(opensAt) && now.isBefore(closesAt);
        }
    }
}

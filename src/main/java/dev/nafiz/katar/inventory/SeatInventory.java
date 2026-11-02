package dev.nafiz.katar.inventory;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The seat bitmasks, and the only code that changes them.
 *
 * <p>Every change is one statement that tests and sets in the same step. A
 * claim adds bits only where none of them are set yet; there is no "read the
 * seat, decide, write it back", so there is nothing for a concurrent request
 * to slip between, and no lock is held beyond that one row for that one
 * statement. That is what lets the Eid rush hit the same coach from thousands
 * of connections at once.
 */
@Repository
public class SeatInventory {

    private final JdbcClient db;

    public SeatInventory(JdbcClient db) {
        this.db = db;
    }

    /**
     * Takes the legs in {@code mask} on a seat if every one of them is free.
     *
     * @return true if this call took them; false if any leg was already taken
     */
    public boolean claim(long seatId, long mask) {
        return db.sql("""
                UPDATE seat SET taken = taken | :mask
                WHERE id = :id AND (taken & :mask) = 0
                """)
                .param("mask", mask)
                .param("id", seatId)
                .update() == 1;
    }

    /** Gives back legs taken by an earlier claim. Releasing free legs changes nothing. */
    public void release(long seatId, long mask) {
        db.sql("UPDATE seat SET taken = taken & ~:mask WHERE id = :id")
                .param("mask", mask)
                .param("id", seatId)
                .update();
    }

    /**
     * Claims the first free seat in a coach for a journey, trying seats in
     * order. A seat another buyer takes between the scan and the claim is
     * simply skipped; the claim itself is still the atomic update above.
     *
     * @return the seat id, or -1 if the coach has no seat free for those legs
     */
    public long claimAny(long journeyId, int coachId, long mask) {
        while (true) {
            List<Long> candidates = db.sql("""
                    SELECT id FROM seat
                    WHERE journey_id = :journey AND coach_id = :coach AND (taken & :mask) = 0
                    ORDER BY number
                    LIMIT 8
                    """)
                    .param("journey", journeyId)
                    .param("coach", coachId)
                    .param("mask", mask)
                    .query(Long.class)
                    .list();
            if (candidates.isEmpty()) {
                return -1;
            }
            for (long id : candidates) {
                if (claim(id, mask)) {
                    return id;
                }
            }
            // every candidate went to someone else in the meantime: scan again
        }
    }

    /** The current mask of a seat. */
    public long taken(long seatId) {
        return db.sql("SELECT taken FROM seat WHERE id = :id").param("id", seatId).query(Long.class).single();
    }

    /** Free seats per coach for a journey's legs, for the seat map and the search page. */
    public List<CoachAvailability> availability(long journeyId, long mask) {
        return db.sql("""
                SELECT c.id, c.code, c.class, c.seat_count,
                       count(*) FILTER (WHERE (s.taken & :mask) = 0) AS free
                FROM seat s JOIN coach c ON c.id = s.coach_id
                WHERE s.journey_id = :journey
                GROUP BY c.id ORDER BY c.position
                """)
                .param("journey", journeyId)
                .param("mask", mask)
                .query((rs, n) -> new CoachAvailability(rs.getInt("id"), rs.getString("code"),
                        rs.getString("class"), rs.getInt("seat_count"), rs.getInt("free")))
                .list();
    }

    public record CoachAvailability(int coachId, String code, String travelClass, int seats, int free) {
    }
}

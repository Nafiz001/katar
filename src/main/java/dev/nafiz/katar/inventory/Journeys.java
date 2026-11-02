package dev.nafiz.katar.inventory;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Runs of a train on a date, their seats, and their route. */
@Repository
public class Journeys {

    private final JdbcClient db;

    public Journeys(JdbcClient db) {
        this.db = db;
    }

    /**
     * The journey for a train on a date, creating it and one row per seat if
     * it does not exist yet. Safe to call twice.
     */
    @Transactional
    public long ensure(int trainNumber, LocalDate date) {
        Long id = db.sql("""
                INSERT INTO journey (train_id, service_date)
                SELECT id, :date FROM train WHERE number = :number
                ON CONFLICT (train_id, service_date) DO NOTHING
                RETURNING id
                """)
                .param("number", trainNumber)
                .param("date", date)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (id == null) {
            return db.sql("""
                    SELECT j.id FROM journey j JOIN train t ON t.id = j.train_id
                    WHERE t.number = :number AND j.service_date = :date
                    """)
                    .param("number", trainNumber)
                    .param("date", date)
                    .query(Long.class)
                    .single();
        }
        db.sql("""
                INSERT INTO seat (journey_id, coach_id, number)
                SELECT :journey, c.id, n
                FROM journey j
                JOIN coach c ON c.train_id = j.train_id
                CROSS JOIN LATERAL generate_series(1, c.seat_count) AS n
                WHERE j.id = :journey
                """)
                .param("journey", id)
                .update();
        return id;
    }

    /** The stops of the train a journey runs, in order. */
    public List<Stop> stops(long journeyId) {
        return db.sql("""
                SELECT rs.seq, s.code, s.name, s.name_bn, rs.depart_min
                FROM journey j
                JOIN route_stop rs ON rs.train_id = j.train_id
                JOIN station s ON s.id = rs.station_id
                WHERE j.id = :journey
                ORDER BY rs.seq
                """)
                .param("journey", journeyId)
                .query((rs, n) -> new Stop(rs.getInt("seq"), rs.getString("code"), rs.getString("name"),
                        rs.getString("name_bn"), rs.getInt("depart_min")))
                .list();
    }

    /** The stop number of a station on a journey's route. */
    public int seqOf(long journeyId, String stationCode) {
        return db.sql("""
                SELECT rs.seq FROM journey j
                JOIN route_stop rs ON rs.train_id = j.train_id
                JOIN station s ON s.id = rs.station_id
                WHERE j.id = :journey AND s.code = :code
                """)
                .param("journey", journeyId)
                .param("code", stationCode)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("this train does not stop at " + stationCode));
    }

    /** A seat's id from its coach code and number. */
    public long seatId(long journeyId, String coachCode, int number) {
        return db.sql("""
                SELECT s.id FROM seat s JOIN coach c ON c.id = s.coach_id
                WHERE s.journey_id = :journey AND c.code = :coach AND s.number = :number
                """)
                .param("journey", journeyId)
                .param("coach", coachCode)
                .param("number", number)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("no seat " + coachCode + "-" + number));
    }

    public record Stop(int seq, String code, String name, String nameBn, int departMin) {
    }
}

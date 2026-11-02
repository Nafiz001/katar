package dev.nafiz.katar.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.nafiz.katar.DatabaseTest;

class SeatInventoryTest extends DatabaseTest {

    @Autowired
    Journeys journeys;
    @Autowired
    SeatInventory seats;

    long journey;
    long seat;

    @BeforeEach
    void journey() {
        journey = journeys.ensure(753, LocalDate.of(2027, 3, 30));
        seat = journeys.seatId(journey, "KA", 1);
    }

    @Test
    void creatingAJourneyMakesEverySeatOnce() {
        assertThat(journeys.ensure(753, LocalDate.of(2027, 3, 30))).isEqualTo(journey);
        int count = db.sql("SELECT count(*) FROM seat WHERE journey_id = :j").param("j", journey)
                .query(Integer.class).single();
        assertThat(count).isEqualTo(75 * 3 + 55 * 2 + 50);
    }

    @Test
    void aSeatIsSoldAgainForTheLegsAfterAJourneyEnds() {
        int tangail = journeys.seqOf(journey, "TGL");
        int rajshahi = journeys.seqOf(journey, "RJS");
        assertThat(seats.claim(seat, Legs.mask(0, tangail))).isTrue();
        assertThat(seats.claim(seat, Legs.mask(tangail, rajshahi))).isTrue();
        assertThat(seats.claim(seat, Legs.mask(1, 2))).isFalse();
        assertThat(seats.taken(seat)).isEqualTo(Legs.mask(0, rajshahi));
    }

    @Test
    void releasingGivesBackOnlyThoseLegs() {
        seats.claim(seat, Legs.mask(0, 3));
        seats.claim(seat, Legs.mask(3, 9));
        seats.release(seat, Legs.mask(0, 3));
        assertThat(seats.taken(seat)).isEqualTo(Legs.mask(3, 9));
        assertThat(seats.claim(seat, Legs.mask(1, 2))).isTrue();
    }

    /** The Eid rush in miniature: 200 buyers, one seat, journeys that all share leg 4. */
    @Test
    void onlyOneOfManyOverlappingClaimsWins() throws Exception {
        int winners = race(200, i -> seats.claim(seat, Legs.mask(i % 4, 5 + i % 4)));
        assertThat(winners).isEqualTo(1);
    }

    /** Nine buyers who each want a different single leg can all have the same seat. */
    @Test
    void disjointClaimsAllWinEvenAtTheSameInstant() throws Exception {
        int winners = race(9, i -> seats.claim(seat, Legs.mask(i, i + 1)));
        assertThat(winners).isEqualTo(9);
        assertThat(seats.taken(seat)).isEqualTo(Legs.mask(0, 9));
    }

    /** 300 buyers race for any seat in a 75-seat coach: exactly 75 get one, never the same one. */
    @Test
    void aFullCoachIsNeverOversold() throws Exception {
        int coach = db.sql("SELECT id FROM coach WHERE code = 'KA'").query(Integer.class).single();
        long mask = Legs.mask(0, 9);
        Set<Long> sold = ConcurrentHashMap.newKeySet();
        int winners = race(300, i -> {
            long id = seats.claimAny(journey, coach, mask);
            return id > 0 && sold.add(id);
        });
        assertThat(winners).isEqualTo(75);
        assertThat(sold).hasSize(75);
        assertThat(seats.claimAny(journey, coach, mask)).isEqualTo(-1);
    }

    /** Runs {@code n} attempts as close to simultaneously as threads allow; counts the successes. */
    private static int race(int n, IntPredicate attempt) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        List<Exception> failures = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int k = i;
                pool.submit(() -> {
                    go.await();
                    if (attempt.test(k)) {
                        won.incrementAndGet();
                    }
                    return null;
                });
            }
            go.countDown();
        }
        assertThat(failures).isEmpty();
        return won.get();
    }
}

package dev.nafiz.katar.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.nafiz.katar.DatabaseTest;
import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.inventory.Journeys;
import dev.nafiz.katar.queue.Sales.Sale;
import dev.nafiz.katar.queue.WaitingRoom.Place;

class WaitingRoomTest extends DatabaseTest {

    static final Instant OPENS = T0.plus(Duration.ofMinutes(1)); // 08:00

    @Autowired
    Journeys journeys;
    @Autowired
    Sales sales;
    @Autowired
    WaitingRoom room;
    @Autowired
    Admitter admitter;

    Sale sale(int perSecond, int maxActive) {
        long journey = journeys.ensure(753, LocalDate.of(2027, 3, 30));
        return sales.create(journey, OPENS, OPENS.plus(Duration.ofHours(4)), perSecond, maxActive);
    }

    static String person(int i) {
        return "person-" + i;
    }

    void open() {
        clock.set(OPENS);
        admitter.tick();
    }

    @Test
    void joiningTwiceKeepsTheSamePlace() {
        Sale sale = sale(10, 100);
        open();
        Place first = room.join(sale.id(), person(1));
        room.join(sale.id(), person(2));
        Place again = room.join(sale.id(), person(1));
        assertThat(again.ticket()).isEqualTo(first.ticket()).isEqualTo(1L);
    }

    @Test
    void earlyJoinersWaitUnnumberedThenAreShuffledAtOpening() {
        Sale sale = sale(10, 100);
        for (int i = 0; i < 60; i++) {
            Place p = room.join(sale.id(), person(i));
            assertThat(p.ticket()).isNull();
            assertThat(p.admitted()).isFalse();
        }
        open();
        List<Long> tickets = IntStream.range(0, 60)
                .mapToObj(i -> room.find(sale.id(), person(i)).orElseThrow().ticket()).toList();
        // everyone got one of 1..60, exactly once
        assertThat(tickets).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 60).boxed().toList());
        // and not in the order they arrived (1 chance in 60! of a false failure)
        assertThat(tickets).isNotEqualTo(LongStream.rangeClosed(1, 60).boxed().toList());
        // whoever comes after opening queues behind all of them
        assertThat(room.join(sale.id(), person(999)).ticket()).isEqualTo(61L);
    }

    @Test
    void peopleAreLetInAtTheSaleRate() {
        Sale sale = sale(10, 1000);
        open();
        for (int i = 0; i < 35; i++) {
            room.join(sale.id(), person(i));
        }
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(10);
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(20);
        Place eleventh = room.find(sale.id(), person(10)).orElseThrow();
        assertThat(eleventh.admitted()).isTrue();
        Place last = room.find(sale.id(), person(34)).orElseThrow();
        assertThat(last.admitted()).isFalse();
        assertThat(last.ahead()).isEqualTo(14);
        // the watermark never passes the last ticket handed out
        admitter.admit(sale.id(), clock.instant());
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(35);
    }

    @Test
    void theBookingRoomNeverHoldsMoreThanItsLimit() {
        Sale sale = sale(100, 5);
        open();
        for (int i = 0; i < 20; i++) {
            room.join(sale.id(), person(i));
        }
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(5);
        // five offered, none claimed yet: still full
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(5);
        room.claimPass(sale.id(), person(0));
        room.claimPass(sale.id(), person(1));
        room.finish(sale.id(), person(0));
        room.finish(sale.id(), person(1));
        // two finished: two more come in
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(7);
    }

    @Test
    void anUnclaimedTurnLapsesAndFreesItsPlace() {
        Sale sale = sale(100, 3);
        open();
        for (int i = 0; i < 6; i++) {
            room.join(sale.id(), person(i));
        }
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(3);
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(admitter.admit(sale.id(), clock.instant())).isEqualTo(6);
        assertThatThrownBy(() -> room.claimPass(sale.id(), person(0)))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("pass_expired"));
    }

    @Test
    void aPassIsOnlyForThosePastTheWatermark() {
        Sale sale = sale(1, 100);
        open();
        room.join(sale.id(), person(1));
        room.join(sale.id(), person(2));
        admitter.admit(sale.id(), clock.instant());
        assertThatThrownBy(() -> room.claimPass(sale.id(), person(2)))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("not_your_turn"));
        var pass = room.claimPass(sale.id(), person(1));
        assertThat(room.verify(sale.id(), pass.token()).person()).isEqualTo(person(1));
        // claiming again does not restart the clock
        clock.advance(Duration.ofMinutes(5));
        assertThat(room.claimPass(sale.id(), person(1)).expiresAt()).isEqualTo(pass.expiresAt());
    }

    @Test
    void aForgedOrExpiredPassIsRefused() {
        Sale sale = sale(10, 100);
        open();
        room.join(sale.id(), person(1));
        admitter.admit(sale.id(), clock.instant());
        String token = room.claimPass(sale.id(), person(1)).token();
        String forged = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> room.verify(sale.id(), forged))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("bad_pass"));
        assertThatThrownBy(() -> room.verify(sale.id() + 1, token))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("bad_pass"));
        clock.advance(Duration.ofMinutes(16));
        assertThatThrownBy(() -> room.verify(sale.id(), token))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("pass_expired"));
    }

    @Test
    void aFinishedBuyerCannotKeepUsingTheirPass() {
        Sale sale = sale(10, 100);
        open();
        room.join(sale.id(), person(1));
        admitter.admit(sale.id(), clock.instant());
        String token = room.claimPass(sale.id(), person(1)).token();
        room.finish(sale.id(), person(1));
        assertThatThrownBy(() -> room.verify(sale.id(), token))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("pass_expired"));
    }

    /** 2,000 people press "join" in the same instant after opening: tickets 1..2000, no gaps, no repeats. */
    @Test
    void aRushOfJoinsGetsDistinctConsecutiveTickets() throws Exception {
        Sale sale = sale(10, 100);
        open();
        List<Long> tickets = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 2000)
                    .mapToObj(i -> pool.submit(() -> room.join(sale.id(), person(i)).ticket()))
                    .toList();
            for (var f : futures) {
                tickets.add(f.get());
            }
        }
        assertThat(tickets).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 2000).boxed().toList());
    }
}

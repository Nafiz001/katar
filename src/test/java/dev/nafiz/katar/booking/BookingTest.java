package dev.nafiz.katar.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.nafiz.katar.DatabaseTest;
import dev.nafiz.katar.booking.Bookings.Booking;
import dev.nafiz.katar.booking.Bookings.PaymentOutcome;
import dev.nafiz.katar.booking.Holds.Hold;
import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.inventory.Journeys;
import dev.nafiz.katar.queue.Admitter;
import dev.nafiz.katar.queue.Sales;
import dev.nafiz.katar.queue.Sales.Sale;
import dev.nafiz.katar.queue.WaitingRoom;
import dev.nafiz.katar.queue.WaitingRoom.Admitted;

class BookingTest extends DatabaseTest {

    static final Instant OPENS = T0.plus(Duration.ofMinutes(1));

    @Autowired
    Journeys journeys;
    @Autowired
    Sales sales;
    @Autowired
    WaitingRoom room;
    @Autowired
    Admitter admitter;
    @Autowired
    Holds holds;
    @Autowired
    Bookings bookings;
    @Autowired
    Payments payments;
    @Autowired
    TicketSigner tickets;

    Sale sale;

    @BeforeEach
    void openSale() {
        long journey = journeys.ensure(753, LocalDate.of(2027, 3, 30));
        sale = sales.create(journey, OPENS, OPENS.plus(Duration.ofHours(4)), 1000, 1000);
        clock.set(OPENS);
        admitter.tick();
    }

    /** Joins, waits for a turn and claims a pass: a buyer standing in the booking room. */
    Admitted buyer(String name) {
        room.join(sale.id(), name);
        admitter.admit(sale.id(), clock.instant());
        return room.verify(sale.id(), room.claimPass(sale.id(), name).token());
    }

    @Test
    void aSeatHeldForPartOfTheRouteIsStillSoldForTheRest() {
        Admitted rahim = buyer("rahim"), sadia = buyer("sadia");
        holds.hold(rahim, "DHK", "TGL", "KA", 12);
        assertThatThrownBy(() -> holds.hold(sadia, "APT", "BBE", "KA", 12))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("seat_taken"));
        Hold rest = holds.hold(sadia, "TGL", "RJS", "KA", 12);
        assertThat(rest.coach()).isEqualTo("KA");
        assertThat(rest.number()).isEqualTo(12);
    }

    @Test
    void noOneHoldsMoreThanFourSeats() {
        Admitted rahim = buyer("rahim");
        for (int i = 1; i <= 4; i++) {
            holds.hold(rahim, "DHK", "RJS", "KA", i);
        }
        assertThatThrownBy(() -> holds.hold(rahim, "DHK", "RJS", "KA", 5))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("seat_limit"));
    }

    /** Ten parallel requests from one person cannot slip past the limit together. */
    @Test
    void theSeatLimitHoldsUnderParallelRequests() throws Exception {
        Admitted rahim = buyer("rahim");
        AtomicInteger held = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= 10; i++) {
                int seat = i;
                pool.submit(() -> {
                    try {
                        holds.hold(rahim, "DHK", "RJS", "KHA", seat);
                        held.incrementAndGet();
                    } catch (KatarException expected) {
                        // seat_limit for six of them
                    }
                });
            }
        }
        assertThat(held.get()).isEqualTo(4);
    }

    @Test
    void releasingAndExpiryBothGiveTheSeatBack() {
        Admitted rahim = buyer("rahim"), sadia = buyer("sadia");
        Hold h = holds.hold(rahim, "DHK", "RJS", "GA", 1);
        holds.release(rahim, h.id());
        holds.hold(sadia, "DHK", "RJS", "GA", 1);
        // sadia never pays: ten minutes on, the sweeper frees it for rahim
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(holds.sweep()).isEqualTo(1);
        rahim = room.verify(sale.id(), room.claimPass(sale.id(), "rahim").token());
        assertThat(holds.hold(rahim, "DHK", "RJS", "GA", 1).state()).isEqualTo("HELD");
    }

    @Test
    void aBookingIsPricedPerLegByClass() {
        Admitted rahim = buyer("rahim");
        Hold chair = holds.hold(rahim, "DHK", "TGL", "KA", 1);     // 3 legs of shovon chair
        Hold snigdha = holds.hold(rahim, "DHK", "TGL", "GHA", 1);  // 3 legs of snigdha
        Booking b = bookings.create(rahim, "key-1", List.of(chair.id(), snigdha.id()));
        assertThat(b.amountPoisha()).isEqualTo(3 * 4500 + 3 * 8600);
        assertThat(b.state()).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void theSameIdempotencyKeyAlwaysReturnsTheSameBooking() {
        Admitted rahim = buyer("rahim");
        Hold a = holds.hold(rahim, "DHK", "RJS", "KA", 1);
        Hold b = holds.hold(rahim, "DHK", "RJS", "KA", 2);
        Booking first = bookings.create(rahim, "key-1", List.of(a.id()));
        assertThat(bookings.create(rahim, "key-1", List.of(a.id())).id()).isEqualTo(first.id());
        assertThatThrownBy(() -> bookings.create(rahim, "key-1", List.of(b.id())))
                .isInstanceOfSatisfying(KatarException.class,
                        e -> assertThat(e.code()).isEqualTo("idempotency_key_reused"));
    }

    /** A flaky connection: the client fires the same request twelve times at once. One booking. */
    @Test
    void parallelRetriesMakeOneBooking() throws Exception {
        Admitted rahim = buyer("rahim");
        Hold a = holds.hold(rahim, "DHK", "RJS", "KA", 1);
        Set<UUID> ids = new HashSet<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<UUID>> fs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                fs.add(pool.submit(() -> bookings.create(rahim, "retry-key", List.of(a.id())).id()));
            }
            for (var f : fs) {
                ids.add(f.get());
            }
        }
        assertThat(ids).hasSize(1);
        assertThat(db.sql("SELECT count(*) FROM booking").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void payingIssuesATicketThatVerifiesOffline() {
        Admitted rahim = buyer("rahim");
        Hold a = holds.hold(rahim, "TGL", "RJS", "CHA", 7);
        Booking b = bookings.create(rahim, "key-1", List.of(a.id()));
        assertThat(payments.payDemo(b.id())).isEqualTo(PaymentOutcome.PAID);
        Booking paid = bookings.get(b.id());
        assertThat(paid.state()).isEqualTo("PAID");
        String payload = tickets.verify(paid.ticketCode()).orElseThrow();
        assertThat(payload).contains("753|2027-03-30").contains("CHA-7:TGL-RJS");
        // the buyer's place in the booking room is handed to the next person
        assertThatThrownBy(() -> room.claimPass(sale.id(), "rahim"))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("pass_expired"));
        // a second delivery of the same webhook changes nothing
        assertThat(payments.payDemo(b.id())).isEqualTo(PaymentOutcome.ALREADY_PAID);
        // and a ticket edited to another coach no longer verifies
        String code = paid.ticketCode();
        String body = new String(java.util.Base64.getUrlDecoder().decode(code.substring(0, code.indexOf('.'))));
        String edited = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(body.replace("CHA-7", "KA-7").getBytes()) + code.substring(code.indexOf('.'));
        assertThat(tickets.verify(edited)).isEmpty();
    }

    @Test
    void aWebhookWithoutTheRightSignatureIsRejected() {
        String body = "{\"booking\":\"" + UUID.randomUUID() + "\",\"reference\":\"x\",\"amountPoisha\":1,\"status\":\"COMPLETED\"}";
        assertThatThrownBy(() -> payments.webhook(body, "00ff"))
                .isInstanceOfSatisfying(KatarException.class, e -> assertThat(e.code()).isEqualTo("bad_signature"));
    }

    /** The money arrives after the seat was already given away: it must be refunded, not lost. */
    @Test
    void aPaymentAfterTheSeatsExpiredIsRefunded() {
        Admitted rahim = buyer("rahim");
        Hold a = holds.hold(rahim, "DHK", "RJS", "KA", 1);
        Booking b = bookings.create(rahim, "key-1", List.of(a.id()));
        clock.advance(Duration.ofMinutes(11));
        holds.sweep();
        assertThat(bookings.get(b.id()).state()).isEqualTo("CANCELLED");
        assertThat(payments.payDemo(b.id())).isEqualTo(PaymentOutcome.REFUND);
    }
}

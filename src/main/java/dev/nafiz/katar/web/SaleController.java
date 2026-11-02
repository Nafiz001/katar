package dev.nafiz.katar.web;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.nafiz.katar.booking.Bookings;
import dev.nafiz.katar.booking.Bookings.Booking;
import dev.nafiz.katar.booking.Holds;
import dev.nafiz.katar.booking.Holds.Hold;
import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.config.People;
import dev.nafiz.katar.inventory.Journeys;
import dev.nafiz.katar.inventory.Journeys.Stop;
import dev.nafiz.katar.inventory.Legs;
import dev.nafiz.katar.inventory.SeatInventory;
import dev.nafiz.katar.inventory.SeatInventory.CoachAvailability;
import dev.nafiz.katar.inventory.SeatInventory.SeatState;
import dev.nafiz.katar.queue.QueueTokens;
import dev.nafiz.katar.queue.Sales;
import dev.nafiz.katar.queue.Sales.Sale;
import dev.nafiz.katar.queue.Sales.SaleSummary;
import dev.nafiz.katar.queue.WaitingRoom;
import dev.nafiz.katar.queue.WaitingRoom.Admitted;
import dev.nafiz.katar.queue.WaitingRoom.Pass;
import dev.nafiz.katar.queue.WaitingRoom.Place;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * The sale, from the outside: the queue, the seat map, holds and bookings.
 * Seat endpoints take the admission pass as {@code Authorization: Pass <token>}.
 */
@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final Sales sales;
    private final Journeys journeys;
    private final SeatInventory inventory;
    private final WaitingRoom room;
    private final QueueTokens queueTokens;
    private final People people;
    private final Holds holds;
    private final Bookings bookings;
    private final Clock clock;
    private final MeterRegistry metrics;

    public SaleController(Sales sales, Journeys journeys, SeatInventory inventory, WaitingRoom room,
            QueueTokens queueTokens, People people, Holds holds, Bookings bookings, Clock clock,
            MeterRegistry metrics) {
        this.sales = sales;
        this.journeys = journeys;
        this.inventory = inventory;
        this.room = room;
        this.queueTokens = queueTokens;
        this.people = people;
        this.holds = holds;
        this.bookings = bookings;
        this.clock = clock;
        this.metrics = metrics;
    }

    @GetMapping
    List<SaleSummary> upcoming() {
        return sales.upcoming(clock.instant());
    }

    /** A sale with its route and coaches: everything the booking page needs to draw. */
    @GetMapping("/{id}")
    Map<String, Object> sale(@PathVariable long id) {
        Sale sale = sales.get(id);
        List<Stop> stops = journeys.stops(sale.journeyId());
        return Map.of("id", sale.id(), "opensAt", sale.opensAt(), "closesAt", sale.closesAt(),
                "open", sale.isOpen(clock.instant()), "stops", stops,
                "coaches", inventory.availability(sale.journeyId(), Legs.mask(0, stops.size() - 1)));
    }

    // ------------------------------------------------------------------ queue

    record JoinRequest(@NotBlank String nationalId) {
    }

    @PostMapping("/{id}/queue")
    Map<String, Object> join(@PathVariable long id, @Valid @RequestBody JoinRequest req) {
        String person = people.key(req.nationalId());
        Place place = room.join(id, person);
        metrics.counter("katar.queue.joins").increment();
        return Map.of("token", queueTokens.issue(id, person), "place", place);
    }

    @GetMapping("/{id}/queue/me")
    Place me(@PathVariable long id, @RequestHeader("X-Queue-Token") String token) {
        String person = queueTokens.person(id, token);
        return room.find(id, person).orElseThrow(() -> KatarException.forbidden("not_in_queue", "join the queue first"));
    }

    /**
     * Your place, pushed every second until your turn comes. EventSource
     * cannot set headers, so the token travels in the query string; it reveals
     * nothing but a place in one queue.
     */
    @GetMapping(path = "/{id}/queue/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable long id, @RequestParam String token) {
        String person = queueTokens.person(id, token);
        SseEmitter emitter = new SseEmitter(15 * 60_000L);
        Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Place p = room.find(id, person).orElse(null);
                    if (p == null) {
                        break;
                    }
                    emitter.send(SseEmitter.event().name("place").data(p));
                    if (p.admitted() || !"WAITING".equals(p.state())) {
                        break;
                    }
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                // the browser went away
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return emitter;
    }

    @PostMapping("/{id}/pass")
    Pass pass(@PathVariable long id, @RequestHeader("X-Queue-Token") String token) {
        return room.claimPass(id, queueTokens.person(id, token));
    }

    // ------------------------------------------------------------------ seats

    /** Free seats per coach for a journey between two stations. */
    @GetMapping("/{id}/availability")
    List<CoachAvailability> availability(@PathVariable long id, @RequestParam String from, @RequestParam String to) {
        Sale sale = sales.get(id);
        return inventory.availability(sale.journeyId(), mask(sale, from, to));
    }

    @GetMapping("/{id}/coaches/{coach}")
    List<SeatState> coach(@PathVariable long id, @PathVariable String coach, @RequestParam String from,
            @RequestParam String to) {
        Sale sale = sales.get(id);
        return inventory.coachMap(sale.journeyId(), coach, mask(sale, from, to));
    }

    record HoldRequest(@NotBlank String from, @NotBlank String to, @NotBlank String coach, Integer number) {
    }

    @PostMapping("/{id}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    Hold hold(@PathVariable long id, @RequestHeader("Authorization") String auth, @Valid @RequestBody HoldRequest req) {
        Admitted who = admitted(id, auth);
        try {
            Hold h = holds.hold(who, req.from(), req.to(), req.coach(), req.number());
            metrics.counter("katar.holds", "result", "held").increment();
            return h;
        } catch (KatarException e) {
            metrics.counter("katar.holds", "result", e.code()).increment();
            throw e;
        }
    }

    @DeleteMapping("/{id}/holds/{hold}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable long id, @PathVariable UUID hold, @RequestHeader("Authorization") String auth) {
        holds.release(admitted(id, auth), hold);
    }

    record BookingRequest(@NotEmpty List<UUID> holds) {
    }

    @PostMapping("/{id}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    Booking book(@PathVariable long id, @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody BookingRequest req) {
        return bookings.create(admitted(id, auth), key, req.holds());
    }

    private Admitted admitted(long saleId, String authorization) {
        if (authorization == null || !authorization.startsWith("Pass ")) {
            throw KatarException.forbidden("pass_required", "send your admission pass as Authorization: Pass <token>");
        }
        return room.verify(saleId, authorization.substring(5).trim());
    }

    private long mask(Sale sale, String from, String to) {
        int a = journeys.seqOf(sale.journeyId(), from);
        int b = journeys.seqOf(sale.journeyId(), to);
        if (b <= a) {
            throw KatarException.unprocessable("bad_route", "the train reaches " + from + " after " + to);
        }
        return Legs.mask(a, b);
    }
}

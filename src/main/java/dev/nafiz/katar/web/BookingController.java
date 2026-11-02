package dev.nafiz.katar.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.nafiz.katar.booking.Bookings;
import dev.nafiz.katar.booking.Bookings.Booking;
import dev.nafiz.katar.booking.Bookings.PaymentOutcome;
import dev.nafiz.katar.booking.Payments;
import dev.nafiz.katar.booking.TicketSigner;
import io.micrometer.core.instrument.MeterRegistry;

/** Bookings after they are made: paying, the gateway's webhook, and tickets. */
@RestController
public class BookingController {

    private final Bookings bookings;
    private final Payments payments;
    private final TicketSigner tickets;
    private final MeterRegistry metrics;

    public BookingController(Bookings bookings, Payments payments, TicketSigner tickets, MeterRegistry metrics) {
        this.bookings = bookings;
        this.payments = payments;
        this.tickets = tickets;
        this.metrics = metrics;
    }

    /** A booking id is a random UUID: knowing it is the capability to see it. */
    @GetMapping("/api/bookings/{id}")
    Map<String, Object> booking(@PathVariable UUID id) {
        Booking b = bookings.get(id);
        return Map.of("id", b.id(), "state", b.state(), "amountPoisha", b.amountPoisha(), "seats", b.seats(),
                "ticketCode", b.ticketCode() == null ? "" : b.ticketCode());
    }

    /** The demo gateway's "Pay" button. A real deployment would redirect to bKash or SSLCommerz instead. */
    @PostMapping("/api/bookings/{id}/demo-pay")
    Map<String, Object> demoPay(@PathVariable UUID id) {
        PaymentOutcome outcome = payments.payDemo(id);
        metrics.counter("katar.payments", "outcome", String.valueOf(outcome)).increment();
        return Map.of("outcome", String.valueOf(outcome), "booking", booking(id));
    }

    /** Where a real gateway calls back. Trusted only with a valid X-Signature. */
    @PostMapping("/api/payments/webhook")
    ResponseEntity<Map<String, String>> webhook(@RequestBody String body,
            @RequestHeader(name = "X-Signature", required = false) String signature) {
        PaymentOutcome outcome = payments.webhook(body, signature);
        return ResponseEntity.ok(Map.of("outcome", String.valueOf(outcome)));
    }

    /** What an inspector's app does offline, offered online for the demo. */
    @GetMapping("/api/tickets/verify")
    Map<String, Object> verify(@RequestParam String code) {
        return tickets.verify(code)
                .<Map<String, Object>>map(p -> Map.of("valid", true, "ticket", p))
                .orElse(Map.of("valid", false));
    }

    @GetMapping("/api/tickets/public-key")
    Map<String, String> publicKey() {
        return Map.of("algorithm", "Ed25519", "x509", tickets.publicKeyBase64());
    }
}

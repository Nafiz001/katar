package dev.nafiz.katar.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.nafiz.katar.booking.Bookings.Booking;
import dev.nafiz.katar.booking.Bookings.PaymentOutcome;
import dev.nafiz.katar.config.KatarException;
import dev.nafiz.katar.config.KatarProperties;
import dev.nafiz.katar.config.Signer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The payment gateway's side of a booking. A real gateway (bKash, Nagad,
 * SSLCommerz) calls back with a webhook; katar trusts it only if the body
 * carries a valid HMAC from the shared secret, compared in constant time.
 *
 * <p>There is no real gateway in the demo: {@link #payDemo} plays its part,
 * building and signing the same webhook a gateway would send, and handing it
 * to the same handler. Nothing else takes that shortcut.
 */
@Service
public class Payments {

    private final Signer signer;
    private final Bookings bookings;
    private final ObjectMapper json;

    public Payments(KatarProperties props, Bookings bookings, ObjectMapper json) {
        this.signer = new Signer(props.paymentSecret());
        this.bookings = bookings;
        this.json = json;
    }

    /** Handles a gateway webhook: verifies, parses, records. */
    public PaymentOutcome webhook(String body, String signatureHex) {
        byte[] expected = signer.mac(body.getBytes(StandardCharsets.UTF_8));
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signatureHex == null ? "" : signatureHex.trim());
        } catch (IllegalArgumentException e) {
            given = new byte[0];
        }
        if (!MessageDigest.isEqual(expected, given)) {
            throw KatarException.forbidden("bad_signature", "the webhook signature does not match");
        }
        Webhook hook;
        try {
            hook = json.readValue(body, Webhook.class);
        } catch (JacksonException e) {
            throw KatarException.unprocessable("bad_webhook", "the webhook body is not valid");
        }
        if (!"COMPLETED".equals(hook.status())) {
            return null; // failed or pending payments leave the booking waiting; its holds expire on time
        }
        return bookings.markPaid(hook.booking(), hook.reference(), hook.amountPoisha());
    }

    /** The demo gateway: approves the booking's full amount and delivers a signed webhook. */
    public PaymentOutcome payDemo(UUID bookingId) {
        Booking booking = bookings.get(bookingId);
        String body = json.writeValueAsString(new Webhook(bookingId, "DEMO-" + UUID.randomUUID().toString().substring(0, 8),
                booking.amountPoisha(), "COMPLETED"));
        return webhook(body, signer.hex(body));
    }

    /** The webhook a gateway sends. */
    public record Webhook(UUID booking, String reference, long amountPoisha, String status) {
    }
}

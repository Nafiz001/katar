package dev.nafiz.katar.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("katar")
public record KatarProperties(
        /** Signs admission passes. */
        @NotBlank String passSecret,
        /** Signs ticket codes, so inspectors can verify them offline. */
        @NotBlank String ticketSecret,
        /** Keys the hash of a national ID: the database never sees the ID itself. */
        @NotBlank String personPepper,
        /** Shared with the payment gateway, to sign its webhooks. */
        @NotBlank String paymentSecret,
        /** How long a held seat waits for payment. */
        @NotNull Duration holdTtl,
        /** How long an admitted person may stay in the booking room. */
        @NotNull Duration passTtl,
        /** Bangladesh Railway's limit: seats per person per journey. */
        @Min(1) int maxSeatsPerPerson,
        @NotNull Duration admitterInterval,
        @NotNull Duration sweeperInterval) {
}

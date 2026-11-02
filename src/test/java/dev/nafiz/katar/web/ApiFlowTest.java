package dev.nafiz.katar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import dev.nafiz.katar.DatabaseTest;
import dev.nafiz.katar.inventory.Journeys;
import dev.nafiz.katar.queue.Admitter;
import dev.nafiz.katar.queue.Sales;

/** The whole purchase over HTTP, the way the booking page drives it. */
@AutoConfigureMockMvc
class ApiFlowTest extends DatabaseTest {

    static final Instant OPENS = T0.plus(Duration.ofMinutes(1));
    static final String NID = "1990 1234 5678 9";

    @Autowired
    MockMvc http;
    @Autowired
    Journeys journeys;
    @Autowired
    Sales sales;
    @Autowired
    Admitter admitter;

    long sale;

    @BeforeEach
    void sale() {
        long journey = journeys.ensure(753, LocalDate.of(2027, 3, 30));
        sale = sales.create(journey, OPENS, OPENS.plus(Duration.ofHours(4)), 100, 100).id();
    }

    @Test
    void joinWaitHoldBookPayAndVerify() throws Exception {
        // the sale is listed before it opens
        http.perform(get("/api/sales")).andExpect(status().isOk()).andExpect(jsonPath("$[0].trainNumber").value(753));

        // join before opening: no number yet
        String joined = http.perform(post("/api/sales/{id}/queue", sale).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nationalId\":\"" + NID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.place.ticket").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String queueToken = JsonPath.read(joined, "$.token");

        // too early for a pass
        http.perform(post("/api/sales/{id}/pass", sale).header("X-Queue-Token", queueToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("not_your_turn"));

        // the sale opens and the admitter lets the first hundred in
        clock.set(OPENS);
        admitter.tick();
        http.perform(get("/api/sales/{id}/queue/me", sale).header("X-Queue-Token", queueToken))
                .andExpect(jsonPath("$.admitted").value(true));
        String pass = JsonPath.read(http.perform(post("/api/sales/{id}/pass", sale).header("X-Queue-Token", queueToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");

        // the seat map: Tangail to Rajshahi in the AC coach
        http.perform(get("/api/sales/{id}/coaches/CHA", sale).param("from", "TGL").param("to", "RJS"))
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[6].free").value(true));

        // holding needs the pass
        String body = "{\"from\":\"TGL\",\"to\":\"RJS\",\"coach\":\"CHA\",\"number\":7}";
        http.perform(post("/api/sales/{id}/holds", sale).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        String hold = JsonPath.read(http.perform(post("/api/sales/{id}/holds", sale)
                        .header("Authorization", "Pass " + pass).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        http.perform(get("/api/sales/{id}/coaches/CHA", sale).param("from", "TGL").param("to", "RJS"))
                .andExpect(jsonPath("$[6].free").value(false));
        // but the same seat is still free for Dhaka to Tangail
        http.perform(get("/api/sales/{id}/coaches/CHA", sale).param("from", "DHK").param("to", "TGL"))
                .andExpect(jsonPath("$[6].free").value(true));

        // a second seat, then let it go
        String spare = JsonPath.read(http.perform(post("/api/sales/{id}/holds", sale)
                        .header("Authorization", "Pass " + pass).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"TGL\",\"to\":\"RJS\",\"coach\":\"CHA\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        http.perform(delete("/api/sales/{id}/holds/{hold}", sale, spare).header("Authorization", "Pass " + pass))
                .andExpect(status().isNoContent());

        // book: no key is refused, then twice with the same key is one booking
        String booking = "{\"holds\":[\"" + hold + "\"]}";
        http.perform(post("/api/sales/{id}/bookings", sale).header("Authorization", "Pass " + pass)
                        .contentType(MediaType.APPLICATION_JSON).content(booking))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_required"));
        String id = JsonPath.read(http.perform(post("/api/sales/{id}/bookings", sale)
                        .header("Authorization", "Pass " + pass).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(booking))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPoisha").value(6 * 10400))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String again = JsonPath.read(http.perform(post("/api/sales/{id}/bookings", sale)
                        .header("Authorization", "Pass " + pass).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(booking))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(again).isEqualTo(id);

        // pay, get the ticket, verify it
        String paid = http.perform(post("/api/bookings/{id}/demo-pay", id))
                .andExpect(jsonPath("$.outcome").value("PAID"))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(paid, "$.booking.ticketCode");
        http.perform(get("/api/tickets/verify").param("code", code))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.ticket").value(org.hamcrest.Matchers.containsString("CHA-7:TGL-RJS")));

        // done: the pass no longer works
        http.perform(post("/api/sales/{id}/holds", sale).header("Authorization", "Pass " + pass)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBadNationalIdIsRefused() throws Exception {
        http.perform(post("/api/sales/{id}/queue", sale).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nationalId\":\"12345\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("bad_request"));
    }

    /** Metrics export is switched off in tests by Spring Boot; health is what the deploy checks. */
    @Test
    void theAppReportsItsHealth() throws Exception {
        http.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}

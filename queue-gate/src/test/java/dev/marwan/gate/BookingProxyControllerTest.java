package dev.marwan.gate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookingProxyControllerTest extends RedisTestBase {

    static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWiremock() {
        WIREMOCK.stop();
    }

    @DynamicPropertySource
    static void bookingServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("booking-service.base-url", () -> "http://localhost:" + WIREMOCK.port());
    }

    @Autowired private MockMvc mvc;
    @Autowired private QueueService queueService;

    private static final String BODY = """
        {"slotId":1,"phone":"+60123456789","partySize":2,"idempotencyKey":"k1"}
        """;

    @Test
    void anAdmittedTokenIsForwardedAndTheResponseRelayed() throws Exception {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/bookings"))
                .willReturn(WireMock.aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"bookingId":42,"status":"PENDING_DEPOSIT","idempotentReplay":false}
                            """)));

        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(42));

        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/bookings")));
    }

    @Test
    void aMissingTokenIsRejectedWithoutCallingDownstream() throws Exception {
        WIREMOCK.resetAll();

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TOKEN_INVALID"));

        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/bookings")));
    }

    @Test
    void aTokenCannotBeUsedTwice() throws Exception {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/bookings"))
                .willReturn(WireMock.aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"bookingId":43,"status":"PENDING_DEPOSIT","idempotentReplay":false}
                            """)));

        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/bookings")));
    }

    @Test
    void aDownstreamConflictIsRelayedWithItsBody() throws Exception {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/bookings"))
                .willReturn(WireMock.aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"reason":"SLOT_SOLD_OUT","details":{"slotId":1,"requested":2,"remaining":0}}
                            """)));

        JoinResult mine = queueService.join(DropRegistry.DEFAULT_ID);
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SLOT_SOLD_OUT"))
                .andExpect(jsonPath("$.details.remaining").value(0));
    }

    @Test
    void depositIsProxiedWithoutRequiringAToken() throws Exception {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/bookings/42/deposit"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"bookingId":42,"status":"CONFIRMED","idempotentReplay":false}
                            """)));

        mvc.perform(post("/bookings/42/deposit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
}

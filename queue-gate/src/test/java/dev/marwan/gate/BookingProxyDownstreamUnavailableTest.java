package dev.marwan.gate;

import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class BookingProxyDownstreamUnavailableTest extends RedisTestBase {

    @DynamicPropertySource
    static void bookingServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("booking-service.base-url", () -> "http://localhost:59999");
    }

    @Autowired private MockMvc mvc;
    @Autowired private QueueService queueService;

    private static final String BODY = """
        {"slotId":1,"phone":"+60123456789","partySize":2,"idempotencyKey":"k1"}
        """;

    @Test
    void downstreamConnectionFailureReturns503WithRetryAfter() throws Exception {
        JoinResult mine = queueService.join();
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.reason").value("BOOKING_SERVICE_UNAVAILABLE"));
    }
}

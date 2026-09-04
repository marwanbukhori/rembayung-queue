package dev.marwan.gate;

import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class QueueControllerTest extends RedisTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private QueueService queueService;

    @Test
    void joiningReturnsATicketAndPosition() throws Exception {
        mvc.perform(post("/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value(1))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.admitted").value(false));
    }

    @Test
    void joiningBeforeOpenReturnsServiceUnavailableWithRetryAfter() throws Exception {
        clock().setNow(OPENS_AT.minusSeconds(120));

        mvc.perform(post("/queue"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.reason").value("NOT_OPEN"))
                .andExpect(jsonPath("$.details.secondsUntilOpen").value(120));
    }

    @Test
    void joiningAfterCloseReturnsConflict() throws Exception {
        clock().setNow(OPENS_AT.plus(Duration.ofMinutes(31)));

        mvc.perform(post("/queue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DROP_CLOSED"));
    }

    @Test
    void joiningPastTheCapReturnsSoldOut() throws Exception {
        for (int i = 0; i < 250; i++) {
            queueService.join();
        }

        mvc.perform(post("/queue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SOLD_OUT"));
    }

    @Test
    void pollingReturnsPositionAndAdmission() throws Exception {
        JoinResult mine = queueService.join();
        clock().advance(Duration.ofSeconds(1));  // 200 admitted at 200/s

        mvc.perform(get("/queue/" + mine.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.admitted").value(true));
    }

    @Test
    void pollingAnUnknownTokenReturnsNotFound() throws Exception {
        mvc.perform(get("/queue/no-such-token"))
                .andExpect(status().isNotFound());
    }
}

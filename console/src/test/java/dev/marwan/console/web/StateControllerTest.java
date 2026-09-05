package dev.marwan.console.web;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.cluster.PodHealth;
import dev.marwan.console.cluster.PodHealthProvider;
import dev.marwan.console.state.DemoState;
import dev.marwan.console.state.DemoStateProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StateController.class)
@Import(StateControllerTest.Properties.class)
class StateControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DemoStateProvider state;

    @MockitoBean
    PodHealthProvider pods;

    // The whole point of the endpoint: everything underneath can be down and
    // the page still gets 200 and a sentence saying why, because a console that
    // 500s during an incident is one more thing to fix during the incident.
    @Test
    void everythingBeingDownIsStillATwoHundred() throws Exception {
        given(state.currentFor(eq("default"), anyLong()))
                .willReturn(DemoState.unavailable("queue-gate did not answer"));
        given(pods.current()).willReturn(PodHealth.unavailable("cluster not readable"));

        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drop.available").value(false))
                .andExpect(jsonPath("$.drop.detail").value("queue-gate did not answer"))
                .andExpect(jsonPath("$.pods.available").value(false));
    }

    /** The canonical drop is what an unasked request means. */
    @Test
    void anUnaskedRequestReadsTheCanonicalDrop() throws Exception {
        given(state.currentFor("default", 1L))
                .willReturn(new DemoState(true, null, "default", 250, 202, 48, 0, 40, 10, 30));
        given(pods.current()).willReturn(PodHealth.of(java.util.List.of()));

        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drop.dropId").value("default"))
                .andExpect(jsonPath("$.drop.oversold").value(0))
                .andExpect(jsonPath("$.drop.seatsTaken").value(202));
    }

    static class Properties {
        @Bean
        ConsoleProperties consoleProperties() {
            return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                    "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "rembayung");
        }
    }
}

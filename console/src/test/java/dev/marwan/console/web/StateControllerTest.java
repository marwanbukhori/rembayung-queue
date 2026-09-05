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
        given(state.currentFor("default"))
                .willReturn(DemoState.unavailable("queue-gate did not answer"));
        given(pods.current()).willReturn(PodHealth.unavailable("cluster not readable"));

        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drop.available").value(false))
                .andExpect(jsonPath("$.drop.detail").value("queue-gate did not answer"))
                .andExpect(jsonPath("$.pods.available").value(false));
    }

    /**
     * The canonical drop is what an unasked request means — and the only thing
     * a request can ask for. There is no slot parameter: the gate says which
     * slot a drop sells, so the page cannot pair one drop's queue with another
     * drop's seats.
     */
    @Test
    void anUnaskedRequestReadsTheCanonicalDrop() throws Exception {
        given(state.currentFor("default"))
                .willReturn(new DemoState(true, null, "default", 1, 250, 202, 48, 0, 40, 10, 30));
        given(pods.current()).willReturn(PodHealth.of("marwanbukhori-dev", java.util.List.of()));

        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drop.dropId").value("default"))
                .andExpect(jsonPath("$.drop.oversold").value(0))
                .andExpect(jsonPath("$.drop.seatsTaken").value(202))
                .andExpect(jsonPath("$.drop.slotId").value(1));
    }

    /**
     * A drop the browser names is read, and its slot comes back with it. The
     * removed ?slot= parameter was the console being told something the gate
     * knows; asking for a drop is now the whole request.
     */
    @Test
    void aNamedDropIsReadWithItsOwnSlot() throws Exception {
        given(state.currentFor("d-abc12345"))
                .willReturn(new DemoState(true, null, "d-abc12345", 4242, 250, 3, 247, 0, 9, 4, 5));
        given(pods.current()).willReturn(PodHealth.of("marwanbukhori-dev", java.util.List.of()));

        mvc.perform(get("/api/state").param("drop", "d-abc12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drop.dropId").value("d-abc12345"))
                .andExpect(jsonPath("$.drop.slotId").value(4242));
    }

    static class Properties {
        @Bean
        ConsoleProperties consoleProperties() {
            return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                    "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "marwanbukhori-dev",
                    "s3cret-demo-key", "compute-deploy", "grafana/k6:0.53.0",
                    new ConsoleProperties.Pool("booking-service", 5, 20));
        }
    }
}

package dev.marwan.console.objects;

import dev.marwan.console.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ObjectsController.class)
@Import(ObjectsControllerTest.Properties.class)
class ObjectsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ObjectsProvider objects;

    /** Public, unkeyed: a GET, like every other read on this console. */
    @Test
    void anObjectIsDescribedWithoutAKey() throws Exception {
        given(objects.describe("pod", "queue-gate-x")).willReturn(new ObjectDetail("pod", "queue-gate-x",
                true, null, ObjectDetail.OK, "Running, ready", List.of(), List.of(), List.of()));

        mvc.perform(get("/api/objects/pod/queue-gate-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Running, ready"))
                .andExpect(jsonPath("$.tone").value("ok"));
    }

    @Test
    void somethingNotFoundIs404() throws Exception {
        given(objects.describe("pod", "gone")).willThrow(new ObjectNotFound("pod gone does not exist here"));

        mvc.perform(get("/api/objects/pod/gone")).andExpect(status().isNotFound());
    }

    /**
     * Review finding 1 (critical): the 404 body echoed the requested name as
     * text/html, so a crafted link ran script on the demo's own origin.
     */
    @Test
    void aNotFoundNeverReflectsTheRequestAsHtml() throws Exception {
        String payload = "<img src=x onerror=alert(1)>";
        given(objects.describe(payload, "x")).willThrow(new ObjectNotFound("no such kind: " + payload));

        mvc.perform(get("/api/objects/{kind}/x", payload).header("Accept", "text/html,*/*"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", not(containsString("text/html"))))
                .andExpect(content().string(not(containsString("<img"))));
    }

    @Test
    void recentJobsAreListed() throws Exception {
        given(objects.recentJobs()).willReturn(List.of(
                new ObjectSummary("job", "load-d-3fa951d5", "ok", "rush: Complete in 110s", "2026-09-25T07:23:00Z")));

        mvc.perform(get("/api/objects").param("kind", "job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("load-d-3fa951d5"));
    }

    @Test
    void listingAnyOtherKindIs404() throws Exception {
        mvc.perform(get("/api/objects").param("kind", "secret")).andExpect(status().isNotFound());
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

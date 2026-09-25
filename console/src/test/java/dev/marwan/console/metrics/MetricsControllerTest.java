package dev.marwan.console.metrics;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsController.class)
@Import(MetricsControllerTest.Properties.class)
class MetricsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MetricsService metrics;

    @Test
    void aNamedChartIsServed() throws Exception {
        given(metrics.chart(ChartName.POOL, 15)).willReturn(new Chart("pool", "connections", null,
                List.of(new Series("booking-a", List.of(new double[]{1, 3}))), null,
                List.of(new Reading("booking-a", 4))));

        mvc.perform(get("/api/metrics/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series[0].label").value("booking-a"))
                .andExpect(jsonPath("$.now[0].value").value(4.0));
    }

    @Test
    void theWindowIsPassedThrough() throws Exception {
        given(metrics.chart(ChartName.REQUESTS, 30))
                .willReturn(new Chart("requests", "req/s", null, List.of(), null, List.of()));

        mvc.perform(get("/api/metrics/requests").param("minutes", "30")).andExpect(status().isOk());
        then(metrics).should().chart(ChartName.REQUESTS, 30);
    }

    @Test
    void anyOtherNameIs404WithoutEchoingIt() throws Exception {
        mvc.perform(get("/api/metrics/{chart}", "<script>").header("Accept", "text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
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

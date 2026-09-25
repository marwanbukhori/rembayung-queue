package dev.marwan.console.web;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.auth.AccessKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DemoKeyController.class)
@Import(DemoKeyControllerOffTest.Properties.class)

class DemoKeyControllerOffTest {

    @Autowired
    MockMvc mvc;

    /** Review focus 2: off means a fixed 404 that never carries the key. */
    @Test
    void nothingIsHandedOutWhileSharingIsOff() throws Exception {
        mvc.perform(get("/api/demo-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("s3cret-demo-key"))));
    }

    static class Properties {
        @Bean
        ConsoleProperties consoleProperties() {
            return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                    "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "marwanbukhori-dev",
                    "s3cret-demo-key", "compute-deploy", "grafana/k6:0.53.0",
                    new ConsoleProperties.Pool("booking-service", 5, 20));
        }

        @Bean
        AccessKey accessKey() {
            return new AccessKey("s3cret-demo-key");
        }
    }
}

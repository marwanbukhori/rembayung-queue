package dev.marwan.booking.chaos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChaosController.class)
@Import(ChaosControllerTest.Wiring.class)
class ChaosControllerTest {

    @Autowired
    MockMvc mvc;

    static class Wiring {
        @Bean
        ChaosState chaosState() {
            return new ChaosState(new ChaosStateTest.FakePool(), java.time.Clock.systemUTC());
        }
    }

    @Test
    void startsReadsAndEndsAFault() throws Exception {
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fault\":\"squeeze-pool\",\"seconds\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fault").value("squeeze-pool"));
        mvc.perform(get("/internal/chaos")).andExpect(jsonPath("$.fault").value("squeeze-pool"));
        mvc.perform(delete("/internal/chaos")).andExpect(status().isNoContent());
        mvc.perform(get("/internal/chaos")).andExpect(jsonPath("$.fault").doesNotExist());
    }

    @Test
    void anUnknownFaultIs400() throws Exception {
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fault\":\"drop-tables\",\"seconds\":10}"))
                .andExpect(status().isBadRequest());
    }
}

package dev.marwan.console.chaos;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.marwan.console.auth.AccessKey;

@WebMvcTest(ChaosController.class)
@Import(ChaosControllerTest.Key.class)
class ChaosControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ChaosService chaos;

    static class Key {
        @Bean AccessKey accessKey() { return new AccessKey("s3cret-demo-key"); }
    }

    static final ChaosService.ActiveFault FAULT = new ChaosService.ActiveFault("squeeze-pool",
            Instant.parse("2026-09-26T12:00:00Z"), Instant.parse("2026-09-26T12:02:00Z"));

    @Test
    void injectingNeedsTheKey() throws Exception {
        mvc.perform(post("/api/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"fault\":\"squeeze-pool\"}"))
                .andExpect(status().isUnauthorized());
        verify(chaos, never()).inject(anyString());
    }

    @Test
    void injectingWithTheKeyStartsIt() throws Exception {
        when(chaos.inject("squeeze-pool")).thenReturn(FAULT);
        mvc.perform(post("/api/chaos").header("X-Console-Key", "s3cret-demo-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fault\":\"squeeze-pool\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fault").value("squeeze-pool"));
    }

    @Test
    void aBusyLockIs409NamingTheActiveFault() throws Exception {
        when(chaos.inject("kill-booking-pod")).thenThrow(new ChaosService.Busy(FAULT));
        mvc.perform(post("/api/chaos").header("X-Console-Key", "s3cret-demo-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fault\":\"kill-booking-pod\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BUSY"))
                .andExpect(jsonPath("$.active.fault").value("squeeze-pool"));
    }

    @Test
    void theActiveFaultIsPublic() throws Exception {
        when(chaos.current()).thenReturn(Optional.of(FAULT));
        mvc.perform(get("/api/chaos")).andExpect(jsonPath("$.fault").value("squeeze-pool"));
    }
}

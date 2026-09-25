package dev.marwan.console.agent;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.marwan.console.auth.AccessKey;

@WebMvcTest(AnalysesController.class)
@Import(AnalysesControllerTest.Key.class)
class AnalysesControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisStore store;

    @MockitoBean
    RunAnalyst runAnalyst;

    static Analysis one() {
        return AnalysisStoreTest.analysis("load-a", Instant.parse("2026-09-25T10:00:00Z"), null);
    }

    @Test
    void listsReportsNewestFirstWithoutAKey() throws Exception {
        when(store.list()).thenReturn(List.of(one()));
        mvc.perform(get("/api/analyses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].job").value("load-a"))
                .andExpect(jsonPath("$[0].source").value("model"))
                .andExpect(jsonPath("$[0].claims").value(1));
    }

    @Test
    void readsOneReport() throws Exception {
        when(store.get("load-a")).thenReturn(Optional.of(one()));
        mvc.perform(get("/api/analyses/load-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.wentWell[0].facts[0]").value("F1"));
    }

    @Test
    void anUnknownRunIs404() throws Exception {
        when(store.get("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/analyses/nope")).andExpect(status().isNotFound());
    }

    @Test
    void reanalysingNeedsTheKey() throws Exception {
        mvc.perform(post("/api/analyses/load-a/rerun")).andExpect(status().isUnauthorized());
        verify(runAnalyst, never()).rerun("load-a");
    }

    @Test
    void reanalysingWithTheKeyStartsIt() throws Exception {
        when(runAnalyst.rerun("load-a")).thenReturn(RunAnalyst.Rerun.STARTED);
        mvc.perform(post("/api/analyses/load-a/rerun").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isAccepted());
    }

    @Test
    void reanalysingWhileBusyIs409() throws Exception {
        when(runAnalyst.rerun("load-a")).thenReturn(RunAnalyst.Rerun.BUSY);
        mvc.perform(post("/api/analyses/load-a/rerun").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isConflict());
    }

    static class Key {
        @Bean
        AccessKey accessKey() {
            return new AccessKey("s3cret-demo-key");
        }
    }
}

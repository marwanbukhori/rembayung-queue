package dev.marwan.console.incident;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import dev.marwan.console.slo.SloReading;
import dev.marwan.console.slo.SloService;

@WebMvcTest(IncidentsController.class)
@Import(IncidentsControllerTest.Key.class)
class IncidentsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean IncidentStore store;
    @MockitoBean Remediation remediation;
    @MockitoBean SloService slo;

    static class Key {
        @Bean AccessKey accessKey() { return new AccessKey("s3cret-demo-key"); }
    }

    static Incident incident() {
        Incident i = new Incident();
        i.id = "inc-1";
        i.kind = "drill";
        i.fault = "squeeze-pool";
        i.status = "open";
        i.openedAt = Instant.parse("2026-09-26T12:00:00Z");
        return i;
    }

    @Test
    void incidentsAndSlosArePublic() throws Exception {
        when(store.list()).thenReturn(List.of(incident()));
        when(store.get("inc-1")).thenReturn(Optional.of(incident()));
        when(slo.now()).thenReturn(new SloReading(Instant.now(), true, null, true, 0.95, 2.4));
        mvc.perform(get("/api/incidents")).andExpect(jsonPath("$[0].id").value("inc-1"));
        mvc.perform(get("/api/incidents/inc-1")).andExpect(jsonPath("$.fault").value("squeeze-pool"));
        mvc.perform(get("/api/incidents/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/slo")).andExpect(jsonPath("$.now.successRatio").value(0.95));
    }

    @Test
    void approvingNeedsTheKey() throws Exception {
        mvc.perform(post("/api/incidents/inc-1/proposals/1/approve")).andExpect(status().isUnauthorized());
        verify(remediation, never()).approve(anyString(), anyInt());
    }

    @Test
    void approvalOutcomesMapToStatuses() throws Exception {
        when(remediation.approve("inc-1", 1)).thenReturn(Remediation.Result.APPLIED);
        when(remediation.approve("inc-1", 2)).thenReturn(Remediation.Result.NOT_PENDING);
        when(remediation.approve("inc-9", 1)).thenReturn(Remediation.Result.NOT_FOUND);
        mvc.perform(post("/api/incidents/inc-1/proposals/1/approve").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/incidents/inc-1/proposals/2/approve").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/incidents/inc-9/proposals/1/approve").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isNotFound());
    }
}

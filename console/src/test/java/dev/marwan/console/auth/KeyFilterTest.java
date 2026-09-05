package dev.marwan.console.auth;

import dev.marwan.console.cluster.PodHealth;
import dev.marwan.console.cluster.PodHealthProvider;
import dev.marwan.console.state.DemoState;
import dev.marwan.console.state.DemoStateProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "console.access-key=s3cret-demo-key")
@AutoConfigureMockMvc
class KeyFilterTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private DemoStateProvider state;
    @MockitoBean private PodHealthProvider pods;

    /**
     * The handler must not run at all — not "run and return nothing useful".
     * Verifying the provider was never asked is the difference between a gate
     * and a curtain.
     */
    @Test
    void anUnkeyedReadIsRefusedBeforeAnyHandlerRuns() throws Exception {
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());

        verify(state, never()).currentFor(anyString());
        verify(pods, never()).current();
    }

    @Test
    void theKeyIsAcceptedInAHeader() throws Exception {
        given(state.currentFor("default"))
                .willReturn(new DemoState(true, null, "default", 1, 250, 0, 250, 0, 0, 0, 0));
        given(pods.current()).willReturn(PodHealth.of(java.util.List.of()));

        mvc.perform(get("/api/state").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isOk());
    }

    /**
     * The owner sends a link, and a header cannot be put in a link. This is why
     * the query parameter exists, so it is asserted rather than assumed.
     */
    @Test
    void theKeyIsAcceptedInTheQueryString() throws Exception {
        given(state.currentFor("default"))
                .willReturn(new DemoState(true, null, "default", 1, 250, 0, 250, 0, 0, 0, 0));
        given(pods.current()).willReturn(PodHealth.of(java.util.List.of()));

        mvc.perform(get("/api/state").param("key", "s3cret-demo-key"))
                .andExpect(status().isOk());
    }

    @Test
    void theWrongKeyIsRefused() throws Exception {
        mvc.perform(get("/api/state").header("X-Console-Key", "not-the-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/state").param("key", "not-the-key"))
                .andExpect(status().isUnauthorized());
    }

    /** Reads are gated too: one rule, not two. */
    @Test
    void theDocumentationIsGatedLikeEverythingElse() throws Exception {
        mvc.perform(get("/api/docs")).andExpect(status().isUnauthorized());
    }

    /** And so is the one endpoint that changes anything. */
    @Test
    void creatingADropIsGated() throws Exception {
        mvc.perform(post("/api/drops")).andExpect(status().isUnauthorized());
    }

    /**
     * The page itself is not gated: the browser fetches its script and
     * stylesheet without the query string that opened the link, so gating the
     * static shell would break the link the moment it worked. The shell holds
     * no numbers — every one of them comes from /api.
     */
    @Test
    void theStaticShellIsNotGated() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}

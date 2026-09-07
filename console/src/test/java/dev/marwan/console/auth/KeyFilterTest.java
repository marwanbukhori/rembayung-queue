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

    private void stubState() {
        given(state.currentFor("default"))
                .willReturn(new DemoState(true, null, "default", 1, 250, 0, 250, 0, 0, 0, 0));
        given(pods.current()).willReturn(PodHealth.of("marwanbukhori-dev", java.util.List.of()));
    }

    /**
     * The console's address is in a public README. A stranger following it has
     * to see the numbers, or the link advertises a system that looks broken.
     */
    @Test
    void anUnkeyedReadIsAllowed() throws Exception {
        stubState();

        mvc.perform(get("/api/state")).andExpect(status().isOk());
        mvc.perform(get("/api/docs")).andExpect(status().isOk());
    }

    /**
     * The handler must not run at all — not "run and return nothing useful".
     * Verifying the provider was never asked is the difference between a gate
     * and a curtain.
     */
    @Test
    void anUnkeyedWriteIsRefusedBeforeAnyHandlerRuns() throws Exception {
        mvc.perform(post("/api/drops")).andExpect(status().isUnauthorized());

        verify(state, never()).currentFor(anyString());
        verify(pods, never()).current();
    }

    /**
     * Every write, not just the one that happens to be checked. These are the
     * calls that spend the namespace's CPU quota.
     */
    @Test
    void everyWriteIsGated() throws Exception {
        mvc.perform(post("/api/drops")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/drops/d-1234abcd/load")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/drops/d-1234abcd/rate")).andExpect(status().isUnauthorized());
    }

    @Test
    void theKeyIsAcceptedInAHeader() throws Exception {
        stubState();

        mvc.perform(get("/api/state").header("X-Console-Key", "s3cret-demo-key"))
                .andExpect(status().isOk());
    }

    /**
     * The owner sends a link, and a header cannot be put in a link. This is why
     * the query parameter exists, so it is asserted rather than assumed.
     */
    @Test
    void theKeyIsAcceptedInTheQueryString() throws Exception {
        stubState();

        mvc.perform(get("/api/state").param("key", "s3cret-demo-key"))
                .andExpect(status().isOk());
    }

    /**
     * Asserted on a write, because a read would pass with no key at all and so
     * could not tell a rejected key from an ignored one.
     */
    @Test
    void theWrongKeyIsRefusedOnAWrite() throws Exception {
        mvc.perform(post("/api/drops").header("X-Console-Key", "not-the-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/drops").param("key", "not-the-key"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The page itself is not gated: the browser fetches its script and
     * stylesheet without the query string that opened the link, so gating the
     * static shell would break the link the moment it worked.
     */
    @Test
    void theStaticShellIsNotGated() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}

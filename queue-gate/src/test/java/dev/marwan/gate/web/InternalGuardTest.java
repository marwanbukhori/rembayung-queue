package dev.marwan.gate.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The guard is the only thing standing between /internal and the internet, so
 * the encodings matter more than the happy path.
 */
class InternalGuardTest {

    private final InternalGuard guard = new InternalGuard();

    private MockHttpServletResponse throughRouter(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        // What OpenShift's router adds to everything it proxies.
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        guard.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    @Test
    void thePublicApiIsReachableFromOutside() throws Exception {
        for (String uri : new String[]{"/queue", "/queue/abc-123", "/bookings", "/bookings/42/deposit"}) {
            assertThat(throughRouter(uri).getStatus()).as(uri).isEqualTo(200);
        }
    }

    @Test
    void internalIsRefusedFromOutside() throws Exception {
        assertThat(throughRouter("/internal/drops/default/state").getStatus()).isEqualTo(404);
    }

    /**
     * The bypass the first version had. getRequestURI returns the RAW URI, so a
     * block-list asking startsWith("/internal") never matches these — while
     * Spring's routing decodes and normalises them and dispatches anyway.
     * An allow-list refuses them for the opposite reason: they do not look
     * public either.
     */
    @Test
    void encodedAndDoubledPathsCannotSlipPast() throws Exception {
        for (String uri : new String[]{
                "/%69nternal/drops",      // percent-encoded 'i'
                "//internal/drops",       // doubled slash
                "/./internal/drops",      // dot segment
                "/INTERNAL/drops",        // case
                "/internal"               // the bare prefix
        }) {
            assertThat(throughRouter(uri).getStatus()).as(uri).isEqualTo(404);
        }
    }

    /** A similarly-named future route must not inherit public access. */
    @Test
    void aPrefixMatchIsNotEnough() throws Exception {
        assertThat(throughRouter("/queuemanager").getStatus()).isEqualTo(404);
        assertThat(throughRouter("/bookingsadmin").getStatus()).isEqualTo(404);
    }

    /** In-cluster callers carry no router headers and are left alone. */
    @Test
    void inClusterCallersReachInternal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/drops");
        request.setRequestURI("/internal/drops");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        guard.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aRefusedRequestNeverReachesTheController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/drops");
        request.setRequestURI("/internal/drops");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        FilterChain chain = mock(FilterChain.class);
        guard.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                                        org.mockito.ArgumentMatchers.any());
    }
}

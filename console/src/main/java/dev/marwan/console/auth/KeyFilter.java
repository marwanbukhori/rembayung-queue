package dev.marwan.console.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects an unkeyed call before any handler runs.
 *
 * A filter rather than a check inside each controller: a check has to be
 * remembered on every new endpoint, and the endpoint someone forgets is exactly
 * the one that creates drops. Registered across {@code /api/*}, so reads are
 * gated as well as writes — with two users there is no reason to leave anything
 * open, and one rule is easier to reason about than two.
 *
 * <h2>Why the key may travel in the query string</h2>
 * The owner sends a link. A header cannot be put in a link, and a login form
 * for an audience of two is more moving parts than the thing it protects. The
 * cost is a key that lands in the browser's history and in any access log in
 * front of this service; that is an acceptable price for a demo key that opens
 * a dashboard and can be rotated by editing one Secret.
 *
 * <h2>What is not gated</h2>
 * The static page: the browser fetches its own script and stylesheet without
 * the query string that opened the page, so gating those would break the link
 * the moment it worked. The shell renders nothing on its own — every number on
 * it comes from {@code /api}. Health and Prometheus live on management port
 * 9090, which is not on the public Route at all, and this filter is registered
 * on the main servlet context only.
 */
public class KeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KeyFilter.class);

    public static final String HEADER = "X-Console-Key";
    public static final String QUERY_PARAM = "key";

    private final AccessKey key;

    public KeyFilter(AccessKey key) {
        this.key = key;
        if (!key.configured()) {
            log.warn("CONSOLE_ACCESS_KEY is not set: every /api call will be refused. "
                    + "This is deliberate — an unconfigured console must be shut, not open.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (key.accepts(presentedBy(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Deliberately no WWW-Authenticate header: it would make the browser
        // raise a native credentials dialog that this console cannot satisfy.
        response.getWriter().write("""
                {"error":"CONSOLE_KEY_REQUIRED",\
                "detail":"Send the console key as the X-Console-Key header or a key query parameter."}""");
    }

    private static String presentedBy(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        // getParameter reads the query string here and nothing else: it would
        // also consume a form-encoded body, and this API speaks only JSON.
        return header != null ? header : request.getParameter(QUERY_PARAM);
    }
}

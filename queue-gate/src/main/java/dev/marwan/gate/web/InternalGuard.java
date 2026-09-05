package dev.marwan.gate.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.List;

/**
 * Lets requests from the public Route reach only the public API. Everything
 * else — /internal above all — is refused.
 *
 * WHY THIS EXISTS. queue-gate's Route carries no `path`, so it publishes every
 * mapping on the container port, and the service runs no other filter. Verified
 * before this class: an unauthenticated GET to
 * https://queue-gate-.../internal/drops/default/state returned 200 with real
 * data, and POST /internal/drops sat beside it. Anyone could have minted a drop
 * opening immediately at 200/s and booked against slot 1. Scoping the Route was
 * tried and reverted — this sandbox forbids setting spec.host, so a second Route
 * could not share the hostname and /bookings moved to a different origin,
 * breaking every existing client.
 *
 * WHY IT IS AN ALLOW-LIST, NOT A BLOCK-LIST. The first version asked
 * `getRequestURI().startsWith("/internal")`. getRequestURI returns the RAW,
 * undecoded URI, while Spring routes on a decoded and normalised path — so
 * `/%69nternal/drops` does not match the guard, sails through, and is then
 * dispatched to the controller anyway. Same for `//internal` and `/./internal`.
 * A block-list has to anticipate every encoding; an allow-list does not, because
 * anything an attacker mangles to evade it simply fails to match the allowed
 * prefixes and is refused. The same trick that hides a path from the guard hides
 * it from the allow-list too, and default-deny turns that into a rejection
 * rather than a bypass.
 *
 * Belt and braces: the path is normalised through UrlPathHelper before matching,
 * so the allow-list itself sees what Spring will route on rather than the raw
 * bytes.
 *
 * WHY THE HEADER IS THE DISCRIMINATOR. OpenShift's router adds X-Forwarded-* to
 * everything it proxies, and a caller cannot suppress that from outside — the
 * router sets it, it is not taken from the client. A pod calling
 * http://queue-gate:8080 over the Service network never touches the router and
 * carries none. Note the direction of failure: a request WITH the header is
 * constrained, so a spoofed header can only get a caller refused, never
 * admitted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalGuard.class);

    /** Everything queue-gate legitimately serves to the internet. */
    private static final List<String> PUBLIC_PREFIXES = List.of("/queue", "/bookings");

    private final UrlPathHelper paths = new UrlPathHelper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (arrivedThroughTheRouter(request) && !isPublic(paths.getPathWithinApplication(request))) {
            log.warn("refused external request to {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            // 404 rather than 403: a path an outside caller may not use is, as far
            // as they are concerned, not there. 403 confirms it exists and is
            // worth probing.
            response.getWriter().write("{\"error\":\"NOT_FOUND\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean arrivedThroughTheRouter(HttpServletRequest request) {
        return request.getHeader("X-Forwarded-For") != null
                || request.getHeader("X-Forwarded-Host") != null
                || request.getHeader("X-Forwarded-Proto") != null
                || request.getHeader("Forwarded") != null;
    }

    /**
     * Anchored deliberately: a prefix must be the whole path or be followed by a
     * slash. Plain startsWith would let a future /queuemanager or /bookingsadmin
     * inherit public access simply by being named similarly.
     */
    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}

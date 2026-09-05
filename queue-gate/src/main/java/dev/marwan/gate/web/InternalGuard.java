package dev.marwan.gate.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses /internal/* to anything that arrived through the public Route.
 *
 * These endpoints create drops and read cluster state. They were published to
 * the internet from the moment they were written, because a Route with no
 * `path` forwards every mapping on the container port and queue-gate runs no
 * other filter. Verified before this existed: an unauthenticated
 * GET https://queue-gate-.../internal/drops/default/state returned 200 with
 * real data, and POST /internal/drops sat beside it. Anyone could have created
 * a drop opening immediately at 200/s, taken admitted tokens from it, and — as
 * BookingProxyController forwards the body verbatim — booked against slot 1.
 * The 21:00 window would have stopped being a gate.
 *
 * The obvious fix, scoping the Route to /queue and /bookings, was tried and
 * reverted: this sandbox forbids setting spec.host, so the second Route could
 * not share a hostname, and splitting the API across two origins breaks
 * loadtest/drop.js, the console and every documented curl.
 *
 * So the discriminator is the router itself. OpenShift's HAProxy adds
 * X-Forwarded-* to everything it proxies, and a caller cannot suppress that
 * from outside — the header is added by the router, not accepted from the
 * client. A pod calling http://queue-gate:8080 over the Service network never
 * touches the router and so carries none. Presence therefore means "came from
 * outside", and that is refused.
 *
 * Note the direction it fails: a request with the header is DENIED. A spoofed
 * header can only get a caller refused, never admitted. The residual risk is an
 * in-cluster caller, which already has the Service address and needs no hole.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalGuard.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal")
                && (request.getHeader("X-Forwarded-For") != null
                    || request.getHeader("X-Forwarded-Host") != null
                    || request.getHeader("Forwarded") != null)) {
            log.warn("refused external request to {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            // 404 rather than 403: an endpoint an outside caller may not use is
            // one that, as far as they are concerned, does not exist. 403 would
            // confirm it is there and worth probing.
            response.getWriter().write("{\"error\":\"NOT_FOUND\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

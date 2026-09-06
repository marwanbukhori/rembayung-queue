package dev.marwan.console.web;

import dev.marwan.console.observability.ObservabilityProbe;
import dev.marwan.console.observability.ObservabilityStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the two observability integrations are alive, for a panel that has to
 * answer that question when the vendors' own UIs are slow, logged out, or - in
 * the case of a trial tenant - simply having a bad afternoon.
 *
 * Its own endpoint rather than part of /api/cluster: this one makes an outbound
 * call to Splunk, and a collector that has gone away must not be able to slow
 * down the panel that reports pods and quota. Same rule as everywhere else here,
 * 200 with the reason inside rather than a 500, so a failing probe renders as a
 * red row instead of blanking the page. Behind the console key; see KeyFilter.
 */
@RestController
public class ObservabilityController {

    private final ObservabilityProbe probe;

    public ObservabilityController(ObservabilityProbe probe) {
        this.probe = probe;
    }

    @GetMapping("/api/observability")
    public ObservabilityStatus observability() {
        return probe.current();
    }
}

package dev.marwan.console.observability;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityProbeTest {

    /**
     * The panel renders this string, so anything the URL carries beyond host and
     * port is on a page. HEC URLs are configured per environment and some carry
     * the token in a query string; reducing to host and port here means the
     * panel cannot leak one however the URL was written.
     */
    @Test
    void theEndpointIsReducedToHostAndPortSoNoCredentialCanReachThePage() {
        String shown = ObservabilityProbe.hostOf(
                "https://prd-p-2d10o.splunkcloud.com:8088/services/collector?token=secret-value");

        assertThat(shown).isEqualTo("prd-p-2d10o.splunkcloud.com:8088");
        assertThat(shown).doesNotContain("secret-value");
    }

    /** A URL on the default port should not grow a port that was never written. */
    @Test
    void aUrlWithNoExplicitPortShowsJustTheHost() {
        assertThat(ObservabilityProbe.hostOf("https://prd-p-2d10o.splunkcloud.com"))
                .isEqualTo("prd-p-2d10o.splunkcloud.com");
    }

    /**
     * Configuration this console cannot parse is a normal state — the value comes
     * from a Secret that may be absent or wrong — and it has to render as a word
     * rather than propagate an exception out of a status panel whose entire job
     * is to keep working when something else is broken.
     */
    @Test
    void anUnparseableUrlBecomesAWordRatherThanAnException() {
        assertThat(ObservabilityProbe.hostOf("not a url")).isEqualTo("unknown");
        assertThat(ObservabilityProbe.hostOf("")).isEqualTo("unknown");
    }

    /**
     * SPLUNK_HEC_URL is written both ways in the wild, and this cluster's Secret
     * uses the longer one. Appending blindly produced
     * /services/collector/services/collector/health, a 404, and a panel reporting
     * a healthy collector as unreachable.
     */
    @Test
    void theHealthUrlIsCorrectWhicheverWayTheHecUrlIsWritten() {
        String expected = "https://splunk.example.com:8088/services/collector/health";

        assertThat(ObservabilityProbe.healthUrl("https://splunk.example.com:8088"))
                .isEqualTo(expected);
        assertThat(ObservabilityProbe.healthUrl("https://splunk.example.com:8088/"))
                .isEqualTo(expected);
        assertThat(ObservabilityProbe.healthUrl("https://splunk.example.com:8088/services/collector"))
                .isEqualTo(expected);
        assertThat(ObservabilityProbe.healthUrl("https://splunk.example.com:8088/services/collector/"))
                .isEqualTo(expected);
    }

    /**
     * The panel shows this line. HEC answers with JSON and rendering it verbatim
     * put braces and escaped quotes where a reader wanted four words.
     */
    @Test
    void theCollectorsOwnSentenceIsShown() {
        assertThat(ObservabilityProbe.saidBy("{\"text\":\"HEC is healthy\",\"code\":17}"))
                .isEqualTo("HEC is healthy");
    }

    /** An answer in some other shape is shown as it came, which is when it matters most. */
    @Test
    void anUnrecognisedAnswerIsKeptVerbatim() {
        assertThat(ObservabilityProbe.saidBy("Service Unavailable"))
                .isEqualTo("Service Unavailable");
        assertThat(ObservabilityProbe.saidBy("")).isEmpty();
    }

    /**
     * An agent switched off on purpose must not read like one that failed. With
     * no reason configured, a namespace without the agent is reported as
     * uninstrumented; with one, every JVM says it is disabled and the panel
     * carries the reason instead.
     */
    @Test
    void aDisabledAgentSaysSoAndWhy() {
        List<Pod> pods = List.of(pod("queue-gate", "spring-boot"), pod("redis", "redis"));

        ObservabilityStatus.Dynatrace off = probe("Trial ended").dynatrace(pods);
        assertThat(off.disabled()).isEqualTo("Trial ended");
        assertThat(off.mode()).isEqualTo("disabled");
        assertThat(off.instrumented()).extracting(ObservabilityStatus.Feed::detail).containsExactly(
                "OneAgent disabled - no traces from it",
                "not a Java workload - nothing to attach to");

        ObservabilityStatus.Dynatrace on = probe("").dynatrace(pods);
        assertThat(on.disabled()).isNull();
        assertThat(on.instrumented().getFirst().detail())
                .isEqualTo("a JVM, but not instrumented - no traces from it");
    }

    /**
     * An ended trial is a decision, not an outage. With a reason configured the
     * collector is not probed at all - its host no longer resolves, and asking
     * it every fifteen seconds only produced "Could not reach the collector",
     * which reads as broken.
     */
    @Test
    void aDisabledSplunkSaysWhyAndIsNotProbed() {
        ObservabilityProbe probe = new ObservabilityProbe(null, "https://splunk.invalid:8088", "t",
                "https://abc12345.apps.dynatrace.com", "", "Trial ended 2026-09-25", true);

        ObservabilityStatus.Splunk splunk = probe.splunk(List.of(pod("queue-gate", "spring-boot")));

        assertThat(splunk.disabled()).isEqualTo("Trial ended 2026-09-25");
        assertThat(splunk.reachable()).isFalse();
        assertThat(splunk.detail()).isEqualTo("Trial ended 2026-09-25");
        assertThat(splunk.latencyMs()).isEqualTo(-1);
    }

    private static ObservabilityProbe probe(String disabledReason) {
        return new ObservabilityProbe(null, "", "", "https://abc12345.apps.dynatrace.com",
                disabledReason, "", true);
    }

    private static Pod pod(String app, String runtime) {
        return new PodBuilder()
                .withNewMetadata().withName(app + "-0")
                    .addToLabels("app", app)
                    .addToLabels("app.openshift.io/runtime", runtime)
                .endMetadata()
                .withNewSpec().addNewContainer().withName(app).endContainer().endSpec()
                .build();
    }
}

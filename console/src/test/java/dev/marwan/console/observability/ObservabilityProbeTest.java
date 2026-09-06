package dev.marwan.console.observability;

import org.junit.jupiter.api.Test;

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
}

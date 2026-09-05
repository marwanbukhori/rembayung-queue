package dev.marwan.console.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccessKeyTest {

    private final AccessKey key = new AccessKey("s3cret-demo-key");

    @Test
    void theConfiguredKeyIsAccepted() {
        assertThat(key.accepts("s3cret-demo-key")).isTrue();
    }

    @Test
    void anythingElseIsRejected() {
        assertThat(key.accepts("wrong")).isFalse();
        assertThat(key.accepts("")).isFalse();
        assertThat(key.accepts(null)).isFalse();
    }

    // Compared in constant time. A dashboard is not a high-value target, but a
    // string comparison that returns early on the first differing character
    // leaks the key's prefix to anyone willing to time the responses, and the
    // fix is one method call.
    @Test
    void comparisonDoesNotShortCircuitOnLength() {
        assertThat(key.accepts("s3cret-demo-key-longer")).isFalse();
        assertThat(key.accepts("s3")).isFalse();
    }

    /**
     * An unconfigured console is shut, not open. A deployment that forgot the
     * Secret must refuse everything rather than serve everything, because the
     * second failure is the one nobody notices.
     */
    @Test
    void anUnsetKeyAcceptsNothingAtAll() {
        assertThat(new AccessKey(null).configured()).isFalse();
        assertThat(new AccessKey("").accepts("")).isFalse();
        assertThat(new AccessKey(null).accepts("anything")).isFalse();
    }
}

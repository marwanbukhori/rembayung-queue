package dev.marwan.console.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one key that opens the console.
 *
 * There are no tiers and no accounts. The audience is two people — the person
 * who wrote this and whoever they sent the link to — so a login, a session
 * table and per-drop ownership checks would all be machinery protecting users
 * from each other who do not exist. One key, one dashboard, everything visible.
 *
 * <h2>Why the comparison is constant time</h2>
 * {@link String#equals} returns on the first differing byte, so the time a
 * rejection takes is a measurement of how much of the key the caller got right.
 * A dashboard is not a high-value target and nobody is going to sit and time
 * ten thousand requests against it — but the fix is one method call, and
 * "nobody would bother" is the reasoning behind most of the timing attacks that
 * did eventually happen. {@link MessageDigest#isEqual} compares every byte
 * whatever it finds.
 */
public final class AccessKey {

    private final byte[] expected;

    public AccessKey(String configured) {
        this.expected = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * False when CONSOLE_ACCESS_KEY was never set. The filter fails closed on
     * that, because the alternative — an unconfigured deployment serving
     * everything to everyone — is the failure nobody notices.
     */
    public boolean configured() {
        return expected.length > 0;
    }

    public boolean accepts(String presented) {
        if (!configured() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}

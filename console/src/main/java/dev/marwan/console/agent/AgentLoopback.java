package dev.marwan.console.agent;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Who the console's own agents are, when they call its MCP server.
 *
 * Not the console key. The agents feed the model text from logs and events, so
 * whatever their session may do, a crafted log line could ask for. Their session
 * therefore carries this token instead: random per process, never shown or
 * stored, and good for one thing only - raw log lines, which the agents read to
 * diagnose. Every tool that changes the system checks the console key, and this
 * is not it.
 */
@Component
public class AgentLoopback {

    public static final String HEADER = "X-Agent-Loopback";

    private final String token;

    public AgentLoopback() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.token = HexFormat.of().formatHex(bytes);
    }

    public String token() {
        return token;
    }

    public boolean matches(String presented) {
        return presented != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}

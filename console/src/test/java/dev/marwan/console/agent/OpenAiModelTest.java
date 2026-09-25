package dev.marwan.console.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiModelTest {

    HttpServer server;
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    volatile int status = 200;
    volatile String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"done\\\":true}\"}}]}";
    volatile long delayMs = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    OpenAiModel model() {
        return new OpenAiModel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "isvc-qwen3-8b-fp8");
    }

    @Test
    void sendsTheModelTheMessagesAndSettings() {
        String reply = model().chat(List.of(new Message("system", "be brief"), new Message("user", "hi")),
                Duration.ofSeconds(5));

        assertThat(reply).isEqualTo("{\"done\":true}");
        JsonNode sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("model").asString()).isEqualTo("isvc-qwen3-8b-fp8");
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(sent.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(sent.path("messages").get(1).path("content").asString()).isEqualTo("hi");
    }

    @Test
    void sendsNoCredentialUnlessGivenATokenSource() {
        model().chat(List.of(new Message("user", "hi")), Duration.ofSeconds(5));
        assertThat(auth.get()).isNull();

        new OpenAiModel("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "m", () -> "sa-token")
                .chat(List.of(new Message("user", "hi")), Duration.ofSeconds(5));
        assertThat(auth.get()).isEqualTo("Bearer sa-token");
    }

    @Test
    void aRedirectToALoginIsNotFollowed() {
        status = 302;
        answer = "";
        assertThatThrownBy(() -> model().chat(List.of(new Message("user", "hi")), Duration.ofSeconds(5)))
                .isInstanceOf(ModelUnavailable.class).hasMessageContaining("302");
    }

    @Test
    void theTokenGoesOnlyToAnInClusterHttpsModel() {
        assertThat(AgentConfiguration.mayReceiveToken(
                "https://isvc-qwen3-8b-fp8-predictor.sandbox-shared-models.svc.cluster.local:8443/v1")).isTrue();
        assertThat(AgentConfiguration.mayReceiveToken(
                "http://isvc-qwen3-8b-fp8-predictor.sandbox-shared-models.svc.cluster.local:8443/v1")).isFalse();
        assertThat(AgentConfiguration.mayReceiveToken("https://api.example.com/v1")).isFalse();
        assertThat(AgentConfiguration.mayReceiveToken("https://evil.svc.cluster.local.example.com/v1")).isFalse();
    }

    @Test
    void anErrorStatusIsModelUnavailable() {
        status = 401;
        answer = "{\"error\":\"unauthorised\"}";
        assertThatThrownBy(() -> model().chat(List.of(new Message("user", "hi")), Duration.ofSeconds(5)))
                .isInstanceOf(ModelUnavailable.class).hasMessageContaining("401");
    }

    @Test
    void aSlowModelIsModelUnavailable() {
        delayMs = 1500;
        assertThatThrownBy(() -> model().chat(List.of(new Message("user", "hi")), Duration.ofMillis(300)))
                .isInstanceOf(ModelUnavailable.class).hasMessageContaining("timed out");
    }

    @Test
    void anAnswerWithoutContentIsModelUnavailable() {
        answer = "{\"choices\":[]}";
        assertThatThrownBy(() -> model().chat(List.of(new Message("user", "hi")), Duration.ofSeconds(5)))
                .isInstanceOf(ModelUnavailable.class);
    }

    @Test
    void anUnreachableModelIsModelUnavailable() {
        OpenAiModel nowhere = new OpenAiModel("http://127.0.0.1:9/v1", "m");
        assertThatThrownBy(() -> nowhere.chat(List.of(new Message("user", "hi")), Duration.ofSeconds(2)))
                .isInstanceOf(ModelUnavailable.class);
    }
}

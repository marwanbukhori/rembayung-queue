package dev.marwan.console.agent;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An OpenAI-compatible chat endpoint - here the vLLM server the Developer
 * Sandbox runs for every namespace - called with the JDK's own HTTP client.
 *
 * No framework: a run makes one to seven calls, and each is one POST. Qwen3's
 * thinking is switched off, because its reasoning would cost seconds and
 * tokens the report never shows, and temperature is low because the job is
 * reading facts, not writing prose.
 *
 * The sandbox's shared models sit behind a login that accepts a Kubernetes
 * token, so the wiring gives this the console's own ServiceAccount token - and
 * only for an in-cluster https URL (see AgentConfiguration). Without a token
 * source nothing is sent. Redirects are never followed: a 302 to a login page
 * is an answer to report, not a place to send anything.
 */
public class OpenAiModel implements Model {

    static final Path SERVICE_CA = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final HttpClient http;
    private final java.util.function.Supplier<String> token;

    public OpenAiModel(String baseUrl, String model) {
        this(baseUrl, model, null);
    }

    public OpenAiModel(String baseUrl, String model, java.util.function.Supplier<String> token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.token = token;
        this.http = client();
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public String chat(List<Message> messages, Duration timeout) {
        String body = JSON.writeValueAsString(Map.of(
                "model", model,
                "messages", messages,
                "temperature", 0.2,
                "max_tokens", 1200,
                "chat_template_kwargs", Map.of("enable_thinking", false)));
        HttpResponse<String> response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout).header("Content-Type", "application/json");
            String bearer = token == null ? null : token.get();
            if (bearer != null && !bearer.isBlank()) {
                request.header("Authorization", "Bearer " + bearer);
            }
            response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ModelUnavailable("the model timed out after " + timeout.toSeconds() + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailable("interrupted");
        } catch (Exception e) {
            throw new ModelUnavailable("the model is unreachable: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
        if (response.statusCode() != 200) {
            throw new ModelUnavailable("the model answered HTTP " + response.statusCode());
        }
        JsonNode content;
        try {
            content = JSON.readTree(response.body()).path("choices").path(0).path("message").path("content");
        } catch (RuntimeException e) {
            throw new ModelUnavailable("the model's answer was not JSON");
        }
        if (!content.isString() || content.asString().isBlank()) {
            throw new ModelUnavailable("the model's answer had no content");
        }
        return content.asString();
    }

    /**
     * Trusts the cluster's service CA as well as the JDK's usual roots: the
     * model's in-cluster certificate is signed by one, anything public by the other.
     */
    private static HttpClient client() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (Files.exists(SERVICE_CA)) {
            try {
                builder.sslContext(withServiceCa());
            } catch (Exception e) {
                // Fall back to the default roots; a failed handshake then says why.
            }
        }
        return builder.build();
    }

    private static SSLContext withServiceCa() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream in = Files.newInputStream(SERVICE_CA)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("service-ca-" + i++, cert);
            }
        }
        X509TrustManager serviceCa = trustManager(store);
        X509TrustManager defaults = trustManager(null);
        X509TrustManager either = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                defaults.checkClientTrusted(chain, auth);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    serviceCa.checkServerTrusted(chain, auth);
                } catch (CertificateException e) {
                    defaults.checkServerTrusted(chain, auth);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return defaults.getAcceptedIssuers();
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] {either}, null);
        return ssl;
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("no X509 trust manager");
    }
}

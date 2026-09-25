package dev.marwan.console.metrics;

import dev.marwan.console.cluster.KubernetesAccess;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Range queries against the Thanos tenancy port, as this namespace.
 *
 * The tenancy port scopes every query to the `namespace` parameter and
 * authorises it by asking whether the caller may get pods.metrics.k8s.io there,
 * so the console sees its own namespace and nothing else.
 */
@Component
class ThanosTenancy implements RangeQuery {

    static final String URL = "https://thanos-querier.openshift-monitoring.svc:9092/api/v1/query_range";
    static final Path TOKEN = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    static final Path SERVICE_CA = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt");
    static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final KubernetesAccess kubernetes;
    private volatile HttpClient http;

    ThanosTenancy(KubernetesAccess kubernetes) {
        this.kubernetes = kubernetes;
    }

    @Override
    public List<Series> range(String promql, String labelKey, Instant start, Instant end, Duration step)
            throws Exception {
        if (!Files.exists(TOKEN)) {
            throw new IllegalStateException("Prometheus is reachable only from inside the cluster");
        }
        // Read per request: the projected service-account token rotates.
        String token = Files.readString(TOKEN).trim();
        String query = "namespace=" + enc(kubernetes.namespace())
                + "&query=" + enc(promql)
                + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&step=" + step.toSeconds();
        HttpResponse<String> response = client().send(HttpRequest.newBuilder(URI.create(URL + "?" + query))
                        .timeout(TIMEOUT).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Prometheus answered HTTP " + response.statusCode());
        }
        return PromJson.matrix(response.body(), labelKey);
    }

    /** Trusts the cluster's service CA, which signs the in-cluster thanos-querier certificate. */
    private HttpClient client() throws Exception {
        HttpClient existing = http;
        if (existing != null) {
            return existing;
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(SERVICE_CA)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("service-ca-" + i++, cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        http = HttpClient.newBuilder().connectTimeout(TIMEOUT).sslContext(ssl).build();
        return http;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

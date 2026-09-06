package dev.marwan.console.observability;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.fabric8.kubernetes.api.model.OwnerReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks what Splunk and Dynatrace are actually doing, from the two vantage
 * points this console has: the collector's own health endpoint, and the specs of
 * the pods running beside it.
 *
 * <h2>Why pods and not deployments</h2>
 * The console's Role grants pods, resourcequotas, HPAs, services, routes, events
 * and jobs — not deployments and not secrets. Everything below is therefore read
 * from a Pod, which is in any case the more honest source: a Deployment says what
 * was asked for, a running Pod says what is true.
 *
 * <h2>Why a Ready pod proves the OneAgent loaded</h2>
 * The agent is not injected by a webhook here; the deployment sets
 * {@code JAVA_TOOL_OPTIONS=-agentpath:...liboneagentloader.so} and an init
 * container downloads the library into a shared emptyDir. Neither half is
 * conditional. If the Dynatrace secret were missing the init container exits 1;
 * if the download were wrong the JVM refuses to start on a bad {@code -agentpath}.
 * So a pod that carries that flag and has reached Ready cannot have got there
 * without the agent — which is why this reports instrumentation from the pod
 * spec rather than claiming it from the deployment manifest.
 *
 * <h2>What it deliberately does not do</h2>
 * It does not show logs or traces. The cluster holds a write-only HEC token and
 * an install-only PaaS token; reading either vendor back needs credentials that
 * do not exist here. Saying "the collector answers and these four JVMs feed it"
 * is the strongest true statement available, and it is the one that distinguishes
 * a quiet pipeline from a broken one.
 */
@Component
public class ObservabilityProbe {

    /** Long enough that a page polling every few seconds does not probe Splunk every time. */
    private static final Duration TTL = Duration.ofSeconds(15);

    /** Short: a slow collector must not hold a request thread on the console. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(4);

    private static final String AGENT_MARKER = "liboneagentloader.so";
    private static final String SPLUNK_ENV = "SPLUNK_HEC_URL";

    private final KubernetesAccess kube;
    private final String hecUrl;
    private final String hecToken;
    private final String dynatraceTenant;
    private final boolean trustSelfSigned;

    private volatile Snapshot cache;

    private record Snapshot(ObservabilityStatus status, Instant takenAt) { }

    public ObservabilityProbe(KubernetesAccess kube,
                              @Value("${SPLUNK_HEC_URL:}") String hecUrl,
                              @Value("${SPLUNK_HEC_TOKEN:}") String hecToken,
                              @Value("${console.dynatrace-tenant:https://icp44821.apps.dynatrace.com}")
                              String dynatraceTenant,
                              @Value("${console.splunk.trust-self-signed:true}")
                              boolean trustSelfSigned) {
        this.kube = kube;
        this.hecUrl = hecUrl == null ? "" : hecUrl.trim();
        this.hecToken = hecToken == null ? "" : hecToken.trim();
        this.dynatraceTenant = dynatraceTenant;
        this.trustSelfSigned = trustSelfSigned;
    }

    public ObservabilityStatus current() {
        Snapshot held = cache;
        Instant now = Instant.now();
        if (held != null && held.takenAt().plus(TTL).isAfter(now)) {
            return held.status();
        }
        ObservabilityStatus fresh = probe(now);
        cache = new Snapshot(fresh, now);
        return fresh;
    }

    private ObservabilityStatus probe(Instant now) {
        List<Pod> pods = readPods();
        return new ObservabilityStatus(splunk(pods), dynatrace(pods), now);
    }

    private List<Pod> readPods() {
        try {
            return kube.client().pods().inNamespace(kube.namespace()).list().getItems();
        } catch (RuntimeException e) {
            // Off-cluster, or the API server is unreachable. Both are normal states
            // for this console and neither is worth a 500 - the caller renders an
            // empty feed list beside a collector reading that still works.
            kube.invalidate();
            return List.of();
        }
    }

    // ---------------------------------------------------------------- Splunk

    private ObservabilityStatus.Splunk splunk(List<Pod> pods) {
        List<ObservabilityStatus.Feed> shippers = feedsFrom(pods, (pod, container) -> {
            boolean wired = env(container, SPLUNK_ENV) != null;
            return new ObservabilityStatus.Feed(workloadOf(pod), wired,
                    wired ? "ships logs over HEC" : "no " + SPLUNK_ENV + " in the container");
        });

        if (hecUrl.isEmpty()) {
            return new ObservabilityStatus.Splunk("not configured", false,
                    "This console has no " + SPLUNK_ENV + ", so it cannot reach the collector.",
                    shippers);
        }

        String endpoint = hostOf(hecUrl);
        try {
            Reply reply = askCollector(healthUrl(hecUrl), trustSelfSigned);
            boolean healthy = reply.status() == 200;
            return new ObservabilityStatus.Splunk(endpoint, healthy,
                    healthy ? "The collector answered: " + oneLine(reply.body())
                            : "The collector answered HTTP " + reply.status() + ".",
                    shippers);
        } catch (Exception e) {
            return new ObservabilityStatus.Splunk(endpoint, false,
                    "Could not reach the collector: " + KubernetesAccess.summarise(e), shippers);
        }
    }

    private record Reply(int status, String body) { }

    // ------------------------------------------------------------- Dynatrace

    private ObservabilityStatus.Dynatrace dynatrace(List<Pod> pods) {
        List<ObservabilityStatus.Feed> instrumented = feedsFrom(pods, (pod, container) -> {
            String options = env(container, "JAVA_TOOL_OPTIONS");
            boolean injected = options != null && options.contains(AGENT_MARKER);
            if (!injected) {
                // A JVM without the agent and a workload that is not a JVM are
                // different facts and only one of them is a gap. redis will never
                // carry a Java agent; the console is a Spring Boot service that
                // simply is not instrumented, which is a choice worth seeing.
                // app.openshift.io/runtime is already set on every Deployment
                // here, so this is read rather than guessed from the image name.
                return new ObservabilityStatus.Feed(workloadOf(pod), false,
                        isJvm(pod) ? "a JVM, but not instrumented - no traces from it"
                                   : "not a Java workload - nothing to attach to");
            }
            // Ready is the half that makes this a measurement rather than a claim:
            // the JVM will not reach it if the agent library is missing or wrong.
            boolean ready = isReady(pod);
            return new ObservabilityStatus.Feed(workloadOf(pod), ready,
                    ready ? "OneAgent loaded into the JVM"
                          : "OneAgent configured, pod not Ready yet");
        });

        return new ObservabilityStatus.Dynatrace(hostOf(dynatraceTenant),
                "application-only OneAgent",
                instrumented,
                "Traces and the service map. This flavour ships no logs at all, "
                        + "which is why log searches in Dynatrace are empty by design.");
    }

    // ----------------------------------------------------------------- Plumbing

    /** Reads one feed per workload, keeping the first pod of each so a scaled-up
     *  deployment appears once rather than once per replica. */
    private List<ObservabilityStatus.Feed> feedsFrom(List<Pod> pods, FeedReader reader) {
        Map<String, ObservabilityStatus.Feed> byWorkload = new LinkedHashMap<>();
        for (Pod pod : pods) {
            List<Container> containers = pod.getSpec() == null
                    ? List.of() : pod.getSpec().getContainers();
            if (containers.isEmpty()) {
                continue;
            }
            // Job pods - load runs and the keepalive CronJob - come and go every
            // few minutes. They are not part of the system being observed, and
            // listing them churned this panel on every poll. Read from
            // ownerReferences rather than the name, because a Job's pod name is
            // only conventionally related to the Job that made it.
            if (ownedByJob(pod)) {
                continue;
            }
            // The BEST pod for the workload, not the first one seen.
            //
            // queue-gate runs three replicas and the HPA adds more under load, so
            // the first pod in an arbitrary list order is regularly one that is
            // still starting - and reporting the whole workload as uninstrumented
            // because one replica has not finished booting is exactly the false
            // negative this panel exists to avoid. It reported precisely that on
            // its first deploy, against three pods that were all Ready.
            byWorkload.merge(workloadOf(pod), reader.read(pod, containers.getFirst()),
                    (existing, candidate) -> existing.on() ? existing : candidate);
        }
        List<ObservabilityStatus.Feed> feeds = new ArrayList<>(byWorkload.values());
        feeds.sort((a, b) -> a.service().compareTo(b.service()));
        return feeds;
    }

    @FunctionalInterface
    private interface FeedReader {
        ObservabilityStatus.Feed read(Pod pod, Container container);
    }

    private static String workloadOf(Pod pod) {
        Map<String, String> labels = pod.getMetadata() == null ? null : pod.getMetadata().getLabels();
        if (labels != null && labels.get("app") != null) {
            return labels.get("app");
        }
        return pod.getMetadata() == null ? "unknown" : pod.getMetadata().getName();
    }

    private static boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        for (PodCondition condition : pod.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return "True".equals(condition.getStatus());
            }
        }
        return false;
    }

    private static String env(Container container, String name) {
        if (container.getEnv() == null) {
            return null;
        }
        for (EnvVar var : container.getEnv()) {
            if (name.equals(var.getName())) {
                // A secretKeyRef has no inline value. Its presence is still the
                // answer to "is this wired", so the reference reads as configured
                // rather than as absent - and the secret is never read.
                return var.getValue() != null ? var.getValue() : "<from secret>";
            }
        }
        return null;
    }

    /** Host and port only. The URL may carry a token in some deployments and a
     *  status panel is exactly the wrong place to render one. */
    static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "unknown";
            }
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * The collector's health endpoint, from whatever shape the HEC URL is written in.
     *
     * SPLUNK_HEC_URL is the URL the log appender POSTs events to, and Splunk's own
     * documentation writes it both ways: some deployments set the bare host and
     * port, others include the /services/collector path. Appending blindly built
     * .../services/collector/services/collector/health against the second form,
     * which the collector answered with a 404 - and a 404 rendered as "the
     * collector is not reachable", the panel's own false negative for the second
     * time in one afternoon.
     */
    static String healthUrl(String hecUrl) {
        String base = hecUrl.endsWith("/") ? hecUrl.substring(0, hecUrl.length() - 1) : hecUrl;
        if (base.endsWith("/services/collector")) {
            base = base.substring(0, base.length() - "/services/collector".length());
        }
        return base + "/services/collector/health";
    }

    private static String oneLine(String body) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 117) + "...";
    }

    /**
     * A client that does not verify the collector's certificate, when and only
     * when {@code console.splunk.trust-self-signed} says so. Set it to false and
     * this returns an ordinary verifying client, which is what a real Splunk
     * stack must run with; it defaults to true because the alternative here is
     * worse than the risk. See below.
     *
     * The same decision, for the same measured reason, as the logback appender
     * that ships the logs: Splunk Cloud trial stacks serve a self-signed
     * certificate on the HEC port, so a verifying client cannot complete the
     * request at all and this panel would report a healthy collector as
     * unreachable. The scope is deliberately one request to one known host that
     * returns a fixed health document, and the token is not sent — so there is
     * no credential to intercept and no response body that is trusted for
     * anything beyond being displayed, truncated and HTML-escaped by Angular.
     *
     * Defaulting this to false would be the reflex, and it would be wrong here:
     * a verifying client cannot complete the handshake against a self-signed
     * trial certificate, so the panel would report a healthy collector as
     * unreachable. A status panel whose whole purpose is to tell "working" from
     * "broken" must not be the thing that invents a false negative.
     *
     * On a real Splunk stack this becomes a default client with the stack's CA in
     * the container truststore. It is here because a trial stack has no CA to add.
     */
    private static boolean isJvm(Pod pod) {
        Map<String, String> labels = pod.getMetadata() == null ? null : pod.getMetadata().getLabels();
        return labels != null && "spring-boot".equals(labels.get("app.openshift.io/runtime"));
    }

    private static boolean ownedByJob(Pod pod) {
        if (pod.getMetadata() == null || pod.getMetadata().getOwnerReferences() == null) {
            return false;
        }
        for (OwnerReference owner : pod.getMetadata().getOwnerReferences()) {
            if ("Job".equals(owner.getKind())) {
                return true;
            }
        }
        return false;
    }

    /**
     * One GET to the collector's health endpoint, over a connection that does not
     * check the certificate when {@code console.splunk.trust-self-signed} says so.
     *
     * <h2>Why HttpsURLConnection and not the modern HttpClient</h2>
     * Because the modern one cannot express this safely. A trust-all
     * TrustManager stops the chain being validated but java.net.http verifies the
     * HOSTNAME separately, and honours neither the TrustManager nor
     * SSLParameters.setEndpointIdentificationAlgorithm(null) for it - both were
     * measured against this collector and both still failed on "No name matching
     * prd-p-2d10o.splunkcloud.com found". The only lever java.net.http offers is
     * the jdk.internal.httpclient.disableHostnameVerification system property,
     * which is JVM-wide: it would switch hostname verification off for every
     * other HTTPS client in this console to satisfy one status probe.
     *
     * HttpsURLConnection takes a HostnameVerifier per connection. Measured: this
     * request succeeds while a second connection built without the verifier is
     * still refused by the same JVM, so the relaxation genuinely stops here.
     *
     * <h2>Why relax it at all</h2>
     * Splunk Cloud trial stacks serve Splunk's own default certificate,
     * CN=SplunkServerDefaultCert, which matches no hostname by construction. A
     * verifying client therefore cannot complete the handshake, and this panel
     * would report a healthy collector as unreachable - a status panel inventing
     * a false negative, which is worse than not having one.
     *
     * The exposure: no token is sent, one fixed health document is read, and it
     * is truncated and rendered through Angular interpolation. A forged reply can
     * change what this panel claims and nothing else. On a real Splunk stack this
     * property goes to false and the stack's CA goes in the container truststore.
     */
    private static Reply askCollector(String url, boolean trustSelfSigned) throws Exception {
        HttpsURLConnection connection =
                (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout((int) HTTP_TIMEOUT.toMillis());
        connection.setReadTimeout((int) HTTP_TIMEOUT.toMillis());
        if (trustSelfSigned) {
            connection.setSSLSocketFactory(permissiveContext().getSocketFactory());
            connection.setHostnameVerifier((host, session) -> true);
        }
        try {
            int status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) {
                return new Reply(status, "");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String first = reader.readLine();
                return new Reply(status, first == null ? "" : first);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static SSLContext permissiveContext() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new java.security.SecureRandom());
        return context;
    }
}

# Step 3: Charts Strip — Prometheus History with Live Readings from the Pods — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four live charts at the top of the platform column — requests/s, latency p95, DB pool in use, replicas — each drawing 15 minutes of history from Prometheus and a "now" reading taken straight from the pods.

**Architecture:** A new `dev.marwan.console.metrics` package. `PromJson` and `PromText` are pure parsers (Prometheus query JSON; Prometheus text exposition). `ThanosTenancy` runs fixed PromQL range queries against the tenancy port with the console's service-account token. `PodReadings` reads `/actuator/prometheus` on each pod's management port for the "now" values. `MetricsService` joins the two per chart, caches, and isolates failures; `MetricsController` serves `GET /api/metrics/{chart}`. The Angular `ChartsStrip` draws four small multiples as inline SVG with a crosshair tooltip, direct labels and a table view.

**Tech Stack:** Java 25, Spring Boot 4.1, JDK `HttpClient`, Jackson 3, JUnit 5 + AssertJ; Angular signals, inline SVG (no chart library).

**Spec:** [`docs/superpowers/specs/2026-09-25-cluster-inspector-and-run-agent-design.md`](../specs/2026-09-25-cluster-inspector-and-run-agent-design.md) §5.2 (charts strip), §6.3 (fixed queries), §12.1 (placement), plus the 2026-09-25 decision recorded as §12.5 in Task 1.

## Verified on 2026-09-25 (in-cluster, console service account, after Task 0's RBAC and policy)

- The tenancy port answers: `count by (job) (up)` → `booking-service` 2, `console` 1, `queue-gate` 2.
- `hikaricp_connections_active` has one series per booking-service `pod`.
- `http_server_requests_seconds_count` exists for all three jobs, with a `status` label.
- `http_server_requests_seconds_bucket` does **not** exist yet — histograms are off (Task 1 turns them on).
- `kube_horizontalpodautoscaler_status_current_replicas` exists with a `horizontalpodautoscaler` label.
- A direct read of `queue-gate:9090/actuator/prometheus` works from a console-labelled pod. The same read of booking-service returned nothing from an `oc debug` copy; the real console pod is checked in Task 7.
- Already committed on branch `metrics` (5b48807) and applied by hand: booking-service's NetworkPolicy admits the user-workload monitoring namespace and `app=console` on 9090; the console Role has `get` on `pods.metrics.k8s.io`.

## Global Constraints

- Only the four named charts; any other name is 404 with `{"error":"NOT_FOUND"}`. No PromQL from the browser, ever.
- History from Prometheus is cached 10 seconds server-side (it scrapes every 15); "now" readings 2 seconds. Viewers never multiply upstream calls.
- Each source fails alone: Prometheus down → the chart keeps its live reading and says history is unavailable; pods unreadable → the chart keeps its history and says the live reading is unavailable. Never a 500.
- The window is 5–60 minutes, default 15, clamped server-side; step 15 seconds.
- Chart colours: categorical slots 1–4 of the reference palette in fixed order (`#2a78d6`, `#eb6834`, `#1baf7a`, `#eda100`), validated light (the site has no dark mode). Slots 3 and 4 are below 3:1 on white, so every line is direct-labelled at its end and each chart has a table view.
- One y-axis per chart, starting at 0; 2px lines; recessive grid; text in ink tokens, never series colours.
- Commit messages: plain sentences, no AI attribution, no `Co-Authored-By` / `Generated with` trailers.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` for Java builds.

## Review Focus

1. **Histograms not on yet, or a service with no traffic.** Latency returns no series. Expected: the chart says "No latency data yet — p95 needs requests with histograms on", not an empty axis or NaN. Pinned in Task 4.
2. **A pod appears or disappears mid-window** (HPA scale-out during a rush). Expected: its series starts or stops where it did; the live reading lists the pods that exist now. Pinned in Task 2 (gaps parse) and Task 4.
3. **The service-account token rotates** (projected tokens refresh hourly). Expected: the next query reads the file again; no 401 storm. Pinned in Task 3 (token read per request).
4. **Counters reset** (a pod restarts, `_count` drops). Expected: the live requests/s never goes negative — a reset reads as "restarting", not −300/s. Pinned in Task 3.
5. **`NaN` / `+Inf` in Prometheus output** (histogram_quantile on empty buckets). Expected: points dropped, not drawn at 0 or thrown. Pinned in Task 2.

---

## File Structure

**Config:** `queue-gate/src/main/resources/application.yml`, `booking-service/src/main/resources/application.yml` (histograms).

**Backend, `console/src/main/java/dev/marwan/console/metrics/`:**

| File | Responsibility |
|---|---|
| `ChartName.java` | The four charts, their PromQL, series label, unit |
| `Series.java`, `Reading.java`, `Chart.java` | Response records |
| `PromJson.java` | Parse a `query_range` matrix into `Series` (pure) |
| `PromText.java` | Parse `/actuator/prometheus` text into samples (pure) |
| `RangeQuery.java` / `ThanosTenancy.java` | Run a range query against the tenancy port |
| `PodReadings.java` | "Now" values straight from the pods |
| `MetricsService.java` | Join, cache, isolate failures |
| `MetricsController.java` | `GET /api/metrics/{chart}` |

**Tests, `console/src/test/java/dev/marwan/console/metrics/`:** `PromJsonTest`, `PromTextTest`, `PodReadingsTest`, `MetricsServiceTest`, `MetricsControllerTest`.

**UI, `console/ui/src/app/`:** `state.ts` (types), `metrics.service.ts`, `charts-strip.ts`, `visitor.ts` (placement).

---

### Task 1: Latency histograms on, and the decision recorded

**Files:** both services' `application.yml`; the spec.

- [ ] **Step 1: Turn histograms on.** In each service's `application.yml`, under the existing `management:` block, add as a sibling of `endpoint:`:

```yaml
  metrics:
    distribution:
      # Buckets for http.server.requests, so Prometheus can compute p95 across
      # pods with histogram_quantile. Without them the latency chart has
      # nothing to draw: on 2026-09-25 no _bucket series existed at all.
      percentiles-histogram:
        "[http.server.requests]": true
```

- [ ] **Step 2: Prove the key binds.** Run each service's own test suite, which boots the context:
`cd queue-gate && ./mvnw -q test` and `cd booking-service && ./mvnw -q test`
Expected: both exit 0 (booking-service's tests use Testcontainers Oracle; if Docker is not running, record a ruling and rely on Task 7's in-cluster check).

- [ ] **Step 3: Record the decision in the spec** — append §12.5:

```markdown
### 12.5 Charts read two sources (supersedes §6.3's single source)

History comes from Prometheus through the tenancy port, verified working on
2026-09-25 once the console could get `pods.metrics.k8s.io` and booking-service's
NetworkPolicy admitted the monitoring namespace on 9090 - before that change
booking-service had never been scraped. Each chart also shows a "now" reading
taken straight from the pods' `/actuator/prometheus` every two seconds, fresher
than the 15-second scrape. Either source can fail alone; the chart says which.
Latency has no live reading: a quantile needs Prometheus's windowed histogram.
```

- [ ] **Step 4: Commit**

```bash
git add queue-gate/src/main/resources/application.yml booking-service/src/main/resources/application.yml docs/superpowers/specs/2026-09-25-cluster-inspector-and-run-agent-design.md
git commit -m "Publish latency histograms, and record that the charts read Prometheus and the pods"
```

---

### Task 2: Parsing Prometheus query results and exposition text

**Files:** Create `Series.java`, `PromJson.java`, `PromText.java`; tests `PromJsonTest.java`, `PromTextTest.java`.

**Interfaces:**
- Produces:
  - `record Series(String label, List<double[]> points)` — points are `[epochSeconds, value]`
  - `final class PromJson { static List<Series> matrix(String json, String labelKey) }` — throws `IllegalStateException` when `status` is not `success`
  - `record PromText.Sample(Map<String, String> labels, double value)`; `static List<Sample> samples(String body, String metric)`

- [ ] **Step 1: Failing tests**

`PromJsonTest.java`:

```java
package dev.marwan.console.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromJsonTest {

    @Test
    void aMatrixBecomesOneSeriesPerLabelValue() {
        String json = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"pod":"booking-a"},"values":[[1790342100,"1"],[1790342115,"3"]]},
              {"metric":{"pod":"booking-b"},"values":[[1790342115,"5"]]}]}}""";

        List<Series> series = PromJson.matrix(json, "pod");

        assertThat(series).extracting(Series::label).containsExactly("booking-a", "booking-b");
        assertThat(series.getFirst().points()).hasSize(2);
        assertThat(series.getFirst().points().get(1)).containsExactly(1790342115, 3);
    }

    /** Review focus 5: histogram_quantile over empty buckets yields NaN; it is dropped, not drawn at 0. */
    @Test
    void notANumberAndInfinityAreDropped() {
        String json = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"job":"queue-gate"},"values":[[1,"NaN"],[2,"+Inf"],[3,"0.25"]]}]}}""";

        assertThat(PromJson.matrix(json, "job").getFirst().points()).hasSize(1);
    }

    @Test
    void aSeriesWithNoUsablePointsIsLeftOut() {
        String json = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"job":"queue-gate"},"values":[[1,"NaN"]]}]}}""";

        assertThat(PromJson.matrix(json, "job")).isEmpty();
    }

    @Test
    void anErrorAnswerIsAnException() {
        assertThatThrownBy(() -> PromJson.matrix("{\"status\":\"error\",\"error\":\"bad query\"}", "job"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad query");
    }
}
```

`PromTextTest.java`:

```java
package dev.marwan.console.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromTextTest {

    private static final String BODY = """
        # HELP hikaricp_connections_active Active connections
        # TYPE hikaricp_connections_active gauge
        hikaricp_connections_active{application="booking-service",pool="HikariPool-1"} 3.0
        hikaricp_connections_active_extra{pool="x"} 9.0
        http_server_requests_seconds_count{method="GET",status="200",uri="/queue/{token}"} 721
        http_server_requests_seconds_count{method="POST",status="503",uri="/bookings"} 4
        jvm_threads_live_threads 42.0
        """;

    @Test
    void samplesOfOneMetricWithTheirLabels() {
        var samples = PromText.samples(BODY, "http_server_requests_seconds_count");

        assertThat(samples).hasSize(2);
        assertThat(samples.getFirst().labels()).containsEntry("status", "200").containsEntry("uri", "/queue/{token}");
        assertThat(samples.getFirst().value()).isEqualTo(721);
    }

    @Test
    void aMetricNameIsMatchedExactlyNotAsAPrefix() {
        assertThat(PromText.samples(BODY, "hikaricp_connections_active")).hasSize(1)
                .first().extracting(PromText.Sample::value).isEqualTo(3.0);
    }

    @Test
    void aMetricWithoutLabelsParses() {
        assertThat(PromText.samples(BODY, "jvm_threads_live_threads")).first()
                .extracting(PromText.Sample::value).isEqualTo(42.0);
    }
}
```

- [ ] **Step 2: Run, see them fail** — `cd console && ./mvnw -q test -Dtest='PromJsonTest,PromTextTest'` → compilation failure (`Series`, `PromJson`, `PromText` missing).

- [ ] **Step 3: Implement**

`Series.java`:

```java
package dev.marwan.console.metrics;

import java.util.List;

/**
 * One line on a chart.
 *
 * @param points [epochSeconds, value] pairs, oldest first, with gaps where Prometheus had none
 */
public record Series(String label, List<double[]> points) { }
```

`PromJson.java`:

```java
package dev.marwan.console.metrics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A Prometheus query_range answer into chart series. */
final class PromJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PromJson() {
    }

    @SuppressWarnings("unchecked")
    static List<Series> matrix(String body, String labelKey) {
        Map<String, Object> root = JSON.readValue(body, Map.class);
        if (!"success".equals(root.get("status"))) {
            throw new IllegalStateException("Prometheus answered: " + root.get("error"));
        }
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        List<Map<String, Object>> result = data == null ? List.of()
                : (List<Map<String, Object>>) data.getOrDefault("result", List.of());
        List<Series> out = new ArrayList<>();
        for (Map<String, Object> row : result) {
            Map<String, Object> metric = (Map<String, Object>) row.getOrDefault("metric", Map.of());
            Object label = metric.get(labelKey);
            List<List<Object>> values = (List<List<Object>>) row.getOrDefault("values", List.of());
            List<double[]> points = new ArrayList<>();
            for (List<Object> v : values) {
                double t = ((Number) v.get(0)).doubleValue();
                double y;
                try {
                    y = Double.parseDouble(String.valueOf(v.get(1)));
                } catch (NumberFormatException e) {
                    continue;
                }
                // Review focus 5: histogram_quantile yields NaN on empty buckets.
                if (Double.isFinite(y)) {
                    points.add(new double[]{t, y});
                }
            }
            if (!points.isEmpty()) {
                out.add(new Series(label == null ? "all" : String.valueOf(label), points));
            }
        }
        out.sort((a, b) -> a.label().compareTo(b.label()));
        return out;
    }
}
```

(Remove the unused `JsonNode` import if the compiler warns.)

`PromText.java`:

```java
package dev.marwan.console.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Prometheus text exposition an /actuator/prometheus endpoint serves. */
final class PromText {

    record Sample(Map<String, String> labels, double value) { }

    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private PromText() {
    }

    static List<Sample> samples(String body, String metric) {
        List<Sample> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String name;
            String labels = "";
            String rest;
            int brace = line.indexOf('{');
            int space = line.indexOf(' ');
            if (brace >= 0 && (space < 0 || brace < space)) {
                name = line.substring(0, brace);
                int close = line.lastIndexOf('}');
                labels = line.substring(brace + 1, close);
                rest = line.substring(close + 1).trim();
            } else {
                name = space < 0 ? line : line.substring(0, space);
                rest = space < 0 ? "" : line.substring(space + 1).trim();
            }
            if (!name.equals(metric)) {
                continue;
            }
            String[] parts = rest.split("\\s+");
            double value;
            try {
                value = Double.parseDouble(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            Matcher m = LABEL.matcher(labels);
            while (m.find()) {
                map.put(m.group(1), m.group(2));
            }
            out.add(new Sample(map, value));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run, see them pass** — same command → exit 0, 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/metrics console/src/test/java/dev/marwan/console/metrics
git commit -m "Parse Prometheus query results and exposition text, dropping NaN rather than drawing it"
```

---

### Task 3: The two sources

**Files:** Create `ChartName.java`, `Reading.java`, `RangeQuery.java`, `ThanosTenancy.java`, `PodReadings.java`; test `PodReadingsTest.java`.

**Interfaces:**
- Produces:
  - `enum ChartName { REQUESTS, LATENCY, POOL, REPLICAS; String path(); String promql(); String labelKey(); String unit(); static Optional<ChartName> parse(String) }`
  - `record Reading(String label, double value)`
  - `interface RangeQuery { List<Series> range(String promql, String labelKey, Instant start, Instant end, Duration step); }`
  - `@Component class ThanosTenancy implements RangeQuery`
  - `@Component class PodReadings { PodReadings(ObjectSource, PodReadings.Fetch, Clock); List<Reading> now(ChartName) }` with `@FunctionalInterface interface Fetch { String get(String url) throws Exception; }`

- [ ] **Step 1: Failing tests** — `PodReadingsTest.java`:

```java
package dev.marwan.console.metrics;

import dev.marwan.console.objects.ObjectSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PodReadingsTest {

    private final ObjectSource source = mock(ObjectSource.class);
    private final Map<String, String> bodies = new HashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final PodReadings readings = new PodReadings(source, url -> {
        String body = bodies.get(url);
        if (body == null) {
            throw new java.io.IOException("connection refused");
        }
        return body;
    }, clock);

    @Test
    void poolIsReadFromEachBookingPod() {
        given(source.pods("booking-service")).willReturn(List.of(pod("booking-a", "10.0.0.1"), pod("booking-b", "10.0.0.2")));
        bodies.put("http://10.0.0.1:9090/actuator/prometheus", "hikaricp_connections_active{pool=\"HikariPool-1\"} 5.0\n");
        bodies.put("http://10.0.0.2:9090/actuator/prometheus", "hikaricp_connections_active{pool=\"HikariPool-1\"} 0.0\n");

        assertThat(readings.now(ChartName.POOL)).extracting(Reading::label, Reading::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("booking-a", 5.0),
                        org.assertj.core.groups.Tuple.tuple("booking-b", 0.0));
    }

    @Test
    void aPodThatCannotBeReadIsLeftOutNotFatal() {
        given(source.pods("booking-service")).willReturn(List.of(pod("booking-a", "10.0.0.1"), pod("booking-b", "10.0.0.2")));
        bodies.put("http://10.0.0.1:9090/actuator/prometheus", "hikaricp_connections_active{pool=\"HikariPool-1\"} 2.0\n");

        assertThat(readings.now(ChartName.POOL)).extracting(Reading::label).containsExactly("booking-a");
    }

    @Test
    void requestsPerSecondComeFromTheCounterDeltaBetweenReads() {
        given(source.pods("queue-gate")).willReturn(List.of(pod("gate-a", "10.0.0.9")));
        String url = "http://10.0.0.9:9090/actuator/prometheus";
        bodies.put(url, counter(200, 100) + counter(503, 10));
        assertThat(readings.now(ChartName.REQUESTS)).isEmpty();   // the first read has nothing to compare with

        now.set(now.get().plusSeconds(10));
        bodies.put(url, counter(200, 150) + counter(503, 30));
        assertThat(readings.now(ChartName.REQUESTS)).extracting(Reading::label, Reading::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2xx", 5.0),
                        org.assertj.core.groups.Tuple.tuple("5xx", 2.0));
    }

    /** Review focus 4: a restarted pod's counter drops; that is not negative traffic. */
    @Test
    void aCounterResetIsNeverNegative() {
        given(source.pods("queue-gate")).willReturn(List.of(pod("gate-a", "10.0.0.9")));
        String url = "http://10.0.0.9:9090/actuator/prometheus";
        bodies.put(url, counter(200, 500));
        readings.now(ChartName.REQUESTS);

        now.set(now.get().plusSeconds(10));
        bodies.put(url, counter(200, 20));
        assertThat(readings.now(ChartName.REQUESTS)).allSatisfy(r -> assertThat(r.value()).isGreaterThanOrEqualTo(0));
    }

    @Test
    void replicasComeFromTheAutoscalers() {
        given(source.hpa("queue-gate")).willReturn(Optional.of(new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("queue-gate").endMetadata()
                .withNewSpec().withMaxReplicas(10).endSpec()
                .withNewStatus().withCurrentReplicas(6).endStatus().build()));
        given(source.hpa("booking-service")).willReturn(Optional.empty());

        assertThat(readings.now(ChartName.REPLICAS)).extracting(Reading::label, Reading::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("queue-gate", 6.0));
    }

    @Test
    void latencyHasNoLiveReading() {
        assertThat(readings.now(ChartName.LATENCY)).isEmpty();
    }

    private static String counter(int status, double value) {
        return "http_server_requests_seconds_count{method=\"GET\",status=\"" + status + "\",uri=\"/queue/{token}\"} " + value + "\n";
    }

    private static Pod pod(String name, String ip) {
        return new PodBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewStatus().withPodIP(ip).withPhase("Running").endStatus().build();
    }
}
```

- [ ] **Step 2: Run, see it fail** — `cd console && ./mvnw -q test -Dtest=PodReadingsTest` → compilation failure.

- [ ] **Step 3: Implement**

`ChartName.java`:

```java
package dev.marwan.console.metrics;

import java.util.Locale;
import java.util.Optional;

/**
 * The four charts, each with the one PromQL query it may run. A closed set:
 * the name comes from a public URL, and nothing a caller sends is ever
 * interpolated into a query.
 */
public enum ChartName {
    REQUESTS("sum by (code) (label_replace(rate(http_server_requests_seconds_count"
            + "{job=\"queue-gate\",uri!~\"/actuator.*\"}[1m]), \"code\", \"${1}xx\", \"status\", \"(.)..\"))",
            "code", "req/s"),
    LATENCY("histogram_quantile(0.95, sum by (le, job) (rate(http_server_requests_seconds_bucket"
            + "{job=~\"queue-gate|booking-service\",uri!~\"/actuator.*\"}[1m])))",
            "job", "s"),
    POOL("max by (pod) (hikaricp_connections_active{job=\"booking-service\"})", "pod", "connections"),
    REPLICAS("max by (horizontalpodautoscaler) (kube_horizontalpodautoscaler_status_current_replicas)",
            "horizontalpodautoscaler", "pods");

    private final String promql;
    private final String labelKey;
    private final String unit;

    ChartName(String promql, String labelKey, String unit) {
        this.promql = promql;
        this.labelKey = labelKey;
        this.unit = unit;
    }

    public String path() { return name().toLowerCase(Locale.ROOT); }
    public String promql() { return promql; }
    public String labelKey() { return labelKey; }
    public String unit() { return unit; }

    public static Optional<ChartName> parse(String value) {
        for (ChartName c : values()) {
            if (c.path().equals(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
```

`Reading.java`:

```java
package dev.marwan.console.metrics;

/** One value right now, read straight from a pod or the Kubernetes API. */
public record Reading(String label, double value) { }
```

`RangeQuery.java`:

```java
package dev.marwan.console.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A Prometheus range query. An interface so MetricsService is tested without a cluster. */
public interface RangeQuery {
    List<Series> range(String promql, String labelKey, Instant start, Instant end, Duration step) throws Exception;
}
```

`ThanosTenancy.java`:

```java
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
        // Read per request (review focus 3): the projected token rotates hourly.
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
```

`PodReadings.java`:

```java
package dev.marwan.console.metrics;

import dev.marwan.console.objects.ObjectSource;
import io.fabric8.kubernetes.api.model.Pod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each chart's "now", read straight from the pods rather than from Prometheus:
 * fresher than a 15-second scrape, and still there if Prometheus is not.
 *
 * Latency has none - a quantile needs a windowed histogram, which is
 * Prometheus's job. Requests/s needs two reads to make a rate, so the first
 * read after a start returns nothing.
 */
@Component
public class PodReadings {

    @FunctionalInterface
    interface Fetch {
        String get(String url) throws Exception;
    }

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final ObjectSource source;
    private final Fetch fetch;
    private final Clock clock;
    private final Map<String, Counts> previous = new ConcurrentHashMap<>();

    private record Counts(Map<String, Double> byClass, Instant at) { }

    PodReadings(ObjectSource source, Fetch fetch, Clock clock) {
        this.source = source;
        this.fetch = fetch;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PodReadings(ObjectSource source, Clock clock) {
        this(source, httpFetch(), clock);
    }

    public List<Reading> now(ChartName chart) {
        return switch (chart) {
            case POOL -> pool();
            case REQUESTS -> requests();
            case REPLICAS -> replicas();
            case LATENCY -> List.of();
        };
    }

    private List<Reading> pool() {
        List<Reading> out = new ArrayList<>();
        for (Pod pod : running("booking-service")) {
            String body = read(pod);
            if (body == null) {
                continue;
            }
            PromText.samples(body, "hikaricp_connections_active").stream().findFirst()
                    .ifPresent(s -> out.add(new Reading(pod.getMetadata().getName(), s.value())));
        }
        out.sort((a, b) -> a.label().compareTo(b.label()));
        return out;
    }

    private List<Reading> requests() {
        Map<String, Double> byClass = new TreeMap<>();
        for (Pod pod : running("queue-gate")) {
            String body = read(pod);
            if (body == null) {
                continue;
            }
            for (PromText.Sample s : PromText.samples(body, "http_server_requests_seconds_count")) {
                String uri = s.labels().getOrDefault("uri", "");
                String status = s.labels().getOrDefault("status", "");
                if (uri.startsWith("/actuator") || status.isEmpty()) {
                    continue;
                }
                byClass.merge(pod.getMetadata().getName() + "|" + status.charAt(0) + "xx", s.value(), Double::sum);
            }
        }
        Instant at = clock.instant();
        Counts last = previous.put("requests", new Counts(byClass, at));
        if (last == null) {
            return List.of();
        }
        double seconds = Math.max(1, Duration.between(last.at(), at).toMillis() / 1000.0);
        Map<String, Double> rates = new TreeMap<>();
        byClass.forEach((key, value) -> {
            Double before = last.byClass().get(key);
            // Review focus 4: a counter that went down is a restarted pod, not negative traffic.
            double delta = before == null || value < before ? 0 : value - before;
            rates.merge(key.substring(key.indexOf('|') + 1), delta / seconds, Double::sum);
        });
        List<Reading> out = new ArrayList<>();
        rates.forEach((code, rate) -> out.add(new Reading(code, Math.round(rate * 10) / 10.0)));
        return out;
    }

    private List<Reading> replicas() {
        List<Reading> out = new ArrayList<>();
        for (String name : List.of("queue-gate", "booking-service")) {
            source.hpa(name).ifPresent(h -> out.add(new Reading(name,
                    h.getStatus() == null || h.getStatus().getCurrentReplicas() == null
                            ? 0 : h.getStatus().getCurrentReplicas())));
        }
        return out;
    }

    private List<Pod> running(String app) {
        return source.pods(app).stream()
                .filter(p -> p.getStatus() != null && p.getStatus().getPodIP() != null
                        && "Running".equals(p.getStatus().getPhase()))
                .toList();
    }

    /** One pod's exposition, or null if it would not answer: one quiet pod must not blank the reading. */
    private String read(Pod pod) {
        try {
            return fetch.get("http://" + pod.getStatus().getPodIP() + ":9090/actuator/prometheus");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static Fetch httpFetch() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        return url -> {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + r.statusCode());
            }
            return r.body();
        };
    }
}
```

- [ ] **Step 4: Run, see it pass** — `cd console && ./mvnw -q test -Dtest=PodReadingsTest` → exit 0, 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/metrics console/src/test/java/dev/marwan/console/metrics
git commit -m "Read chart history from Prometheus and the live value straight from the pods"
```

---

### Task 4: Joining them per chart

**Files:** Create `Chart.java`, `MetricsService.java`; test `MetricsServiceTest.java`.

**Interfaces:**
- Produces:
  - `record Chart(String name, String unit, String history, List<Series> series, String live, List<Reading> now)` — `history`/`live` are `null` when that source worked, else a one-line reason
  - `@Component class MetricsService { MetricsService(RangeQuery, PodReadings, Clock); Chart chart(ChartName, int minutes) }`

- [ ] **Step 1: Failing tests**

```java
package dev.marwan.console.metrics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class MetricsServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);
    private final PodReadings pods = mock(PodReadings.class);
    private final AtomicInteger promCalls = new AtomicInteger();

    @Test
    void historyAndNowArriveTogether() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            promCalls.incrementAndGet();
            return List.of(new Series("booking-a", List.of(new double[]{1, 3})));
        }, pods, clock);
        given(pods.now(ChartName.POOL)).willReturn(List.of(new Reading("booking-a", 4)));

        Chart chart = metrics.chart(ChartName.POOL, 15);

        assertThat(chart.series()).hasSize(1);
        assertThat(chart.now()).extracting(Reading::value).containsExactly(4.0);
        assertThat(chart.history()).isNull();
        assertThat(chart.live()).isNull();
    }

    @Test
    void prometheusDownKeepsTheLiveReading() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            throw new IllegalStateException("Prometheus answered HTTP 503");
        }, pods, clock);
        given(pods.now(ChartName.POOL)).willReturn(List.of(new Reading("booking-a", 4)));

        Chart chart = metrics.chart(ChartName.POOL, 15);

        assertThat(chart.series()).isEmpty();
        assertThat(chart.history()).contains("503");
        assertThat(chart.now()).hasSize(1);
    }

    @Test
    void podsUnreadableKeepTheHistory() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) ->
                List.of(new Series("queue-gate", List.of(new double[]{1, 2}))), pods, clock);
        given(pods.now(any())).willThrow(new IllegalStateException("API server refused"));

        Chart chart = metrics.chart(ChartName.REPLICAS, 15);

        assertThat(chart.series()).hasSize(1);
        assertThat(chart.live()).contains("API server refused");
    }

    /** Review focus 1. */
    @Test
    void noLatencyYetSaysWhy() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> List.of(), pods, clock);

        Chart chart = metrics.chart(ChartName.LATENCY, 15);

        assertThat(chart.history()).isEqualTo("No latency data yet - p95 needs requests with histograms on.");
    }

    @Test
    void historyIsCachedAcrossViewers() {
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            promCalls.incrementAndGet();
            return List.of();
        }, pods, clock);

        metrics.chart(ChartName.POOL, 15);
        metrics.chart(ChartName.POOL, 15);

        assertThat(promCalls.get()).isEqualTo(1);
    }

    @Test
    void theWindowIsClamped() {
        java.util.concurrent.atomic.AtomicReference<Instant> start = new java.util.concurrent.atomic.AtomicReference<>();
        MetricsService metrics = new MetricsService((q, l, s, e, st) -> {
            start.set(s);
            return List.of();
        }, pods, clock);

        metrics.chart(ChartName.POOL, 100_000);

        assertThat(start.get()).isEqualTo(Instant.parse("2026-09-25T09:00:00Z"));
    }
}
```

- [ ] **Step 2: Run, see it fail** — `cd console && ./mvnw -q test -Dtest=MetricsServiceTest` → compilation failure.

- [ ] **Step 3: Implement**

`Chart.java`:

```java
package dev.marwan.console.metrics;

import java.util.List;

/**
 * One chart: history from Prometheus, the live value from the pods.
 *
 * @param history null when the history was read; otherwise why it was not
 * @param live    null when the live reading was taken; otherwise why it was not
 */
public record Chart(String name, String unit, String history, List<Series> series,
                    String live, List<Reading> now) { }
```

`MetricsService.java`:

```java
package dev.marwan.console.metrics;

import dev.marwan.console.cluster.KubernetesAccess;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A chart, from two sources that fail separately.
 *
 * Prometheus's history is reused for ten seconds (it scrapes every fifteen);
 * the pods' live reading for two. However many people watch, the cluster sees
 * one query per chart per ten seconds and one read per pod per two.
 */
@Component
public class MetricsService {

    static final Duration HISTORY_TTL = Duration.ofSeconds(10);
    static final Duration LIVE_TTL = Duration.ofSeconds(2);
    static final Duration STEP = Duration.ofSeconds(15);
    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 60;
    static final String NO_LATENCY = "No latency data yet - p95 needs requests with histograms on.";

    private final RangeQuery prometheus;
    private final PodReadings pods;
    private final Clock clock;
    private final Map<String, Held<List<Series>>> history = new ConcurrentHashMap<>();
    private final Map<ChartName, Held<List<Reading>>> live = new ConcurrentHashMap<>();

    private record Held<T>(T value, String failure, Instant at) { }

    public MetricsService(RangeQuery prometheus, PodReadings pods, Clock clock) {
        this.prometheus = prometheus;
        this.pods = pods;
        this.clock = clock;
    }

    public Chart chart(ChartName chart, int minutes) {
        int window = Math.clamp(minutes, MIN_MINUTES, MAX_MINUTES);
        Held<List<Series>> h = history.compute(chart.path() + "/" + window, (k, held) ->
                fresh(held, HISTORY_TTL) ? held : readHistory(chart, window));
        Held<List<Reading>> l = live.compute(chart, (k, held) ->
                fresh(held, LIVE_TTL) ? held : readLive(chart));
        String historyNote = h.failure() != null ? h.failure()
                : chart == ChartName.LATENCY && h.value().isEmpty() ? NO_LATENCY : null;
        return new Chart(chart.path(), chart.unit(), historyNote, h.value(), l.failure(), l.value());
    }

    private Held<List<Series>> readHistory(ChartName chart, int minutes) {
        Instant end = clock.instant();
        try {
            return new Held<>(prometheus.range(chart.promql(), chart.labelKey(),
                    end.minus(Duration.ofMinutes(minutes)), end, STEP), null, end);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Held<>(List.of(), "History unavailable: " + KubernetesAccess.summarise(e), end);
        }
    }

    private Held<List<Reading>> readLive(ChartName chart) {
        try {
            return new Held<>(pods.now(chart), null, clock.instant());
        } catch (RuntimeException e) {
            return new Held<>(List.of(), "Live reading unavailable: " + KubernetesAccess.summarise(e), clock.instant());
        }
    }

    private boolean fresh(Held<?> held, Duration ttl) {
        return held != null && held.at().plus(ttl).isAfter(clock.instant());
    }
}
```

The windows a caller can ask for are clamped to 5–60, so the history map holds at most 4 × 56 entries.

- [ ] **Step 4: Run, see it pass** — same command → exit 0, 6 tests, 0 failures; then the full suite `./mvnw -q test` → exit 0.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/metrics console/src/test/java/dev/marwan/console/metrics
git commit -m "Join each chart's history and live reading, each failing on its own"
```

---

### Task 5: The endpoint

**Files:** Create `MetricsController.java`; test `MetricsControllerTest.java`.

**Interfaces:** Produces `GET /api/metrics/{chart}?minutes=15` → `Chart`; unknown chart → 404 `{"error":"NOT_FOUND"}`.

- [ ] **Step 1: Failing test**

```java
package dev.marwan.console.metrics;

import dev.marwan.console.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsController.class)
@Import(MetricsControllerTest.Properties.class)
class MetricsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MetricsService metrics;

    @Test
    void aNamedChartIsServed() throws Exception {
        given(metrics.chart(ChartName.POOL, 15)).willReturn(new Chart("pool", "connections", null,
                List.of(new Series("booking-a", List.of(new double[]{1, 3}))), null, List.of(new Reading("booking-a", 4))));

        mvc.perform(get("/api/metrics/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series[0].label").value("booking-a"))
                .andExpect(jsonPath("$.now[0].value").value(4.0));
    }

    @Test
    void theWindowIsPassedThrough() throws Exception {
        given(metrics.chart(ChartName.REQUESTS, 30)).willReturn(new Chart("requests", "req/s", null, List.of(), null, List.of()));

        mvc.perform(get("/api/metrics/requests").param("minutes", "30")).andExpect(status().isOk());
        then(metrics).should().chart(ChartName.REQUESTS, 30);
    }

    @Test
    void anyOtherNameIs404WithoutEchoingIt() throws Exception {
        mvc.perform(get("/api/metrics/{chart}", "<script>").header("Accept", "text/html"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    static class Properties {
        @Bean
        ConsoleProperties consoleProperties() {
            return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                    "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "marwanbukhori-dev",
                    "s3cret-demo-key", "compute-deploy", "grafana/k6:0.53.0",
                    new ConsoleProperties.Pool("booking-service", 5, 20));
        }
    }
}
```

- [ ] **Step 2: Run, see it fail** → compilation failure (`MetricsController` missing).

- [ ] **Step 3: Implement**

```java
package dev.marwan.console.metrics;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The charts strip. Four names, fixed queries; public like every GET. */
@RestController
public class MetricsController {

    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/api/metrics/{chart}")
    public ResponseEntity<?> chart(@PathVariable String chart, @RequestParam(defaultValue = "15") int minutes) {
        return ChartName.parse(chart)
                .<ResponseEntity<?>>map(name -> ResponseEntity.ok(metrics.chart(name, minutes)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("error", "NOT_FOUND")));
    }
}
```

- [ ] **Step 4: Run, see it pass**, then the full suite. **Step 5: Commit** — `git commit -m "Serve the four charts by name"` (add the two files).

---

### Task 6: The charts strip

**Files:** `console/ui/src/app/state.ts` (types), create `metrics.service.ts`, `charts-strip.ts`; modify `visitor.ts`.

- [ ] **Step 1: Types** (append to `state.ts`):

```ts
export interface ChartSeries { label: string; points: [number, number][]; }
export interface ChartReading { label: string; value: number; }
export interface ChartData {
  name: 'requests' | 'latency' | 'pool' | 'replicas';
  unit: string;
  history: string | null;
  series: ChartSeries[];
  live: string | null;
  now: ChartReading[];
}
```

- [ ] **Step 2: `metrics.service.ts`** — a root service polling all four every 5 s (history is cached 10 s server-side, live 2 s), keeping the last good reading on error:

```ts
import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ChartData } from './state';

const POLL_MILLIS = 5000;
export const CHARTS = ['requests', 'latency', 'pool', 'replicas'] as const;

@Injectable({ providedIn: 'root' })
export class MetricsService {
  private readonly http = inject(HttpClient);
  readonly charts = signal<Partial<Record<(typeof CHARTS)[number], ChartData>>>({});

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  private poll(): void {
    for (const name of CHARTS) {
      this.http.get<ChartData>(`/api/metrics/${name}`, { params: { minutes: 15 } }).subscribe({
        next: (c) => this.charts.update((was) => ({ ...was, [name]: c })),
        error: () => {}
      });
    }
  }
}
```

- [ ] **Step 3: `charts-strip.ts`** — four small multiples. Requirements (from the dataviz method):
  - Per chart: title (Requests/s, Latency p95, DB pool in use, Replicas), a "now" row of direct values with the label "live from pods" (or its `live` reason), the SVG plot, a legend row, and a "Table" toggle rendering the last 8 points per series.
  - SVG `viewBox="0 0 320 120"`, plot inset left 28 / right 56 (room for end labels) / top 8 / bottom 18. y from 0 to a nice max ≥ the data max (pool: fixed 5 with a dashed reference line labelled "pool size 5"; replicas: at least the HPA max seen, rounded up). x across the 15-minute window ending now.
  - 3 recessive horizontal gridlines (`stroke: var(--line)`), y tick labels in `var(--muted)` 10px; x labels "−15m" and "now".
  - Series: 2px polyline, `stroke-linejoin: round`, colours by fixed slot in label order via CSS vars `--series-1..4` = `#2a78d6, #eb6834, #1baf7a, #eda100` on the component root. A series is split into separate polylines where consecutive points are more than 2 steps (30 s) apart (review focus 2: a pod that came or went).
  - Direct label at each line's last point: the label text in `var(--ink-soft)`, 10px, preceded by a 8px colour swatch; labels nudged apart vertically if within 10px.
  - Hover: a transparent rect over the plot; on pointermove, a vertical crosshair at the nearest timestamp and a tooltip (HTML, absolutely positioned) listing each series' value at that time, formatted with the unit (latency in ms: value × 1000, 0 decimals; req/s 1 decimal; others integers).
  - Empty/failed states: `history` text shown in the plot area in `var(--muted)`; no axes drawn without data.
  - Grid: 4 columns ≥ 1650px (the platform column is wide), 2×2 below, 1 column under 600px.
  - `aria-label` on each SVG summarising the latest value per series.

Write the component to that specification (inline template + styles, standalone, signals; derive geometry in `computed()`s from `MetricsService.charts()`), keeping each chart's rendering in one `protected` method set: `paths(chart)`, `endLabels(chart)`, `yTicks(chart)`, `tooltip(chart, x)`.

- [ ] **Step 4: Place it.** In `visitor.ts`, import `ChartsStrip` and put `<rb-charts-strip />` as the first child of the `.platform` section, above "The platform, live" note's graph, with a heading "Last 15 minutes". Update the section comment: the charts landed.

- [ ] **Step 5: Build** — `cd console/ui && npx ng build` → bundle generation complete.

- [ ] **Step 6: Check locally** against the real cluster (backend on 8083/9093 with a valid `oc` login; Prometheus is not reachable from a laptop, so expect every chart's `history` to say "History unavailable…" and the live readings for replicas to work). Check: layout at 1680 / 1366 / 390, the unavailable message is readable, no sideways scroll. Then open the page once deployed (Task 7) for the real lines.

- [ ] **Step 7: Commit** — `git commit -m "Draw the four charts above the graph: fifteen minutes from Prometheus, now from the pods"`.

---

### Task 7: Deploy and verify

- [ ] **Step 1:** Merge `--ff-only` to `main`, push, watch CD (both services rebuild with histograms; the console gains the metrics package). `deploy/scripts/status.sh` → `everything is up`.
- [ ] **Step 2:** Live checks:

```bash
C=https://console-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com
for c in requests latency pool replicas; do
  curl -s "$C/api/metrics/$c" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["name"], "| history:", d["history"] or "ok", len(d["series"]), "series | live:", d["live"] or "ok", d["now"])'
done
curl -s -o /dev/null -w "unknown chart: %{http_code}\n" "$C/api/metrics/secrets"
```

Expected: `pool` and `replicas` history ok with series; `requests` ok; `latency` either ok or "No latency data yet" until traffic flows after the redeploy; `pool` live shows one reading per booking-service pod — **if pool live is empty, the direct read from the console is blocked**: record it, and the chart still stands on Prometheus.

- [ ] **Step 3:** Start a rush and watch the strip: requests/s climbs, pool rises towards 5 on the busy pod, replicas steps up, and after a minute p95 appears.

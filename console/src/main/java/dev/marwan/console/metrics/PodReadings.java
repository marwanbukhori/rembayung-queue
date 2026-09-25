package dev.marwan.console.metrics;

import dev.marwan.console.objects.ObjectSource;
import io.fabric8.kubernetes.api.model.Pod;
import org.springframework.beans.factory.annotation.Autowired;
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

    private record Counts(Map<String, Double> byPodAndClass, Instant at) { }

    PodReadings(ObjectSource source, Fetch fetch, Clock clock) {
        this.source = source;
        this.fetch = fetch;
        this.clock = clock;
    }

    @Autowired
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
        Map<String, Double> counts = new TreeMap<>();
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
                counts.merge(pod.getMetadata().getName() + "|" + status.charAt(0) + "xx", s.value(), Double::sum);
            }
        }
        Instant at = clock.instant();
        Counts last = previous.put("requests", new Counts(counts, at));
        if (last == null) {
            return List.of();
        }
        double seconds = Math.max(1, Duration.between(last.at(), at).toMillis() / 1000.0);
        Map<String, Double> rates = new TreeMap<>();
        counts.forEach((key, value) -> {
            Double before = last.byPodAndClass().get(key);
            // A counter that went down is a restarted pod, not negative traffic.
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
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

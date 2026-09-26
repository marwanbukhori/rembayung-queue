package dev.marwan.console.slo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import dev.marwan.console.metrics.RangeQuery;
import dev.marwan.console.metrics.Series;

/**
 * The booking SLOs, read from Prometheus: at least 99% of bookings not 5xx,
 * and p95 under two seconds, both over five minutes of booking-service's own
 * /bookings requests - the path customers feel, not the queue polls.
 */
@Component
public class SloService {

    static final String SELECTOR = "job=\"booking-service\",uri=\"/bookings\"";
    static final String TOTAL = "sum by (job) (rate(http_server_requests_seconds_count{" + SELECTOR + "}[5m]))";
    static final String ERRORS = "sum by (job) (rate(http_server_requests_seconds_count{" + SELECTOR
            + ",status=~\"5..\"}[5m]))";
    static final String P95 = "histogram_quantile(0.95, sum by (le, job) (rate(http_server_requests_seconds_bucket{"
            + SELECTOR + "}[5m])))";
    static final Duration STEP = Duration.ofSeconds(15);

    private final RangeQuery prometheus;
    private final Clock clock;

    public SloService(RangeQuery prometheus, Clock clock) {
        this.prometheus = prometheus;
        this.clock = clock;
    }

    public SloReading now() {
        List<SloReading> recent = history(Duration.ofMinutes(1));
        return recent.isEmpty() ? new SloReading(clock.instant(), true, null, false, null, null)
                : recent.get(recent.size() - 1);
    }

    public List<SloReading> history(Duration window) {
        Instant end = clock.instant();
        Instant start = end.minus(window);
        try {
            Map<Long, Double> total = byTime(prometheus.range(TOTAL, "job", start, end, STEP));
            Map<Long, Double> errors = byTime(prometheus.range(ERRORS, "job", start, end, STEP));
            Map<Long, Double> p95 = byTime(prometheus.range(P95, "job", start, end, STEP));
            List<SloReading> out = new ArrayList<>();
            for (Map.Entry<Long, Double> t : total.entrySet()) {
                double rate = t.getValue();
                boolean traffic = rate > 0;
                Double success = traffic ? round(1 - errors.getOrDefault(t.getKey(), 0.0) / rate) : null;
                Double latency = traffic ? p95.get(t.getKey()) : null;
                out.add(new SloReading(Instant.ofEpochSecond(t.getKey()), true, null, traffic, success,
                        latency == null ? null : round(latency)));
            }
            if (out.isEmpty()) {
                out.add(new SloReading(end, true, null, false, null, null));
            }
            return out;
        } catch (Exception e) {
            return List.of(new SloReading(end, false, e.getMessage(), false, null, null));
        }
    }

    private static Map<Long, Double> byTime(List<Series> series) {
        Map<Long, Double> out = new TreeMap<>();
        for (Series s : series) {
            for (double[] p : s.points()) {
                if (!Double.isNaN(p[1])) {
                    out.merge((long) p[0], p[1], Double::sum);
                }
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}

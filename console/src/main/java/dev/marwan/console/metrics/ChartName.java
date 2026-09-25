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

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String promql() {
        return promql;
    }

    public String labelKey() {
        return labelKey;
    }

    public String unit() {
        return unit;
    }

    public static Optional<ChartName> parse(String value) {
        for (ChartName c : values()) {
            if (c.path().equals(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}

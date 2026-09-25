package dev.marwan.console.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** A Prometheus range query. An interface so MetricsService is tested without a cluster. */
public interface RangeQuery {

    List<Series> range(String promql, String labelKey, Instant start, Instant end, Duration step) throws Exception;
}

package dev.marwan.console.metrics;

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
                // histogram_quantile yields NaN on empty buckets: dropped, never drawn at 0.
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

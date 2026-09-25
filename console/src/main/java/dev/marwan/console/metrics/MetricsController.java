package dev.marwan.console.metrics;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The charts strip. Four names, four fixed queries; public like every GET.
 * Any other name is a fixed JSON 404 that never repeats what was asked for.
 */
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

package dev.marwan.gate.config;

import dev.marwan.gate.queue.QueueStateProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class GateMetrics implements MeterBinder {

    private final QueueStateProvider provider;

    public GateMetrics(QueueStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("rembayung_queue_tickets_issued", provider,
                        p -> p.current().ticketsIssued())
                .register(registry);
        Gauge.builder("rembayung_queue_admitted", provider,
                        p -> p.current().admitted())
                .register(registry);
        Gauge.builder("rembayung_queue_waiting", provider,
                        p -> p.current().waiting())
                .register(registry);
    }
}

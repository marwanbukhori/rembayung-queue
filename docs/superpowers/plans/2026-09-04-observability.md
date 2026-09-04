# Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the system observable — Prometheus metrics carrying the domain invariant, alerts written from failures that actually happened, Dynatrace APM traces, and structured logs in Splunk.

**Architecture:** Each service computes its own state through a single provider component; Micrometer gauges read that provider, and Phase 8's public API will read the same one. Metrics ride the existing private management port and are scraped by the cluster's own Prometheus at zero quota. Dynatrace attaches as an application-only Java agent; Splunk receives JSON logs over HEC with no agent at all.

**Tech Stack:** Spring Boot 4.1.1, Micrometer, Prometheus, `logstash-logback-encoder`, Dynatrace OneAgent (app-only), Splunk HEC

**Spec:** [`docs/superpowers/specs/2026-09-04-observability-design.md`](../specs/2026-09-04-observability-design.md)

## Global Constraints

- **`rembayung_slot_oversold` must always read 0.** It is `max(0, seatsTaken − capacity)` and is deliberately redundant with the database's `ck_slots_seats` CHECK constraint. If it ever moves, either the invariant broke or the metric lies.
- **The management port stays private.** Metrics are exposed on port 9090 only. `/actuator/prometheus` through the public Route must return 404, exactly as `/actuator/health` already does. Never add `prometheus` to a publicly routed port.
- **One computation, several projections.** Slot state is computed once, by `SlotStateProvider`. Phase 8's public endpoint reads the same component. Nothing else may recompute seats, capacity or oversold.
- **The Splunk appender must never block the booking path.** Asynchronous, bounded queue, drop on full. Losing logs is acceptable; losing bookings is not.
- **No token is ever committed, echoed, logged, or placed in a ConfigMap.** Dynatrace PaaS/tenant tokens and the Splunk HEC token are OpenShift Secrets created by a human.
- **Commit messages contain no AI attribution.** No `Co-Authored-By`, no `Claude-Session`, no claude.ai URL, no "Generated with", no robot emoji, no mention of an assistant, model or session. Hard standing rule of the repository owner; violated once already in this project, requiring a history rewrite. After each commit run `git log -1 --format=%B`, read it, and amend if anything but your own text is present.

### Four things that will bite you

**Spring Boot 4 renamed artifacts, and this project has been burned twice.** Testcontainers modules moved (`testcontainers-oracle-free`), and Flyway's autoconfiguration moved to a `spring-boot-flyway` artifact — costing hours each time. **Verify the Prometheus registry artifact resolves before writing code that depends on it.** Task 1 Step 1 does exactly that.

**The containers have no `curl`.** They are JRE images. Do not `oc exec ... curl`. Use an ephemeral debug pod:
`oc run probe --rm -i --restart=Never --image=registry.access.redhat.com/ubi9/ubi-minimal --command -- curl -s <url>`

**Metrics are split across two services.** `booking-service` owns slot state (Oracle); `queue-gate` owns queue state (Redis). There is no single service that knows both. Phase 8 will need the gate to proxy slot state from booking-service — the NetworkPolicy already permits gate→booking-service and nothing else.

**The cluster is live and serving.** Real pods behind a public Route. Deploy only through the Phase 5 playbook, never by hand.

---

## File Structure

```
booking-service/
├── pom.xml                                        + micrometer-registry-prometheus, logstash-logback-encoder
├── src/main/resources/application.yml             expose prometheus on 9090
├── src/main/resources/logback-spring.xml          NEW — JSON encoder + async Splunk appender
└── src/main/java/dev/marwan/booking/
    ├── service/SlotStateProvider.java             NEW — the single computation
    ├── domain/SlotState.java                      NEW — immutable record
    └── config/BookingMetrics.java                 NEW — binds gauges to the provider

queue-gate/
├── pom.xml                                        + same two dependencies
├── src/main/resources/application.yml             expose prometheus on 9090
├── src/main/resources/logback-spring.xml          NEW
└── src/main/java/dev/marwan/gate/
    ├── queue/QueueStateProvider.java              NEW
    ├── queue/QueueState.java                      NEW
    └── config/GateMetrics.java                    NEW

deploy/base/observability/                         NEW
├── servicemonitor.yaml                            scrape both services on the management port
├── prometheusrule.yaml                            the five alerts
└── kustomization.yaml

deploy/base/booking-service/deployment.yaml        + Dynatrace initContainer, JAVA_TOOL_OPTIONS, secret envs
deploy/base/queue-gate/deployment.yaml             + same
docs/notes/08-observability.md                     NEW
```

---

## Task 1: Prometheus metrics in booking-service

**Files:**
- Modify: `booking-service/pom.xml`
- Modify: `booking-service/src/main/resources/application.yml`
- Create: `booking-service/src/main/java/dev/marwan/booking/domain/SlotState.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/service/SlotStateProvider.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/config/BookingMetrics.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/service/SlotStateProviderTest.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/config/BookingMetricsTest.java`

**Interfaces:**
- Consumes: `SlotRepository` (`findById`), `Slot` (`getCapacity()`, `getSeatsTaken()`, `getId()`).
- Produces: `SlotState` — a record `(long slotId, int capacity, int seatsTaken, int remaining, int oversold)`; and `SlotStateProvider.stateFor(long slotId)` returning `Optional<SlotState>`, plus `SlotStateProvider.trackedSlotIds()` returning `List<Long>`. **Phase 8 consumes both.** Metric names `rembayung_slot_{capacity,seats_taken,remaining,oversold}`.

- [ ] **Step 1: Verify the registry artifact actually resolves before depending on it**

Boot 4 has moved artifacts twice in this project already. Check first:

```bash
cd booking-service
../mvnw -q dependency:get -Dartifact=io.micrometer:micrometer-registry-prometheus:1.15.4 2>&1 | tail -5
```

Expected: no error. If it fails, run `../mvnw dependency:tree | grep -i micrometer` to find the version Boot 4.1.1 manages, and use that — do not guess a version number.

- [ ] **Step 2: Add the dependency**

In `booking-service/pom.xml`, inside `<dependencies>`:

```xml
    <!-- Exposes /actuator/prometheus. Version is managed by the Spring Boot
         parent; pinning it here would fight the BOM. -->
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

- [ ] **Step 3: Write the failing test for SlotStateProvider**

Create `booking-service/src/test/java/dev/marwan/booking/service/SlotStateProviderTest.java`:

```java
package dev.marwan.booking.service;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlotStateProviderTest {

    private Slot slotWith(int capacity, int taken) {
        Slot slot = new Slot(LocalDate.of(2026, 10, 1), "19:00", capacity);
        if (taken > 0) {
            slot.takeSeats(taken);
        }
        return slot;
    }

    @Test
    void reportsRemainingAndZeroOversoldForAPartiallyFilledSlot() {
        SlotRepository repo = mock(SlotRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.of(slotWith(250, 202)));

        SlotState state = new SlotStateProvider(repo).stateFor(1L).orElseThrow();

        assertThat(state.capacity()).isEqualTo(250);
        assertThat(state.seatsTaken()).isEqualTo(202);
        assertThat(state.remaining()).isEqualTo(48);
        assertThat(state.oversold()).isZero();
    }

    @Test
    void reportsZeroOversoldForAnExactlyFullSlot() {
        SlotRepository repo = mock(SlotRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.of(slotWith(250, 250)));

        SlotState state = new SlotStateProvider(repo).stateFor(1L).orElseThrow();

        assertThat(state.remaining()).isZero();
        assertThat(state.oversold()).isZero();
    }

    // The database's ck_slots_seats CHECK constraint makes this state impossible
    // to persist, and the domain refuses to produce it. The record is still
    // tested directly, because the gauge exists to notice a state that "cannot
    // happen" — a gauge that cannot represent the failure it watches for is
    // decoration.
    @Test
    void reportsTheOverageIfASlotEverExceededItsCapacity() {
        SlotState state = new SlotState(1L, 250, 253, 0, 3);

        assertThat(state.oversold()).isEqualTo(3);
    }

    @Test
    void returnsEmptyForAnUnknownSlot() {
        SlotRepository repo = mock(SlotRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.empty());

        assertThat(new SlotStateProvider(repo).stateFor(99L)).isEmpty();
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

```bash
./mvnw -q -f booking-service test -Dtest=SlotStateProviderTest
```

Expected: FAIL — `SlotState` and `SlotStateProvider` do not exist.

- [ ] **Step 5: Create the SlotState record**

`booking-service/src/main/java/dev/marwan/booking/domain/SlotState.java`:

```java
package dev.marwan.booking.domain;

/**
 * A slot's state at one instant, computed once and projected several ways.
 *
 * Micrometer gauges read this today. Phase 8's public JSON endpoint reads the
 * same record, so the number on a public dashboard and the number in an alert
 * rule cannot drift apart — which matters because seatsTaken versus capacity is
 * the claim this whole project makes.
 *
 * Field names match metric names minus the rembayung_ prefix, on purpose.
 */
public record SlotState(
        long slotId,
        int capacity,
        int seatsTaken,
        int remaining,
        int oversold) {

    public static SlotState of(long slotId, int capacity, int seatsTaken) {
        return new SlotState(
                slotId,
                capacity,
                seatsTaken,
                Math.max(0, capacity - seatsTaken),
                Math.max(0, seatsTaken - capacity));
    }
}
```

- [ ] **Step 6: Create SlotStateProvider**

`booking-service/src/main/java/dev/marwan/booking/service/SlotStateProvider.java`:

```java
package dev.marwan.booking.service;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The single place slot state is computed.
 *
 * Deliberately a plain read: findById, not findByIdForUpdate. Taking the
 * pessimistic lock here would put a metrics scrape into contention with real
 * bookings on the one row the whole system serialises on — a monitor that
 * degrades the thing it monitors.
 */
@Service
public class SlotStateProvider {

    private final SlotRepository slotRepository;

    public SlotStateProvider(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    @Transactional(readOnly = true)
    public Optional<SlotState> stateFor(long slotId) {
        return slotRepository.findById(slotId)
                .map(slot -> SlotState.of(slot.getId(), slot.getCapacity(), slot.getSeatsTaken()));
    }

    /**
     * Slots the gauges report on. Every slot in the table — the demo has one,
     * and a restaurant booking system will never have enough rows here for this
     * to be the expensive query.
     */
    @Transactional(readOnly = true)
    public List<Long> trackedSlotIds() {
        return slotRepository.findAll().stream().map(s -> s.getId()).toList();
    }
}
```

- [ ] **Step 7: Run the test and watch it pass**

```bash
./mvnw -q -f booking-service test -Dtest=SlotStateProviderTest
```

Expected: PASS, 4 tests.

- [ ] **Step 8: Write the failing test for the gauges**

Create `booking-service/src/test/java/dev/marwan/booking/config/BookingMetricsTest.java`:

```java
package dev.marwan.booking.config;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.service.SlotStateProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingMetricsTest {

    @Test
    void publishesSlotGaugesTaggedBySlot() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.trackedSlotIds()).thenReturn(List.of(1L));
        when(provider.stateFor(1L)).thenReturn(Optional.of(SlotState.of(1L, 250, 202)));

        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider).bindTo(registry);

        assertThat(registry.get("rembayung_slot_capacity").tag("slot", "1").gauge().value())
                .isEqualTo(250.0);
        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value())
                .isEqualTo(202.0);
        assertThat(registry.get("rembayung_slot_remaining").tag("slot", "1").gauge().value())
                .isEqualTo(48.0);
        assertThat(registry.get("rembayung_slot_oversold").tag("slot", "1").gauge().value())
                .isZero();
    }

    // A slot that vanishes between registration and scrape must not throw and
    // must not report a stale value. NaN is Micrometer's "no value right now".
    @Test
    void reportsNaNRatherThanThrowingWhenASlotDisappears() {
        SlotStateProvider provider = mock(SlotStateProvider.class);
        when(provider.trackedSlotIds()).thenReturn(List.of(1L));
        when(provider.stateFor(1L)).thenReturn(Optional.empty());

        MeterRegistry registry = new SimpleMeterRegistry();
        new BookingMetrics(provider).bindTo(registry);

        assertThat(registry.get("rembayung_slot_seats_taken").tag("slot", "1").gauge().value())
                .isNaN();
    }
}
```

- [ ] **Step 9: Run it and watch it fail**

```bash
./mvnw -q -f booking-service test -Dtest=BookingMetricsTest
```

Expected: FAIL — `BookingMetrics` does not exist.

- [ ] **Step 10: Create BookingMetrics**

`booking-service/src/main/java/dev/marwan/booking/config/BookingMetrics.java`:

```java
package dev.marwan.booking.config;

import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.service.SlotStateProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

/**
 * Binds the domain's own numbers to Micrometer.
 *
 * rembayung_slot_oversold is the one that matters. It must read 0 forever, and
 * it is deliberately redundant with the database's ck_slots_seats CHECK
 * constraint that makes the condition impossible to persist. That redundancy is
 * the point: if the gauge ever moves, either the invariant broke or the metric
 * is lying, and both deserve waking someone up.
 */
@Component
public class BookingMetrics implements MeterBinder {

    private final SlotStateProvider provider;

    public BookingMetrics(SlotStateProvider provider) {
        this.provider = provider;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (Long slotId : provider.trackedSlotIds()) {
            register(registry, "rembayung_slot_capacity", slotId, SlotState::capacity);
            register(registry, "rembayung_slot_seats_taken", slotId, SlotState::seatsTaken);
            register(registry, "rembayung_slot_remaining", slotId, SlotState::remaining);
            register(registry, "rembayung_slot_oversold", slotId, SlotState::oversold);
        }
    }

    private void register(MeterRegistry registry, String name, Long slotId,
                          ToDoubleFunction<SlotState> value) {
        Gauge.builder(name, slotId, id -> provider.stateFor(id)
                        .map(value::applyAsDouble)
                        // A slot that disappeared has no value, which is not the
                        // same as zero. Reporting 0 seats taken for a deleted
                        // slot would look like an empty restaurant.
                        .orElse(Double.NaN))
                .tag("slot", String.valueOf(slotId))
                .register(registry);
    }
}
```

- [ ] **Step 11: Run the test and watch it pass**

```bash
./mvnw -q -f booking-service test -Dtest=BookingMetricsTest
```

Expected: PASS, 2 tests.

- [ ] **Step 12: Expose the endpoint on the management port only**

In `booking-service/src/main/resources/application.yml`, change the exposure line:

```yaml
  endpoints:
    web:
      exposure:
        # prometheus rides the management port (9090), which is not on the public
        # Route. Adding it to a publicly routed port would publish internal
        # timings and JVM detail to the internet.
        include: health,prometheus
```

- [ ] **Step 13: Verify the whole suite still passes**

```bash
./mvnw -q -f booking-service test 2>&1 | tail -20
```

Expected: all tests pass, zero skipped. The count should be 36 plus the 6 added here.

- [ ] **Step 14: Commit**

```bash
git add booking-service
git commit -m "Publish slot state as Prometheus metrics"
git log -1 --format=%B
```

Read that output. Amend if it contains anything you did not write.

---

## Task 2: Prometheus metrics in queue-gate

**Files:**
- Modify: `queue-gate/pom.xml`
- Modify: `queue-gate/src/main/resources/application.yml`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/QueueState.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/QueueStateProvider.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/config/GateMetrics.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/queue/QueueStateProviderTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`, `DropProperties` (`opensAt()`, `admitRate()`, `ticketCap()`), `Admission.admittedBy(Instant, Instant, int)`, `Clock`. The Redis key is `queue:ticket`, declared as `QueueService.TICKET_COUNTER` (package-private).
- Produces: `QueueState` — a record `(long ticketsIssued, long admitted, long waiting, int ticketCap)`; and `QueueStateProvider.current()` returning `QueueState`. **Phase 8 consumes this.** Metric names `rembayung_queue_{tickets_issued,admitted,waiting}`.

- [ ] **Step 1: Add the dependency**

In `queue-gate/pom.xml`, inside `<dependencies>`:

```xml
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

Create `queue-gate/src/test/java/dev/marwan/gate/queue/QueueStateProviderTest.java`:

```java
package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueStateProviderTest {

    private static final Instant OPENS = Instant.parse("2026-10-01T13:00:00Z");

    private QueueStateProvider providerWith(String counterValue, Instant now, int admitRate) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(counterValue);

        DropProperties drop = new DropProperties(
                OPENS, OPENS.plus(Duration.ofHours(1)), 250, admitRate, Duration.ofMinutes(5));

        return new QueueStateProvider(redis, drop, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void reportsWaitingAsIssuedMinusAdmitted() {
        // 10s after opening at 1/second, 10 are admitted of 40 issued.
        QueueState state = providerWith("40", OPENS.plusSeconds(10), 1).current();

        assertThat(state.ticketsIssued()).isEqualTo(40);
        assertThat(state.admitted()).isEqualTo(10);
        assertThat(state.waiting()).isEqualTo(30);
    }

    // An untouched Redis has no counter key at all. That is zero issued, not an
    // error and not a NullPointerException on a metrics scrape.
    @Test
    void treatsAnAbsentCounterAsZeroIssued() {
        QueueState state = providerWith(null, OPENS.plusSeconds(10), 1).current();

        assertThat(state.ticketsIssued()).isZero();
        assertThat(state.waiting()).isZero();
    }

    // Admission is a pure function of time and keeps counting past the last
    // issued ticket. Waiting must not go negative, which would render as a
    // nonsense queue depth on a dashboard.
    @Test
    void neverReportsNegativeWaiting() {
        QueueState state = providerWith("5", OPENS.plusSeconds(600), 1).current();

        assertThat(state.admitted()).isGreaterThan(state.ticketsIssued());
        assertThat(state.waiting()).isZero();
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
./mvnw -q -f queue-gate test -Dtest=QueueStateProviderTest
```

Expected: FAIL — `QueueState` and `QueueStateProvider` do not exist.

- [ ] **Step 4: Create QueueState**

`queue-gate/src/main/java/dev/marwan/gate/queue/QueueState.java`:

```java
package dev.marwan.gate.queue;

/**
 * The queue's state at one instant.
 *
 * Phase 8's public endpoint reads this same record, so the dashboard and the
 * metrics cannot disagree. Field names match metric names minus the rembayung_
 * prefix.
 */
public record QueueState(
        long ticketsIssued,
        long admitted,
        long waiting,
        int ticketCap) {

    public static QueueState of(long ticketsIssued, long admitted, int ticketCap) {
        // admittedBy() is a pure function of elapsed time and keeps counting
        // past the last ticket ever issued, so this subtraction can go negative.
        // A queue of -412 people is not a thing.
        return new QueueState(
                ticketsIssued,
                admitted,
                Math.max(0, ticketsIssued - admitted),
                ticketCap);
    }
}
```

- [ ] **Step 5: Create QueueStateProvider**

`queue-gate/src/main/java/dev/marwan/gate/queue/QueueStateProvider.java`:

```java
package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Reads queue state without disturbing it.
 *
 * Note GET, never INCR — a metrics scrape that issued a ticket would consume
 * one of the 250 the drop has to give away, and the scrape interval would
 * quietly set the pace at which the queue sold out.
 */
@Service
public class QueueStateProvider {

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    public QueueStateProvider(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    public QueueState current() {
        String raw = redis.opsForValue().get(QueueService.TICKET_COUNTER);
        long issued = raw == null ? 0L : Long.parseLong(raw);
        long admitted = Admission.admittedBy(clock.instant(), drop.opensAt(), drop.admitRate());
        return QueueState.of(issued, admitted, drop.ticketCap());
    }
}
```

- [ ] **Step 6: Run the test and watch it pass**

```bash
./mvnw -q -f queue-gate test -Dtest=QueueStateProviderTest
```

Expected: PASS, 3 tests.

- [ ] **Step 7: Create GateMetrics**

`queue-gate/src/main/java/dev/marwan/gate/config/GateMetrics.java`:

```java
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
```

- [ ] **Step 8: Expose the endpoint on the management port only**

In `queue-gate/src/main/resources/application.yml`:

```yaml
  endpoints:
    web:
      exposure:
        # Management port 9090 only — it is not on the public Route.
        include: health,prometheus
```

- [ ] **Step 9: Run the full gate suite**

```bash
./mvnw -q -f queue-gate test 2>&1 | tail -20
```

Expected: all pass, zero skipped — 38 plus the 3 added here.

- [ ] **Step 10: Commit**

```bash
git add queue-gate
git commit -m "Publish queue state as Prometheus metrics"
git log -1 --format=%B
```

---

## Task 3: Scraping and alerting

**Files:**
- Create: `deploy/base/observability/servicemonitor.yaml`
- Create: `deploy/base/observability/prometheusrule.yaml`
- Create: `deploy/base/observability/kustomization.yaml`
- Modify: `deploy/base/kustomization.yaml` (add the new directory)

**Interfaces:**
- Consumes: the metric names from Tasks 1 and 2, and the existing Services' `management` port name.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the management port to both Services**

**This is required, not conditional — it has been checked.** Neither Service
publishes 9090 today: `booking-service` exposes only `http` on 8081 and
`queue-gate` only `http` on 8080. A ServiceMonitor selects a port *by name* on
the Service, so without this it would match nothing and scrape nothing, silently.

Add to `ports:` in `deploy/base/booking-service/service.yaml`:

```yaml
    # Scrape target. Deliberately on the Service and NOT on the Route: adding it
    # to the Route would publish JVM internals and request timings to the
    # internet. The Route forwards only 8081; this port is reachable inside the
    # cluster, which is where Prometheus runs.
    - name: management
      port: 9090
      targetPort: 9090
```

And the same block to `deploy/base/queue-gate/service.yaml`.

Confirm both took:

```bash
oc apply -k deploy/overlays/sandbox
oc get svc booking-service queue-gate -o jsonpath='{range .items[*]}{.metadata.name}: {range .spec.ports[*]}{.name}={.port} {end}{"\n"}{end}'
```

Expected: each names both `http` and `management`.

- [ ] **Step 2: Create the ServiceMonitor**

`deploy/base/observability/servicemonitor.yaml`:

```yaml
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: rembayung
  labels:
    app.kubernetes.io/part-of: rembayung-queue
spec:
  # Both services carry app.kubernetes.io/part-of, so one ServiceMonitor covers
  # them. Scraping the management port keeps metrics off the public Route.
  selector:
    matchLabels:
      app.kubernetes.io/part-of: rembayung-queue
  endpoints:
    - port: management
      path: /actuator/prometheus
      # 15s matches the HPA's own polling. Faster would cost a database read per
      # scrape via SlotStateProvider for resolution nobody looks at.
      interval: 15s
```

- [ ] **Step 3: Create the alert rules**

`deploy/base/observability/prometheusrule.yaml`:

```yaml
---
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: rembayung
  labels:
    app.kubernetes.io/part-of: rembayung-queue
spec:
  groups:
    - name: rembayung.rules
      rules:
        # Written from a real incident: redis sat at 0 replicas for ~9 hours on
        # 2026-09-04, queue-gate fell out of its Service because its readiness
        # group includes redis, and the public Route returned 503 the whole time.
        # Nobody noticed, because nothing was watching.
        - alert: RedisDown
          expr: kube_deployment_status_replicas_ready{deployment="redis"} == 0
          for: 2m
          labels: { severity: critical }
          annotations:
            summary: Redis has no ready replicas
            description: >-
              queue-gate's readiness check includes redis, so every gate replica
              will drop out of its Service and the public Route will 503.

        - alert: ServiceHasNoEndpoints
          expr: kube_endpoint_address_available{endpoint=~"queue-gate|booking-service"} == 0
          for: 2m
          labels: { severity: critical }
          annotations:
            summary: "{{ $labels.endpoint }} has no endpoints"
            description: This is what a user experiences as a 503.

        # Should never fire in the lifetime of this system. That is exactly why
        # it is worth having: the database's ck_slots_seats CHECK constraint makes
        # the condition impossible to persist, so if this fires either the
        # invariant broke or the metric is lying.
        - alert: SlotOversold
          expr: rembayung_slot_oversold > 0
          for: 0m
          labels: { severity: critical }
          annotations:
            summary: "Slot {{ $labels.slot }} reports overselling"
            description: >-
              The central claim of this system is that this cannot happen.
              Check seats_taken against capacity in the database immediately.

        # The Phase 3 signature: 250ms RTT plus row-lock serialisation exhausting
        # the connection pool under an admission rate the database cannot take.
        - alert: BookingLatencyDegraded
          expr: >-
            histogram_quantile(0.99,
              sum by (le) (rate(http_server_requests_seconds_bucket{uri="/bookings"}[5m]))) > 10
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: Booking p99 above 10s
            description: Usually admission rate exceeding what the database can absorb.

        # Catches a CD run killed mid-rollback, which leaves the cluster pointing
        # at an image it cannot run.
        - alert: DeployStuck
          expr: kube_deployment_status_replicas_unavailable{namespace="marwanbukhori-dev"} > 0
          for: 10m
          labels: { severity: warning }
          annotations:
            summary: "{{ $labels.deployment }} has had unavailable replicas for 10m"
```

- [ ] **Step 4: Create the kustomization and wire it in**

`deploy/base/observability/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - servicemonitor.yaml
  - prometheusrule.yaml
```

Add `- observability` to the `resources:` list in `deploy/base/kustomization.yaml`.

- [ ] **Step 5: Validate the rules before applying them**

```bash
oc kustomize deploy/overlays/sandbox > /tmp/rendered.yaml && echo "kustomize builds"
oc apply --dry-run=server -f deploy/base/observability/servicemonitor.yaml
oc apply --dry-run=server -f deploy/base/observability/prometheusrule.yaml
```

Expected: all three succeed. A `PrometheusRule` with a malformed `expr` is rejected here rather than silently never firing.

- [ ] **Step 6: Apply and confirm scraping**

```bash
oc apply -k deploy/overlays/sandbox
oc get servicemonitor,prometheusrule
```

Then confirm the endpoint actually serves metrics. **The containers have no `curl`** — use a debug pod:

```bash
oc run probe --rm -i --restart=Never \
  --image=registry.access.redhat.com/ubi9/ubi-minimal --command -- \
  curl -s http://booking-service:9090/actuator/prometheus | grep rembayung_
```

Expected: the `rembayung_slot_*` series appear.

- [ ] **Step 7: Verify the private boundary still holds**

```bash
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
curl -s -o /dev/null -w '%{http_code}\n' $GATE/actuator/prometheus
```

Expected: **404**. Anything else means metrics are public — stop and fix before continuing.

- [ ] **Step 8: Commit**

```bash
git add deploy/base
git commit -m "Scrape application metrics and alert on real failure modes"
git log -1 --format=%B
```

---

## Task 4: Structured JSON logging

Splunk over unstructured text is a text search. Over JSON it is a dataset. This
task is the prerequisite that makes Task 5 worth doing.

**Files:**
- Modify: `booking-service/pom.xml`, `queue-gate/pom.xml`
- Create: `booking-service/src/main/resources/logback-spring.xml`
- Create: `queue-gate/src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: JSON log lines on stdout. Task 5 adds a second appender to the same files.

- [ ] **Step 1: Add the encoder to both poms**

```xml
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>8.0</version>
    </dependency>
```

- [ ] **Step 2: Create `booking-service/src/main/resources/logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <!-- JSON on stdout rather than a pattern layout. OpenShift collects stdout,
       and a Splunk index of JSON is queryable by field; an index of pretty text
       is a full-text search that happens to contain numbers. -->
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeMdcKeyName>slot_id</includeMdcKeyName>
      <includeMdcKeyName>booking_id</includeMdcKeyName>
      <includeMdcKeyName>idempotency_key</includeMdcKeyName>
      <includeMdcKeyName>outcome</includeMdcKeyName>
      <customFields>{"service":"booking-service"}</customFields>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

- [ ] **Step 3: Create `queue-gate/src/main/resources/logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeMdcKeyName>ticket</includeMdcKeyName>
      <includeMdcKeyName>token</includeMdcKeyName>
      <includeMdcKeyName>outcome</includeMdcKeyName>
      <customFields>{"service":"queue-gate"}</customFields>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

- [ ] **Step 4: Verify logs are valid JSON**

```bash
./mvnw -q -f queue-gate test 2>&1 | grep -m1 '^{' | python3 -m json.tool
```

Expected: parses, and shows `"service": "queue-gate"`. If nothing matches, run a service locally and check its startup output instead — the point is that a line is machine-readable, not where you found it.

- [ ] **Step 5: Confirm both suites still pass**

```bash
./mvnw -q -f booking-service test 2>&1 | tail -5
./mvnw -q -f queue-gate test 2>&1 | tail -5
```

- [ ] **Step 6: Commit**

```bash
git add booking-service queue-gate
git commit -m "Emit structured JSON logs"
git log -1 --format=%B
```

---

## Task 5: Ship logs to Splunk over HEC

**Files:**
- Modify: `booking-service/pom.xml`, `queue-gate/pom.xml`
- Modify: both `logback-spring.xml`
- Create: `deploy/base/observability/README-secrets.md`

**Interfaces:**
- Consumes: the JSON encoder from Task 4.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: STOP — the token is the repository owner's to create**

**Do not create, read, print, or store a Splunk HEC token.** Report to the controller that the owner must:

1. In Splunk Cloud: Settings → Data Inputs → HTTP Event Collector → New Token, named `rembayung`, sourcetype `_json`, index `main`.
2. Create the Secret:
   ```bash
   oc create secret generic splunk-hec \
     --from-literal=url=https://http-inputs-<tenant>.splunkcloud.com/services/collector \
     --from-literal=token=<the HEC token>
   ```

Continue to Step 2 regardless — the code is written and committed before the secret exists, and only the running integration waits.

- [ ] **Step 2: Add the Splunk repository and two dependencies to both poms**

**Two things here fail at container startup rather than at build time, so both
were verified against this machine before being written down.**

First: `splunk-library-javalogging` is **not in Maven Central.** Splunk publishes
it to their own Artifactory. Without this repository block the build fails to
resolve it. Add to both `pom.xml` files, as a sibling of `<dependencies>`:

```xml
  <repositories>
    <!-- Splunk does not publish splunk-library-javalogging to Maven Central.
         This is their official repository. Worth being conscious that it adds a
         non-Central source to the build's trust surface — acceptable for a
         first-party artifact from the vendor whose product it talks to. -->
    <repository>
      <id>splunk-artifactory</id>
      <url>https://splunk.jfrog.io/splunk/ext-releases-local</url>
    </repository>
  </repositories>
```

Second: the `<if condition="...">` in Step 3 is logback conditional processing,
which **requires Janino**. It is not on the classpath, and without it logback
fails to parse the configuration at startup — taking logging, and possibly the
container, with it.

```xml
    <dependency>
      <groupId>com.splunk.logging</groupId>
      <artifactId>splunk-library-javalogging</artifactId>
      <version>1.11.8</version>
    </dependency>
    <!-- Required by logback's <if condition="..."> in logback-spring.xml.
         Without it the config fails to parse AT STARTUP, not at build. -->
    <dependency>
      <groupId>org.codehaus.janino</groupId>
      <artifactId>janino</artifactId>
      <version>3.1.12</version>
    </dependency>
```

Both were confirmed to resolve on this machine: janino from Central, and
`splunk-library-javalogging:1.11.8` from the repository above.

- [ ] **Step 3: Add the appender to both `logback-spring.xml`**

Insert before `<root>` in each file, changing `booking-service` to `queue-gate` in the gate's copy:

```xml
  <!-- Only active when SPLUNK_HEC_URL is set, so local runs and CI are
       unaffected and no test needs a Splunk tenant. -->
  <springProfile name="!test">
    <if condition='isDefined("SPLUNK_HEC_URL")'>
      <then>
        <appender name="SPLUNK" class="com.splunk.logging.HttpEventCollectorLogbackAppender">
          <url>${SPLUNK_HEC_URL}</url>
          <token>${SPLUNK_HEC_TOKEN}</token>
          <source>rembayung</source>
          <sourcetype>_json</sourcetype>
          <!-- Batch rather than one HTTP call per line. -->
          <batch_size_count>50</batch_size_count>
          <batch_interval>2000</batch_interval>
          <disableCertificateValidation>false</disableCertificateValidation>
          <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"booking-service"}</customFields>
          </encoder>
        </appender>

        <!-- The constraint that matters. If Splunk is unreachable this must not
             block a request thread or grow the heap: bounded queue, and discard
             when full rather than apply back-pressure. Losing logs is
             acceptable; a logging integration that can stall the booking path
             would be a worse bug than having no logging at all. -->
        <appender name="SPLUNK_ASYNC" class="ch.qos.logback.classic.AsyncAppender">
          <appender-ref ref="SPLUNK"/>
          <queueSize>512</queueSize>
          <discardingThreshold>0</discardingThreshold>
          <neverBlock>true</neverBlock>
        </appender>
      </then>
    </if>
  </springProfile>
```

And inside `<root>`, guarded the same way:

```xml
    <if condition='isDefined("SPLUNK_HEC_URL")'>
      <then><appender-ref ref="SPLUNK_ASYNC"/></then>
    </if>
```

- [ ] **Step 4: Wire the Secret into both Deployments**

Add to the container `env:` in `deploy/base/booking-service/deployment.yaml` and `deploy/base/queue-gate/deployment.yaml`:

```yaml
            - name: SPLUNK_HEC_URL
              valueFrom:
                secretKeyRef: { name: splunk-hec, key: url, optional: true }
            - name: SPLUNK_HEC_TOKEN
              valueFrom:
                secretKeyRef: { name: splunk-hec, key: token, optional: true }
```

`optional: true` is deliberate: without it, the pods will not start until the Secret exists, which would take a working system down for a logging integration.

- [ ] **Step 5: Prove the failure mode is safe**

The important test is not that logs arrive — it is that the booking path survives Splunk being down.

```bash
oc set env deploy/booking-service SPLUNK_HEC_URL=https://10.255.255.1/services/collector
oc rollout status deploy/booking-service --timeout=300s

GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "  booking %{http_code} in %{time_total}s\n" \
    -XPOST $GATE/queue
done
oc logs deploy/booking-service --tail=20 | grep -ci 'outofmemory\|thread' || true
```

Expected: response times unchanged, no thread or heap errors. Then remove the override:

```bash
oc set env deploy/booking-service SPLUNK_HEC_URL-
oc rollout status deploy/booking-service --timeout=300s
```

- [ ] **Step 6: Document the secrets**

Create `deploy/base/observability/README-secrets.md` listing both Splunk keys and the three Dynatrace keys from Task 6, with the `oc create secret` commands and an explicit note that no token is ever committed or placed in a ConfigMap.

- [ ] **Step 7: Commit**

```bash
git add booking-service queue-gate deploy/base
git commit -m "Ship structured logs to Splunk over HEC"
git log -1 --format=%B
```

---

## Task 6: Dynatrace application-only APM

**Files:**
- Modify: `deploy/base/booking-service/deployment.yaml`
- Modify: `deploy/base/queue-gate/deployment.yaml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: STOP — the tokens are the repository owner's to create**

**Do not create, read, print, or store any Dynatrace token.** Report that the owner must:

1. Start a Dynatrace trial and note the tenant id (the `abc12345` in `abc12345.live.dynatrace.com`).
2. Create a **PaaS token** (Settings → Integration → Platform as a Service) and an **API token**.
3. Create the Secret:
   ```bash
   oc create secret generic dynatrace \
     --from-literal=tenant=<tenant-id> \
     --from-literal=paas-token=<PaaS token> \
     --from-literal=api-url=https://<tenant-id>.live.dynatrace.com/api
   ```

Continue to Step 2 — the manifests are written before the secret exists.

- [ ] **Step 2: Add the initContainer and volume to both Deployments**

Full-stack OneAgent needs `privileged`, which this sandbox does not offer — `restricted-v2` is the only SCC available. Application-only needs none of it. Add to each Deployment's `spec.template.spec`:

```yaml
      initContainers:
        # Downloads the Java agent into a shared emptyDir. No privileged access,
        # no DaemonSet, no operator — all of which restricted-v2 would refuse.
        # This means APM and traces only; host and infrastructure metrics are not
        # available here, and claiming otherwise would be false.
        - name: dynatrace-agent
          image: registry.access.redhat.com/ubi9/ubi-minimal
          command:
            - sh
            - -c
            - |
              set -e
              curl -fsSL -o /dynatrace/agent.zip \
                "${DT_API_URL}/v1/deployment/installer/agent/unix/paas/latest?Api-Token=${DT_PAAS_TOKEN}&flavor=default&arch=x86&bitness=64"
              mkdir -p /dynatrace/agent
              microdnf install -y unzip >/dev/null 2>&1
              unzip -q /dynatrace/agent.zip -d /dynatrace/agent
              rm -f /dynatrace/agent.zip
          env:
            - name: DT_API_URL
              valueFrom:
                secretKeyRef: { name: dynatrace, key: api-url, optional: true }
            - name: DT_PAAS_TOKEN
              valueFrom:
                secretKeyRef: { name: dynatrace, key: paas-token, optional: true }
          volumeMounts:
            - name: dynatrace-agent
              mountPath: /dynatrace
```

Add to `volumes:`:

```yaml
        - name: dynatrace-agent
          emptyDir: {}
```

Add to the application container's `volumeMounts:`:

```yaml
            - name: dynatrace-agent
              mountPath: /dynatrace
```

And to its `env:`:

```yaml
            - name: JAVA_TOOL_OPTIONS
              value: "-agentpath:/dynatrace/agent/agent/lib64/liboneagent.so"
```

- [ ] **Step 3: Verify the agent path before trusting it**

The path inside the archive varies by version, and a wrong `-agentpath` makes the JVM refuse to start — which would take the service down.

```bash
oc get pods -l app=booking-service
oc logs deploy/booking-service -c dynatrace-agent --tail=20
oc exec deploy/booking-service -- find /dynatrace -name 'liboneagent.so' 2>/dev/null
```

If the path differs from Step 2, correct `JAVA_TOOL_OPTIONS` to match what `find` reports.

- [ ] **Step 4: Confirm the application still starts**

```bash
oc rollout status deploy/booking-service --timeout=300s
oc get pods
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
curl -s -XPOST $GATE/queue | head -c 100
```

Expected: pods Ready, gate answering. **If the JVM fails to start, the agentpath is wrong** — fix it rather than removing the instrumentation, and if you cannot, report BLOCKED with the JVM error rather than leaving a broken Deployment.

- [ ] **Step 5: Commit**

```bash
git add deploy/base
git commit -m "Attach the Dynatrace agent in application-only mode"
git log -1 --format=%B
```

---

## Task 7: Document it

**Files:**
- Create: `docs/notes/08-observability.md`
- Modify: `docs/notes/README.md`
- Modify: `deploy/README.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: nothing.

- [ ] **Step 1: Read the notes you are matching**

Read `docs/notes/06-continuous-integration.md` and `docs/notes/07-continuous-delivery.md` first. They explain *why*, especially where a default was rejected, rather than narrating file contents. Match that voice.

- [ ] **Step 2: Write note 08**

It must cover:

- **Why three tools rather than one**, split by job: Prometheus for metrics and alerts, Dynatrace for traces, Splunk for logs. State plainly that three tools doing one job would be worse than one.
- **The SCC evidence.** `restricted-v2` only, no `privileged`, no `hostaccess`, `get nodes` denied. This makes OneAgent full-stack and the Splunk forwarder impossible, and application-level the only option. Say what that costs: no infrastructure monitoring, and do not imply otherwise.
- **The quota evidence.** Self-hosted Splunk Enterprise wants ~2 CPU / 4GB against a 3000m budget where the spike demo needs 1900m — the same constraint that removed AAP and Jenkins.
- **Why Prometheus is the backbone and not a fourth wheel:** both trials expire (Dynatrace ~2026-09-19, Splunk ~2026-09-18), and the alerting that keeps the demo alive must outlive them.
- **Why `rembayung_slot_oversold` matters**, and that it is deliberately redundant with the `ck_slots_seats` CHECK constraint.
- **The one-computation rule** and why Phase 8 inherits `SlotStateProvider` and `QueueStateProvider` rather than writing its own queries.
- **The Splunk failure policy** — async, bounded, drop on full — and why losing logs beats stalling the booking path.
- **The real incident** that produced `RedisDown`: nine hours of 503 that nothing detected.
- Record the actual measured outcome of Task 5 Step 5 (the unreachable-Splunk test), not an assertion that it should work.

- [ ] **Step 3: Add the README row**

Add a row for note 08 to `docs/notes/README.md`, matching the existing table format exactly.

- [ ] **Step 4: Add a deploy/README section**

Add an "Observability" section covering: where metrics are (management port, not public), how to view them in the OpenShift console (Developer → Observe), the alert list, the Dynatrace and Splunk dashboard URLs, and that both trials expire on the dates above.

- [ ] **Step 5: Commit**

```bash
git add docs deploy/README.md
git commit -m "Document the observability stack"
git log -1 --format=%B
```

---

## Self-Review

**1. Spec coverage.** §2's tool rationale → Task 7. §3's metrics → Tasks 1 and 2. §4's one-computation rule → `SlotStateProvider` (Task 1) and `QueueStateProvider` (Task 2). §5 Dynatrace → Task 6. §6 Splunk → Tasks 4 and 5. §7 alerting → Task 3. §8's Phase 8 inheritance → the Interfaces blocks of Tasks 1 and 2, which name the exact types Phase 8 consumes. §9 secrets → the STOP steps in Tasks 5 and 6, plus `README-secrets.md`. §10 testing → the test steps throughout, including the private-boundary check in Task 3 Step 7 and the Splunk-down check in Task 5 Step 5.

**2. Placeholder scan.** Every code step carries the file's real content. Two steps deliberately verify before depending on a value rather than asserting one: Task 1 Step 1 (the registry artifact, because Boot 4 renames have cost this project hours twice) and Task 6 Step 3 (the `liboneagent.so` path, because a wrong `-agentpath` stops the JVM booting).

**3. Type consistency.** `SlotState` is `(slotId, capacity, seatsTaken, remaining, oversold)` in Task 1 and referenced with those accessors in `BookingMetrics`. `QueueState` is `(ticketsIssued, admitted, waiting, ticketCap)` in Task 2 and used with those accessors in `GateMetrics`. Metric names appear identically in Tasks 1, 2 and 3 — `rembayung_slot_oversold` is spelled the same in the gauge and in the `SlotOversold` alert's `expr`.

**Two gaps named rather than hidden.** Tasks 5 and 6 cannot complete unattended: both need credentials only the repository owner may create. Both are written so the code and manifests commit first, so only the live integration waits. And `optional: true` on every secret reference means a missing Secret degrades to "no Dynatrace, no Splunk" rather than pods that will not start.

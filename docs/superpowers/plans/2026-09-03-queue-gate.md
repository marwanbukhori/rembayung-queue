# Queue Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a virtual waiting room in front of the Phase 1 booking domain, and give the whole system a real HTTP surface that can be driven by `curl` and by k6.

**Architecture:** A second standalone Spring Boot service, `queue-gate`, is the only public entry point. It issues tickets from a Redis counter, derives admission arithmetically from the clock (no scheduler, no lock, no coordination between replicas), validates single-use admission tokens with `GETDEL`, and proxies admitted bookings to `booking-service` over internal HTTP. `booking-service` gains thin REST controllers and nothing else — its domain code and all 25 Phase 1 tests are untouched.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Maven, Spring Data Redis, Spring Web (`RestClient`), Redis 7, Testcontainers, WireMock, JUnit 5, AssertJ, k6, Docker Compose

**Spec:** `docs/superpowers/specs/2026-09-03-queue-gate-design.md`
**Parent spec:** `docs/superpowers/specs/2026-09-02-rembayung-booking-queue-design.md`

## Global Constraints

- **Java 25**, Spring Boot **4.1.1**, Maven. Gate base package `dev.marwan.gate`; booking-service base package remains `dev.marwan.booking`.
- **`queue-gate/` is a standalone Maven project**, a sibling of `booking-service/`, not a module of a parent POM. They deploy independently.
- **Phase 1's 25 tests must keep passing unmodified.** If a change requires editing a Phase 1 test, the change is wrong. Verify with `mvn verify` in `booking-service/` after every task that touches it.
- **`booking-service` must not gain a Redis dependency** and must remain unaware that a queue exists.
- **Never substitute H2 or Postgres for Oracle**, and never substitute an in-memory Redis for real Redis. Container images: `gvenzl/oracle-free:23-slim-faststart` and `redis:7-alpine`.
- **`java.time.Clock` must be an injected bean.** Never call `Instant.now()` inline in gate production code — admission derives from `now`, and tests must control it.
- Admit rate: **200/sec**. Drop seats: **250**. Ticket cap: **250 tickets** (tickets, not seats). Admission window: **5 minutes**. Ticket TTL: **30 minutes**.
- **Commit messages must contain no AI attribution.** No `Co-Authored-By`, no `Claude-Session:`, no `claude.ai` URL, no "Generated with", no assistant mention. This is a hard standing rule of the repository owner and has been violated once already, requiring a history rewrite. After each commit run `git log -1 --format=%B` and confirm the body is only the subject line.
- Every Maven command must first `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` and `export PATH="$JAVA_HOME/bin:$PATH"`.
- TDD throughout: failing test first, verify it fails for the expected reason, minimal implementation, verify it passes, commit.

### Boot 4 packaging warning

Phase 1 hit five defects caused by the plan being written to Spring Boot 3 conventions while the project runs 4.1.1 — Testcontainers 2.x renamed its artifacts and moved `OracleContainer` to `org.testcontainers.oracle`, and `FlywayAutoConfiguration` moved out of `spring-boot-autoconfigure` into a separate `spring-boot-flyway` artifact (Flyway silently never ran until it was added).

**If something here does not compile or does not take effect, suspect packaging before suspecting yourself.** Verify against the resolved jars, report what you find, and add a missing Spring Boot module dependency if one proves necessary — as its own commit.

---

## File Structure

```
rembayung-queue/
├── booking-service/                        (exists; Phase 2 adds a REST layer only)
│   ├── pom.xml                             + spring-boot-starter-web
│   └── src/
│       ├── main/java/dev/marwan/booking/
│       │   ├── api/
│       │   │   ├── SlotSoldOutException.java      + getters
│       │   │   ├── SlotNotFoundException.java     + getter
│       │   │   ├── BookingNotFoundException.java  + getter
│       │   │   └── ApiError.java                  new
│       │   └── web/
│       │       ├── BookingController.java         new
│       │       └── RestExceptionHandler.java      new
│       └── test/java/dev/marwan/booking/
│           └── BookingControllerTest.java         new
│
├── queue-gate/                             (new, standalone)
│   ├── pom.xml
│   └── src/
│       ├── main/java/dev/marwan/gate/
│       │   ├── QueueGateApplication.java
│       │   ├── config/
│       │   │   ├── DropProperties.java      drop window, cap, rate
│       │   │   └── ClockConfig.java         the injected Clock bean
│       │   ├── queue/
│       │   │   ├── Admission.java           pure time arithmetic
│       │   │   ├── QueueService.java        join + position
│       │   │   ├── AdmissionService.java    validate + consume token
│       │   │   ├── JoinResult.java
│       │   │   ├── PositionView.java
│       │   │   ├── DropNotOpenException.java
│       │   │   ├── DropClosedException.java
│       │   │   ├── SoldOutException.java
│       │   │   └── TokenRejectedException.java
│       │   └── web/
│       │       ├── QueueController.java     POST /queue, GET /queue/{token}
│       │       ├── BookingProxyController.java
│       │       ├── BookingClient.java       RestClient wrapper
│       │       ├── GateApiError.java
│       │       └── GateExceptionHandler.java
│       └── test/java/dev/marwan/gate/
│           ├── RedisTestBase.java           shared Redis container + TestClock
│           ├── TestClock.java               settable Clock
│           ├── AdmissionTest.java           pure arithmetic, no container
│           ├── QueueServiceTest.java
│           ├── AdmissionServiceTest.java
│           ├── QueueControllerTest.java
│           └── BookingProxyControllerTest.java   WireMock
│
├── docker-compose.yml                      new
└── loadtest/
    └── drop.js                             new (k6)
```

`Admission` is deliberately a separate class with no Spring or Redis dependency, so the admission arithmetic — the part most likely to be wrong and most important to explain — is testable in microseconds without a container.

---

## Task 1: REST layer for booking-service

Gives the Phase 1 domain an HTTP surface. This is the only task that touches `booking-service`, and it adds no logic — controllers translate HTTP to the existing service calls, and the exception getters exist so error bodies can be structured rather than parsed from prose.

**Files:**
- Modify: `booking-service/pom.xml`
- Modify: `booking-service/src/main/java/dev/marwan/booking/api/SlotSoldOutException.java`
- Modify: `booking-service/src/main/java/dev/marwan/booking/api/SlotNotFoundException.java`
- Modify: `booking-service/src/main/java/dev/marwan/booking/api/BookingNotFoundException.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/api/ApiError.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/web/BookingController.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/web/RestExceptionHandler.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/BookingControllerTest.java`

**Interfaces:**
- Consumes: `BookingService.book(BookingRequest)`, `BookingService.confirmDeposit(Long)`, `BookingRequest`, `BookingResult`, `BookingStatus` — all from Phase 1, unchanged.
- Produces: `POST /bookings` and `POST /bookings/{id}/deposit`; `ApiError(String reason, Map<String,Object> details)`; exception getters `SlotSoldOutException.getSlotId()/getRequested()/getRemaining()`, `SlotNotFoundException.getSlotId()`, `BookingNotFoundException.getBookingId()`.

- [ ] **Step 1: Add the web starter**

In `booking-service/pom.xml`, add inside `<dependencies>`, immediately after the `spring-boot-starter-data-jpa` block:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

`booking-service/src/test/java/dev/marwan/booking/BookingControllerTest.java`:

```java
package dev.marwan.booking;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookingControllerTest extends OracleTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private SlotRepository slotRepository;

    private Long seedSlot(int capacity) {
        return slotRepository.save(
                new Slot(LocalDate.of(2027, 3, 1),
                         String.valueOf(System.nanoTime() % 100000), capacity)).getId();
    }

    @Test
    void bookingReturnsCreatedWithPendingDeposit() throws Exception {
        Long slotId = seedSlot(250);

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60123456789","partySize":2,
                             "idempotencyKey":"http-key-1"}
                            """.formatted(slotId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_DEPOSIT"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
    }

    @Test
    void soldOutSlotReturnsConflictWithStructuredDetails() throws Exception {
        Long slotId = seedSlot(2);

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60111111111","partySize":2,
                             "idempotencyKey":"http-key-2"}
                            """.formatted(slotId)))
                .andExpect(status().isCreated());

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60122222222","partySize":1,
                             "idempotencyKey":"http-key-3"}
                            """.formatted(slotId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SLOT_SOLD_OUT"))
                .andExpect(jsonPath("$.details.slotId").value(slotId))
                .andExpect(jsonPath("$.details.requested").value(1))
                .andExpect(jsonPath("$.details.remaining").value(0));
    }

    @Test
    void unknownSlotReturnsNotFound() throws Exception {
        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":999999,"phone":"+60123456789","partySize":2,
                             "idempotencyKey":"http-key-4"}
                            """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("SLOT_NOT_FOUND"))
                .andExpect(jsonPath("$.details.slotId").value(999999));
    }

    @Test
    void depositConfirmsThenRejectsASecondAttempt() throws Exception {
        Long slotId = seedSlot(250);

        String body = mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slotId":%d,"phone":"+60123456789","partySize":2,
                             "idempotencyKey":"http-key-5"}
                            """.formatted(slotId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long bookingId = com.jayway.jsonpath.JsonPath.parse(body).read("$.bookingId", Integer.class).longValue();

        mvc.perform(post("/bookings/" + bookingId + "/deposit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(post("/bookings/" + bookingId + "/deposit"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("BOOKING_NOT_PENDING"));
    }

    @Test
    void depositOnUnknownBookingReturnsNotFound() throws Exception {
        mvc.perform(post("/bookings/999999/deposit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("BOOKING_NOT_FOUND"))
                .andExpect(jsonPath("$.details.bookingId").value(999999));
    }
}
```

This test uses only real collaborators against real Oracle — no mocks anywhere. `JsonPath` comes from `spring-boot-starter-test`, which bundles `json-path`, so no extra dependency is needed. Keep imports minimal; test output must be pristine.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd booking-service && mvn test -Dtest=BookingControllerTest`
Expected: FAIL — compilation error, `BookingController` does not exist and the exception getters are undefined.

- [ ] **Step 4: Add getters to the three exceptions**

`SlotSoldOutException.java`:

```java
package dev.marwan.booking.api;

public class SlotSoldOutException extends RuntimeException {

    private final Long slotId;
    private final int requested;
    private final int remaining;

    public SlotSoldOutException(Long slotId, int requested, int remaining) {
        super("Slot " + slotId + " cannot seat " + requested + "; " + remaining + " remaining");
        this.slotId = slotId;
        this.requested = requested;
        this.remaining = remaining;
    }

    public Long getSlotId() { return slotId; }
    public int getRequested() { return requested; }
    public int getRemaining() { return remaining; }
}
```

`SlotNotFoundException.java`:

```java
package dev.marwan.booking.api;

public class SlotNotFoundException extends RuntimeException {

    private final Long slotId;

    public SlotNotFoundException(Long slotId) {
        super("No slot with id " + slotId);
        this.slotId = slotId;
    }

    public Long getSlotId() { return slotId; }
}
```

`BookingNotFoundException.java`:

```java
package dev.marwan.booking.api;

public class BookingNotFoundException extends RuntimeException {

    private final Long bookingId;

    public BookingNotFoundException(Long bookingId) {
        super("No booking with id " + bookingId);
        this.bookingId = bookingId;
    }

    public Long getBookingId() { return bookingId; }
}
```

- [ ] **Step 5: Write the error body, controller and handler**

`booking-service/src/main/java/dev/marwan/booking/api/ApiError.java`:

```java
package dev.marwan.booking.api;

import java.util.Map;

public record ApiError(String reason, Map<String, Object> details) { }
```

`booking-service/src/main/java/dev/marwan/booking/web/BookingController.java`:

```java
package dev.marwan.booking.web;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResult create(@RequestBody BookingRequest request) {
        return bookingService.book(request);
    }

    @PostMapping("/{id}/deposit")
    public BookingResult deposit(@PathVariable Long id) {
        return bookingService.confirmDeposit(id);
    }
}
```

`booking-service/src/main/java/dev/marwan/booking/web/RestExceptionHandler.java`:

```java
package dev.marwan.booking.web;

import dev.marwan.booking.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(SlotSoldOutException.class)
    public ResponseEntity<ApiError> soldOut(SlotSoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "SLOT_SOLD_OUT",
                Map.of("slotId", e.getSlotId(),
                       "requested", e.getRequested(),
                       "remaining", e.getRemaining())));
    }

    @ExceptionHandler(SlotNotFoundException.class)
    public ResponseEntity<ApiError> slotMissing(SlotNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                "SLOT_NOT_FOUND", Map.of("slotId", e.getSlotId())));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ApiError> bookingMissing(BookingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                "BOOKING_NOT_FOUND", Map.of("bookingId", e.getBookingId())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> notPending(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "BOOKING_NOT_PENDING", Map.of("message", e.getMessage())));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=BookingControllerTest`
Expected: PASS, 5 tests plus the inherited `oracleContainerIsReachable`.

- [ ] **Step 7: Verify Phase 1 is undisturbed**

Run: `mvn verify`
Expected: PASS. The count rises from 25 to 31 (5 new tests + 1 inherited in the new class). **No existing test may change or fail.** Adding `spring-boot-starter-web` makes `@SpringBootTest` start a mock servlet environment; if any Phase 1 test breaks, stop and report rather than editing it.

- [ ] **Step 8: Commit**

```bash
git add booking-service/
git commit -m "Add REST layer over the booking domain with structured errors"
```

---

## Task 2: queue-gate skeleton with real Redis in tests

Scaffolding plus the shared test base. Carries the same "does the container actually run" risk Phase 1's Task 1 carried, so it is proven first.

**Files:**
- Create: `queue-gate/pom.xml`
- Create: `queue-gate/src/main/java/dev/marwan/gate/QueueGateApplication.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/config/ClockConfig.java`
- Create: `queue-gate/src/main/resources/application.yml`
- Create: `queue-gate/src/test/resources/application-test.yml`
- Test: `queue-gate/src/test/java/dev/marwan/gate/TestClock.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/RedisTestBase.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/RedisReachableTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RedisTestBase` — an abstract `@SpringBootTest` base exposing a shared Redis container and a `TestClock`; every later gate test extends it. `TestClock.setNow(Instant)` and `TestClock.advance(Duration)`.

- [ ] **Step 1: Create `queue-gate/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>dev.marwan</groupId>
  <artifactId>queue-gate</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <java.version>25</java.version>
    <wiremock.version>3.9.1</wiremock.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.wiremock</groupId>
      <artifactId>wiremock-standalone</artifactId>
      <version>${wiremock.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Redis runs via a plain `GenericContainer` rather than a dedicated Testcontainers module, so no module artifact needs guessing. `testcontainers-junit-jupiter` pulls in the core `testcontainers` artifact transitively.

- [ ] **Step 2: Create the application, clock bean and config**

`queue-gate/src/main/java/dev/marwan/gate/QueueGateApplication.java`:

```java
package dev.marwan.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QueueGateApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueGateApplication.class, args);
    }
}
```

`queue-gate/src/main/java/dev/marwan/gate/config/ClockConfig.java`:

```java
package dev.marwan.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Admission is derived from the current time, so the clock is injected
     * rather than read statically. Tests replace this with a settable clock;
     * production code must never call Instant.now() directly.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

`queue-gate/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: queue-gate
  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8080

drop:
  opens-at: 2026-09-03T13:00:00Z
  closes-at: 2026-09-03T13:30:00Z
  seats: 250
  ticket-cap: 250
  admit-rate: 200
  admission-window: PT5M
  ticket-ttl: PT30M

booking-service:
  base-url: http://localhost:8081
```

`queue-gate/src/test/resources/application-test.yml`:

```yaml
logging:
  level:
    org.testcontainers: INFO
booking-service:
  base-url: http://localhost:0
```

- [ ] **Step 3: Write the test clock and Redis base**

`queue-gate/src/test/java/dev/marwan/gate/TestClock.java`:

```java
package dev.marwan.gate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A Clock whose instant can be set and advanced, so time-dependent behaviour is deterministic. */
public class TestClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public TestClock(Instant now) {
        this(now, ZoneId.of("UTC"));
    }

    private TestClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void setNow(Instant instant) { this.now = instant; }

    public void advance(Duration duration) { this.now = this.now.plus(duration); }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId z) { return new TestClock(now, z); }

    @Override public Instant instant() { return now; }
}
```

`queue-gate/src/test/java/dev/marwan/gate/RedisTestBase.java`:

```java
package dev.marwan.gate;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;

@SpringBootTest
@ActiveProfiles("test")
public abstract class RedisTestBase {

    /** Drop opens at a fixed instant in every test, so arithmetic is predictable. */
    public static final Instant OPENS_AT = Instant.parse("2026-09-03T13:00:00Z");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("drop.opens-at", () -> OPENS_AT.toString());
        registry.add("drop.closes-at", () -> OPENS_AT.plusSeconds(1800).toString());
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return new TestClock(OPENS_AT);
        }
    }

    @Autowired protected StringRedisTemplate redis;
    @Autowired protected Clock clock;

    protected TestClock clock() {
        return (TestClock) clock;
    }

    /**
     * Redis reuse keeps the container alive between runs, so every test must
     * start from an empty keyspace or fixed ticket numbers would collide.
     */
    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        clock().setNow(OPENS_AT);
    }
}
```

The singleton `static { REDIS.start(); }` pattern is used rather than `@Container`, for the same reason Phase 1 adopted it: `@Container` gives per-class container lifecycle while `@SpringBootTest` caches contexts across classes, and the two disagree.

`queue-gate/src/test/java/dev/marwan/gate/RedisReachableTest.java`:

```java
package dev.marwan.gate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisReachableTest extends RedisTestBase {

    @Test
    void redisRespondsAndTheClockIsControllable() {
        redis.opsForValue().set("smoke", "ok");
        assertThat(redis.opsForValue().get("smoke")).isEqualTo("ok");
        assertThat(clock.instant()).isEqualTo(OPENS_AT);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd queue-gate && mvn test -Dtest=RedisReachableTest`
Expected: PASS. Redis starts in a few seconds. If the container fails to start, stop and report — everything downstream depends on it.

- [ ] **Step 5: Commit**

```bash
git add queue-gate/
git commit -m "Add queue-gate skeleton with Redis Testcontainers base"
```

---

## Task 3: Admission arithmetic

The heart of the design, isolated from Spring and Redis so it can be tested exhaustively in microseconds. Everything else in the gate depends on this being right.

**Files:**
- Create: `queue-gate/src/main/java/dev/marwan/gate/config/DropProperties.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/Admission.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/AdmissionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `DropProperties(Instant opensAt, Instant closesAt, int seats, int ticketCap, int admitRate, Duration admissionWindow, Duration ticketTtl)`, a `@ConfigurationProperties("drop")` record.
  - `Admission.admittedBy(Instant now, Instant opensAt, int rate)` → `long`
  - `Admission.turnAt(long ticket, Instant opensAt, int rate)` → `Instant`
  - `Admission.isAdmitted(long ticket, Instant now, Instant opensAt, int rate)` → `boolean`
  - `Admission.hasExpired(long ticket, Instant now, Instant opensAt, int rate, Duration window)` → `boolean`

- [ ] **Step 1: Write the failing test**

`queue-gate/src/test/java/dev/marwan/gate/AdmissionTest.java`:

```java
package dev.marwan.gate;

import dev.marwan.gate.queue.Admission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionTest {

    private static final Instant OPENS = Instant.parse("2026-09-03T13:00:00Z");
    private static final int RATE = 200;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void nobodyIsAdmittedBeforeTheDropOpens() {
        assertThat(Admission.admittedBy(OPENS.minusSeconds(1), OPENS, RATE)).isZero();
        assertThat(Admission.admittedBy(OPENS.minusSeconds(3600), OPENS, RATE)).isZero();
    }

    @Test
    void admissionAdvancesAtTheConfiguredRate() {
        assertThat(Admission.admittedBy(OPENS, OPENS, RATE)).isZero();
        assertThat(Admission.admittedBy(OPENS.plusSeconds(1), OPENS, RATE)).isEqualTo(200);
        assertThat(Admission.admittedBy(OPENS.plusSeconds(10), OPENS, RATE)).isEqualTo(2000);
    }

    @Test
    void admissionAdvancesWithinASecond() {
        assertThat(Admission.admittedBy(OPENS.plusMillis(500), OPENS, RATE)).isEqualTo(100);
    }

    @Test
    void aTicketIsAdmittedOnceAdmissionReachesIt() {
        assertThat(Admission.isAdmitted(200, OPENS.plusMillis(999), OPENS, RATE)).isFalse();
        assertThat(Admission.isAdmitted(200, OPENS.plusSeconds(1), OPENS, RATE)).isTrue();
        assertThat(Admission.isAdmitted(201, OPENS.plusSeconds(1), OPENS, RATE)).isFalse();
    }

    @Test
    void turnAtIsTheInstantATicketBecomesAdmitted() {
        assertThat(Admission.turnAt(200, OPENS, RATE)).isEqualTo(OPENS.plusSeconds(1));
        assertThat(Admission.turnAt(100, OPENS, RATE)).isEqualTo(OPENS.plusMillis(500));
    }

    @Test
    void theAdmissionWindowExpiresFiveMinutesAfterTheTurn() {
        Instant turn = Admission.turnAt(200, OPENS, RATE);
        assertThat(Admission.hasExpired(200, turn, OPENS, RATE, WINDOW)).isFalse();
        assertThat(Admission.hasExpired(200, turn.plus(WINDOW), OPENS, RATE, WINDOW)).isFalse();
        assertThat(Admission.hasExpired(200, turn.plus(WINDOW).plusSeconds(1), OPENS, RATE, WINDOW)).isTrue();
    }

    @Test
    void admissionIsIdenticalForEveryCaller() {
        // The property that removes the need for coordination between replicas:
        // the same inputs always produce the same answer, with no shared state.
        Instant now = OPENS.plusMillis(1234);
        long first = Admission.admittedBy(now, OPENS, RATE);
        long second = Admission.admittedBy(now, OPENS, RATE);
        assertThat(first).isEqualTo(second).isEqualTo(246);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AdmissionTest`
Expected: FAIL — compilation error, `Admission` does not exist.

- [ ] **Step 3: Write the implementation**

`queue-gate/src/main/java/dev/marwan/gate/config/DropProperties.java`:

```java
package dev.marwan.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Instant;

@ConfigurationProperties(prefix = "drop")
public record DropProperties(
        Instant opensAt,
        Instant closesAt,
        int seats,
        int ticketCap,
        int admitRate,
        Duration admissionWindow,
        Duration ticketTtl) { }
```

`queue-gate/src/main/java/dev/marwan/gate/queue/Admission.java`:

```java
package dev.marwan.gate.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * Admission is a pure function of time.
 *
 * There is no counter, no scheduler and no lock. Every replica of the gate
 * computes the identical answer from the clock, because this is arithmetic
 * rather than shared state — which is why admission needs no coordination
 * between replicas. A scheduled counter would advance at rate x replicaCount.
 */
public final class Admission {

    private Admission() { }

    /** How many tickets have been admitted by {@code now}. Zero before the drop opens. */
    public static long admittedBy(Instant now, Instant opensAt, int rate) {
        if (now.isBefore(opensAt)) {
            return 0;
        }
        long elapsedMillis = Duration.between(opensAt, now).toMillis();
        return Math.floorDiv(elapsedMillis * rate, 1000L);
    }

    /** The instant at which {@code ticket} becomes admitted. */
    public static Instant turnAt(long ticket, Instant opensAt, int rate) {
        long millis = Math.floorDiv(ticket * 1000L, (long) rate);
        return opensAt.plusMillis(millis);
    }

    public static boolean isAdmitted(long ticket, Instant now, Instant opensAt, int rate) {
        return ticket <= admittedBy(now, opensAt, rate);
    }

    /** True once the holder's five-minute window, measured from their turn, has lapsed. */
    public static boolean hasExpired(long ticket, Instant now, Instant opensAt,
                                     int rate, Duration window) {
        return now.isAfter(turnAt(ticket, opensAt, rate).plus(window));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=AdmissionTest`
Expected: PASS, 7 tests, in milliseconds — no container involved.

- [ ] **Step 5: Commit**

```bash
git add queue-gate/
git commit -m "Derive queue admission arithmetically from the clock"
```

---

## Task 4: Joining the queue

**Files:**
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/JoinResult.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/DropNotOpenException.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/DropClosedException.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/SoldOutException.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/QueueService.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/QueueServiceTest.java`

**Interfaces:**
- Consumes: `Admission`, `DropProperties`, `StringRedisTemplate`, `Clock`.
- Produces:
  - `record JoinResult(String token, long ticket, long position, double etaSeconds, boolean admitted)`
  - `QueueService.join()` → `JoinResult`, throwing `DropNotOpenException`, `DropClosedException`, `SoldOutException`
  - `DropNotOpenException.getSecondsUntilOpen()` → `long`

- [ ] **Step 1: Write the failing test**

`queue-gate/src/test/java/dev/marwan/gate/QueueServiceTest.java`:

```java
package dev.marwan.gate;

import dev.marwan.gate.queue.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueServiceTest extends RedisTestBase {

    @Autowired private QueueService queueService;

    @Test
    void joiningBeforeTheDropOpensIsRejectedWithACountdown() {
        clock().setNow(OPENS_AT.minusSeconds(120));

        assertThatThrownBy(queueService::join)
                .isInstanceOf(DropNotOpenException.class)
                .satisfies(e -> assertThat(((DropNotOpenException) e).getSecondsUntilOpen())
                        .isEqualTo(120));
    }

    @Test
    void joiningAfterTheDropClosesIsRejected() {
        clock().setNow(OPENS_AT.plus(Duration.ofMinutes(31)));

        assertThatThrownBy(queueService::join).isInstanceOf(DropClosedException.class);
    }

    @Test
    void ticketsAreIssuedInOrderFromOne() {
        JoinResult first = queueService.join();
        JoinResult second = queueService.join();

        assertThat(first.ticket()).isEqualTo(1);
        assertThat(second.ticket()).isEqualTo(2);
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void positionAndEtaReflectHowFarAdmissionHasAdvanced() {
        for (int i = 0; i < 399; i++) {
            queueService.join();
        }
        clock().advance(Duration.ofSeconds(1));   // 200 admitted

        JoinResult result = queueService.join();  // ticket 400

        assertThat(result.ticket()).isEqualTo(400);
        assertThat(result.position()).isEqualTo(200);
        assertThat(result.etaSeconds()).isEqualTo(1.0);
        assertThat(result.admitted()).isFalse();
    }

    @Test
    void anAlreadyAdmittedTicketReportsPositionZero() {
        clock().advance(Duration.ofSeconds(1));   // 200 admitted before anyone joins

        JoinResult result = queueService.join();  // ticket 1

        assertThat(result.position()).isZero();
        assertThat(result.admitted()).isTrue();
    }

    @Test
    void ticketsBeyondTheCapAreSoldOut() {
        for (int i = 0; i < 250; i++) {
            queueService.join();
        }

        assertThatThrownBy(queueService::join).isInstanceOf(SoldOutException.class);
    }

    @Test
    void theTicketIsStoredInRedisUnderTheToken() {
        JoinResult result = queueService.join();

        assertThat(redis.opsForValue().get("admit:" + result.token()))
                .isEqualTo(String.valueOf(result.ticket()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=QueueServiceTest`
Expected: FAIL — compilation error, `QueueService` does not exist.

- [ ] **Step 3: Write the implementation**

`queue-gate/src/main/java/dev/marwan/gate/queue/JoinResult.java`:

```java
package dev.marwan.gate.queue;

public record JoinResult(String token, long ticket, long position,
                         double etaSeconds, boolean admitted) { }
```

`queue-gate/src/main/java/dev/marwan/gate/queue/DropNotOpenException.java`:

```java
package dev.marwan.gate.queue;

public class DropNotOpenException extends RuntimeException {

    private final long secondsUntilOpen;

    public DropNotOpenException(long secondsUntilOpen) {
        super("Drop opens in " + secondsUntilOpen + " seconds");
        this.secondsUntilOpen = secondsUntilOpen;
    }

    public long getSecondsUntilOpen() { return secondsUntilOpen; }
}
```

`queue-gate/src/main/java/dev/marwan/gate/queue/DropClosedException.java`:

```java
package dev.marwan.gate.queue;

public class DropClosedException extends RuntimeException {
    public DropClosedException() {
        super("The drop has closed");
    }
}
```

`queue-gate/src/main/java/dev/marwan/gate/queue/SoldOutException.java`:

```java
package dev.marwan.gate.queue;

public class SoldOutException extends RuntimeException {
    public SoldOutException() {
        super("The drop is sold out");
    }
}
```

`queue-gate/src/main/java/dev/marwan/gate/queue/QueueService.java`:

```java
package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class QueueService {

    static final String TICKET_COUNTER = "queue:ticket";
    static final String ADMIT_PREFIX = "admit:";

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    public QueueService(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    public JoinResult join() {
        Instant now = clock.instant();

        if (now.isBefore(drop.opensAt())) {
            throw new DropNotOpenException(Duration.between(now, drop.opensAt()).toSeconds());
        }
        if (now.isAfter(drop.closesAt())) {
            throw new DropClosedException();
        }

        Long ticket = redis.opsForValue().increment(TICKET_COUNTER);
        if (ticket == null || ticket > drop.ticketCap()) {
            throw new SoldOutException();
        }

        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(ADMIT_PREFIX + token, String.valueOf(ticket), drop.ticketTtl());

        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        long position = Math.max(0, ticket - admitted);

        return new JoinResult(token, ticket, position,
                (double) position / drop.admitRate(),
                position == 0);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=QueueServiceTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add queue-gate/
git commit -m "Issue queue tickets with a drop window and inventory cap"
```

---

## Task 5: Polling position and validating admission tokens

`AdmissionService` owns both reading a token's position and consuming it. They live together because both are operations on `admit:{token}` and share the same expiry rules.

**Files:**
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/PositionView.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/TokenRejectedException.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/AdmissionService.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/AdmissionServiceTest.java`

**Interfaces:**
- Consumes: `Admission`, `DropProperties`, `StringRedisTemplate`, `Clock`, `QueueService.join()`.
- Produces:
  - `record PositionView(long position, boolean admitted, long expiresInSeconds)`
  - `AdmissionService.position(String token)` → `Optional<PositionView>` (empty when unknown or expired in Redis)
  - `AdmissionService.consume(String token)` → `void`, throwing `TokenRejectedException`
  - `TokenRejectedException.getReason()` → `String`, one of `TOKEN_INVALID`, `TOKEN_EXPIRED`, `TOKEN_NOT_YET_ADMITTED`

- [ ] **Step 1: Write the failing test**

`queue-gate/src/test/java/dev/marwan/gate/AdmissionServiceTest.java`:

```java
package dev.marwan.gate;

import dev.marwan.gate.queue.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionServiceTest extends RedisTestBase {

    @Autowired private QueueService queueService;
    @Autowired private AdmissionService admissionService;

    @Test
    void anUnknownTokenHasNoPosition() {
        assertThat(admissionService.position("no-such-token")).isEmpty();
    }

    @Test
    void positionShrinksAsAdmissionAdvances() {
        for (int i = 0; i < 399; i++) {
            queueService.join();
        }
        JoinResult mine = queueService.join();   // ticket 400

        Optional<PositionView> before = admissionService.position(mine.token());
        assertThat(before).isPresent();
        assertThat(before.get().position()).isEqualTo(400);
        assertThat(before.get().admitted()).isFalse();

        clock().advance(Duration.ofSeconds(1));  // 200 admitted

        PositionView after = admissionService.position(mine.token()).orElseThrow();
        assertThat(after.position()).isEqualTo(200);
        assertThat(after.admitted()).isFalse();
    }

    @Test
    void anAdmittedTokenReportsItsRemainingWindow() {
        JoinResult mine = queueService.join();   // ticket 1
        clock().advance(Duration.ofSeconds(1));

        PositionView view = admissionService.position(mine.token()).orElseThrow();

        assertThat(view.position()).isZero();
        assertThat(view.admitted()).isTrue();
        assertThat(view.expiresInSeconds()).isBetween(290L, 300L);
    }

    @Test
    void consumingAnAdmittedTokenSucceedsExactlyOnce() {
        JoinResult mine = queueService.join();
        clock().advance(Duration.ofSeconds(1));

        admissionService.consume(mine.token());

        assertThatThrownBy(() -> admissionService.consume(mine.token()))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_INVALID"));
    }

    @Test
    void aTokenWhoseTurnHasNotComeIsRejected() {
        for (int i = 0; i < 399; i++) {
            queueService.join();
        }
        JoinResult mine = queueService.join();   // ticket 400, not yet admitted

        assertThatThrownBy(() -> admissionService.consume(mine.token()))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_NOT_YET_ADMITTED"));
    }

    @Test
    void aTokenPastItsFiveMinuteWindowIsRejected() {
        JoinResult mine = queueService.join();
        clock().advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> admissionService.consume(mine.token()))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_EXPIRED"));
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() -> admissionService.consume("no-such-token"))
                .isInstanceOf(TokenRejectedException.class)
                .satisfies(e -> assertThat(((TokenRejectedException) e).getReason())
                        .isEqualTo("TOKEN_INVALID"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AdmissionServiceTest`
Expected: FAIL — compilation error, `AdmissionService` does not exist.

- [ ] **Step 3: Write the implementation**

`queue-gate/src/main/java/dev/marwan/gate/queue/PositionView.java`:

```java
package dev.marwan.gate.queue;

public record PositionView(long position, boolean admitted, long expiresInSeconds) { }
```

`queue-gate/src/main/java/dev/marwan/gate/queue/TokenRejectedException.java`:

```java
package dev.marwan.gate.queue;

public class TokenRejectedException extends RuntimeException {

    private final String reason;

    public TokenRejectedException(String reason) {
        super("Admission token rejected: " + reason);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
```

`queue-gate/src/main/java/dev/marwan/gate/queue/AdmissionService.java`:

```java
package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AdmissionService {

    private final StringRedisTemplate redis;
    private final DropProperties drop;
    private final Clock clock;

    public AdmissionService(StringRedisTemplate redis, DropProperties drop, Clock clock) {
        this.redis = redis;
        this.drop = drop;
        this.clock = clock;
    }

    /** Read-only. Returns empty when the token is unknown or has lapsed from Redis. */
    public Optional<PositionView> position(String token) {
        String raw = redis.opsForValue().get(QueueService.ADMIT_PREFIX + token);
        if (raw == null) {
            return Optional.empty();
        }
        long ticket = Long.parseLong(raw);
        Instant now = clock.instant();

        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        long position = Math.max(0, ticket - admitted);
        boolean isAdmitted = position == 0;

        Instant expiresAt = Admission.turnAt(ticket, drop.opensAt(), drop.admitRate())
                .plus(drop.admissionWindow());
        long expiresIn = isAdmitted ? Math.max(0, Duration.between(now, expiresAt).toSeconds()) : 0;

        return Optional.of(new PositionView(position, isAdmitted, expiresIn));
    }

    /**
     * Consumes the token, or throws. GETDEL is atomic, so two simultaneous
     * requests carrying the same token race inside Redis and exactly one wins —
     * there is no check-then-act window to exploit.
     */
    public void consume(String token) {
        String raw = redis.opsForValue().getAndDelete(QueueService.ADMIT_PREFIX + token);
        if (raw == null) {
            throw new TokenRejectedException("TOKEN_INVALID");
        }
        long ticket = Long.parseLong(raw);
        Instant now = clock.instant();

        if (!Admission.isAdmitted(ticket, now, drop.opensAt(), drop.admitRate())) {
            throw new TokenRejectedException("TOKEN_NOT_YET_ADMITTED");
        }
        if (Admission.hasExpired(ticket, now, drop.opensAt(),
                                 drop.admitRate(), drop.admissionWindow())) {
            throw new TokenRejectedException("TOKEN_EXPIRED");
        }
    }
}
```

Note that `consume` deletes before validating. That is deliberate: a token presented too early or too late is spent either way, which prevents a caller from probing repeatedly until their window opens.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=AdmissionServiceTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add queue-gate/
git commit -m "Add position polling and single-use admission token consumption"
```

---

## Task 6: The public HTTP surface

**Files:**
- Create: `queue-gate/src/main/java/dev/marwan/gate/web/GateApiError.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/web/QueueController.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/web/GateExceptionHandler.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/QueueControllerTest.java`

**Interfaces:**
- Consumes: `QueueService.join()`, `AdmissionService.position(String)`, and the four gate exceptions.
- Produces: `POST /queue`, `GET /queue/{token}`, and `GateApiError(String reason, Map<String,Object> details)`.

- [ ] **Step 1: Write the failing test**

`queue-gate/src/test/java/dev/marwan/gate/QueueControllerTest.java`:

```java
package dev.marwan.gate;

import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class QueueControllerTest extends RedisTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private QueueService queueService;

    @Test
    void joiningReturnsATicketAndPosition() throws Exception {
        mvc.perform(post("/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value(1))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.admitted").value(true));
    }

    @Test
    void joiningBeforeOpenReturnsServiceUnavailableWithRetryAfter() throws Exception {
        clock().setNow(OPENS_AT.minusSeconds(120));

        mvc.perform(post("/queue"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.reason").value("NOT_OPEN"))
                .andExpect(jsonPath("$.details.secondsUntilOpen").value(120));
    }

    @Test
    void joiningAfterCloseReturnsConflict() throws Exception {
        clock().setNow(OPENS_AT.plus(Duration.ofMinutes(31)));

        mvc.perform(post("/queue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DROP_CLOSED"));
    }

    @Test
    void joiningPastTheCapReturnsSoldOut() throws Exception {
        for (int i = 0; i < 250; i++) {
            queueService.join();
        }

        mvc.perform(post("/queue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SOLD_OUT"));
    }

    @Test
    void pollingReturnsPositionAndAdmission() throws Exception {
        JoinResult mine = queueService.join();

        mvc.perform(get("/queue/" + mine.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.admitted").value(true));
    }

    @Test
    void pollingAnUnknownTokenReturnsNotFound() throws Exception {
        mvc.perform(get("/queue/no-such-token"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=QueueControllerTest`
Expected: FAIL — compilation error, `QueueController` does not exist.

- [ ] **Step 3: Write the implementation**

`queue-gate/src/main/java/dev/marwan/gate/web/GateApiError.java`:

```java
package dev.marwan.gate.web;

import java.util.Map;

public record GateApiError(String reason, Map<String, Object> details) { }
```

`queue-gate/src/main/java/dev/marwan/gate/web/QueueController.java`:

```java
package dev.marwan.gate.web;

import dev.marwan.gate.queue.AdmissionService;
import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.PositionView;
import dev.marwan.gate.queue.QueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/queue")
public class QueueController {

    private final QueueService queueService;
    private final AdmissionService admissionService;

    public QueueController(QueueService queueService, AdmissionService admissionService) {
        this.queueService = queueService;
        this.admissionService = admissionService;
    }

    @PostMapping
    public JoinResult join() {
        return queueService.join();
    }

    @GetMapping("/{token}")
    public ResponseEntity<PositionView> position(@PathVariable String token) {
        return admissionService.position(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

`queue-gate/src/main/java/dev/marwan/gate/web/GateExceptionHandler.java`:

```java
package dev.marwan.gate.web;

import dev.marwan.gate.queue.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GateExceptionHandler {

    @ExceptionHandler(DropNotOpenException.class)
    public ResponseEntity<GateApiError> notOpen(DropNotOpenException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getSecondsUntilOpen()))
                .body(new GateApiError("NOT_OPEN",
                        Map.of("secondsUntilOpen", e.getSecondsUntilOpen())));
    }

    @ExceptionHandler(DropClosedException.class)
    public ResponseEntity<GateApiError> closed(DropClosedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new GateApiError("DROP_CLOSED", Map.of()));
    }

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<GateApiError> soldOut(SoldOutException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new GateApiError("SOLD_OUT", Map.of()));
    }

    @ExceptionHandler(TokenRejectedException.class)
    public ResponseEntity<GateApiError> tokenRejected(TokenRejectedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new GateApiError(e.getReason(), Map.of()));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=QueueControllerTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add queue-gate/
git commit -m "Expose queue join and position over HTTP"
```

---

## Task 7: The admission-gated booking proxy

The gate consumes the admission token and forwards the call to `booking-service`. Tested against WireMock so no Oracle is needed.

**Files:**
- Create: `queue-gate/src/main/java/dev/marwan/gate/web/BookingClient.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/web/BookingProxyController.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/BookingProxyControllerTest.java`

**Interfaces:**
- Consumes: `AdmissionService.consume(String)`, `TokenRejectedException`.
- Produces: `POST /bookings` requiring header `X-Admission-Token`, and `POST /bookings/{id}/deposit`. Both relay the downstream status code and body verbatim.

- [ ] **Step 1: Write the failing test**

`queue-gate/src/test/java/dev/marwan/gate/BookingProxyControllerTest.java`:

```java
package dev.marwan.gate;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.marwan.gate.queue.JoinResult;
import dev.marwan.gate.queue.QueueService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookingProxyControllerTest extends RedisTestBase {

    static WireMockServer wiremock;

    @BeforeAll
    static void startWiremock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWiremock() {
        wiremock.stop();
    }

    @DynamicPropertySource
    static void bookingServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("booking-service.base-url", () -> "http://localhost:" + wiremock.port());
    }

    @Autowired private MockMvc mvc;
    @Autowired private QueueService queueService;

    private static final String BODY = """
        {"slotId":1,"phone":"+60123456789","partySize":2,"idempotencyKey":"k1"}
        """;

    @Test
    void anAdmittedTokenIsForwardedAndTheResponseRelayed() throws Exception {
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/bookings"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"bookingId":42,"status":"PENDING_DEPOSIT","idempotentReplay":false}
                            """)));

        JoinResult mine = queueService.join();
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(42));

        wiremock.verify(1, postRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void aMissingTokenIsRejectedWithoutCallingDownstream() throws Exception {
        wiremock.resetAll();

        mvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TOKEN_INVALID"));

        wiremock.verify(0, postRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void aTokenCannotBeUsedTwice() throws Exception {
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/bookings"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"bookingId":43,"status":"PENDING_DEPOSIT","idempotentReplay":false}
                            """)));

        JoinResult mine = queueService.join();
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        wiremock.verify(1, postRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void aDownstreamConflictIsRelayedWithItsBody() throws Exception {
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/bookings"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"reason":"SLOT_SOLD_OUT","details":{"slotId":1,"requested":2,"remaining":0}}
                            """)));

        JoinResult mine = queueService.join();
        clock().advance(Duration.ofSeconds(1));

        mvc.perform(post("/bookings")
                        .header("X-Admission-Token", mine.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SLOT_SOLD_OUT"))
                .andExpect(jsonPath("$.details.remaining").value(0));
    }

    @Test
    void depositIsProxiedWithoutRequiringAToken() throws Exception {
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/bookings/42/deposit"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {"bookingId":42,"status":"CONFIRMED","idempotentReplay":false}
                            """)));

        mvc.perform(post("/bookings/42/deposit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=BookingProxyControllerTest`
Expected: FAIL — compilation error, `BookingProxyController` does not exist.

- [ ] **Step 3: Write the implementation**

`queue-gate/src/main/java/dev/marwan/gate/web/BookingClient.java`:

```java
package dev.marwan.gate.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BookingClient {

    private final RestClient client;

    public BookingClient(@Value("${booking-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** Relays the downstream status and body verbatim, including errors. */
    public ResponseEntity<String> createBooking(String body) {
        return client.post()
                .uri("/bookings")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })   // never throw; relay instead
                .toEntity(String.class);
    }

    public ResponseEntity<String> confirmDeposit(long bookingId) {
        return client.post()
                .uri("/bookings/{id}/deposit", bookingId)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
    }
}
```

`queue-gate/src/main/java/dev/marwan/gate/web/BookingProxyController.java`:

```java
package dev.marwan.gate.web;

import dev.marwan.gate.queue.AdmissionService;
import dev.marwan.gate.queue.TokenRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingProxyController {

    private final AdmissionService admissionService;
    private final BookingClient bookingClient;

    public BookingProxyController(AdmissionService admissionService, BookingClient bookingClient) {
        this.admissionService = admissionService;
        this.bookingClient = bookingClient;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "X-Admission-Token", required = false) String token,
            @RequestBody String body) {

        if (token == null || token.isBlank()) {
            throw new TokenRejectedException("TOKEN_INVALID");
        }
        admissionService.consume(token);

        ResponseEntity<String> downstream = bookingClient.createBooking(body);
        return ResponseEntity.status(downstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(downstream.getBody());
    }

    /**
     * Deposit carries no admission token: the token was consumed by the booking
     * itself. This endpoint is therefore unauthenticated and takes a guessable
     * sequential id — a known weakness recorded in the design spec, acceptable
     * only because payment is mocked.
     */
    @PostMapping("/{id}/deposit")
    public ResponseEntity<String> deposit(@PathVariable long id) {
        ResponseEntity<String> downstream = bookingClient.confirmDeposit(id);
        return ResponseEntity.status(downstream.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(downstream.getBody());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=BookingProxyControllerTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole gate suite**

Run: `mvn verify`
Expected: PASS — 7 (Admission) + 7 (QueueService) + 7 (AdmissionService) + 6 (QueueController) + 5 (proxy) + 1 (RedisReachable) = 33 tests.

- [ ] **Step 6: Commit**

```bash
git add queue-gate/
git commit -m "Proxy admitted bookings to the booking service"
```

---

## Task 8: Compose stack and the synchronized-burst load test

Makes the whole system runnable by hand and reproduces the 21:00 drop as a load test.

**Files:**
- Create: `docker-compose.yml`
- Create: `booking-service/Dockerfile`
- Create: `queue-gate/Dockerfile`
- Create: `loadtest/drop.js`
- Create: `loadtest/README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: a `docker compose up` stack on ports 8080 (gate) and 8081 (booking-service), and `k6 run loadtest/drop.js`.

- [ ] **Step 1: Write the Dockerfiles**

`booking-service/Dockerfile`:

```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -DskipTests package || mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`queue-gate/Dockerfile`: identical except `EXPOSE 8080`.

- [ ] **Step 2: Write the compose file**

`docker-compose.yml`:

```yaml
services:
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  oracle:
    image: gvenzl/oracle-free:23-slim-faststart
    environment:
      ORACLE_PASSWORD: booking
      APP_USER: booking
      APP_USER_PASSWORD: booking
    ports: ["1521:1521"]
    healthcheck:
      test: ["CMD", "healthcheck.sh"]
      interval: 10s
      timeout: 5s
      retries: 30

  booking-service:
    build: ./booking-service
    depends_on:
      oracle:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:oracle:thin:@//oracle:1521/FREEPDB1
      SPRING_DATASOURCE_USERNAME: booking
      SPRING_DATASOURCE_PASSWORD: booking
      SERVER_PORT: 8081
    ports: ["8081:8081"]

  queue-gate:
    build: ./queue-gate
    depends_on: [redis, booking-service]
    environment:
      SPRING_DATA_REDIS_HOST: redis
      BOOKING_SERVICE_BASE_URL: http://booking-service:8081
      DROP_OPENS_AT: "2026-09-03T00:00:00Z"
      DROP_CLOSES_AT: "2030-01-01T00:00:00Z"
      DROP_ADMIT_RATE: "200"
      DROP_TICKET_CAP: "250"
    ports: ["8080:8080"]
```

The compose stack sets the drop window wide open so the system is usable on demand rather than only at 21:00.

- [ ] **Step 3: Verify the stack by hand**

```bash
docker compose up -d --build
sleep 90                      # Oracle first boot
TOKEN=$(curl -s -XPOST localhost:8080/queue | jq -r .token)
curl -s localhost:8080/queue/$TOKEN
curl -s -XPOST localhost:8080/bookings \
  -H "X-Admission-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slotId":1,"phone":"+60123456789","partySize":2,"idempotencyKey":"manual-1"}'
```

Expected: a token, then a position with `admitted: true`, then either a `201` booking or a `404 SLOT_NOT_FOUND` if no slot row exists yet. Seed one via sqlplus if needed:

```sql
INSERT INTO slots (service_date, service_time, capacity, seats_taken)
VALUES (DATE '2026-10-01', '19:00', 250, 0);
COMMIT;
```

- [ ] **Step 4: Write the k6 load test**

`loadtest/drop.js`:

```javascript
import http from 'k6/http';
import { check } from 'k6';

// Models the 21:00 drop: every virtual user arrives in the same instant,
// rather than ramping. A ramp would be testing a spike that does not exist.
export const options = {
  scenarios: {
    drop: {
      executor: 'per-vu-iterations',
      vus: 5000,
      iterations: 1,
      maxDuration: '2m',
    },
  },
};

const GATE = __ENV.GATE || 'http://localhost:8080';
const SLOT_ID = __ENV.SLOT_ID || 1;

export default function () {
  const join = http.post(`${GATE}/queue`);
  check(join, { 'join answered': (r) => r.status === 200 || r.status === 409 });
  if (join.status !== 200) return;               // SOLD_OUT is a valid outcome

  const token = join.json('token');

  for (let i = 0; i < 30; i++) {
    const poll = http.get(`${GATE}/queue/${token}`);
    if (poll.status === 200 && poll.json('admitted') === true) break;
  }

  const booking = http.post(
    `${GATE}/bookings`,
    JSON.stringify({
      slotId: Number(SLOT_ID),
      phone: `+6012${__VU}`,
      partySize: 2,
      idempotencyKey: `k6-${__VU}`,
    }),
    { headers: { 'Content-Type': 'application/json', 'X-Admission-Token': token } },
  );

  check(booking, {
    'booking resolved cleanly': (r) => [201, 403, 409].includes(r.status),
    'never a server error': (r) => r.status < 500,
  });
}
```

`loadtest/README.md`:

```markdown
# Load test

Reproduces the 21:00 drop as a synchronized burst.

    docker compose up -d --build
    k6 run loadtest/drop.js

Environment overrides: `GATE` (default `http://localhost:8080`), `SLOT_ID` (default `1`).

The invariant to check afterwards — no application code involved:

    docker exec -it $(docker compose ps -q oracle) \
      sqlplus -S booking/booking@//localhost:1521/FREEPDB1

    SELECT capacity, seats_taken, capacity - seats_taken AS remaining FROM slots;
    SELECT COUNT(*) AS violations FROM slots WHERE seats_taken > capacity;

`violations` must be 0, and `seats_taken` must never exceed `capacity`.
```

- [ ] **Step 5: Run the load test**

Run: `k6 run loadtest/drop.js`
Expected: no 5xx responses. Bookings resolve as `201`, `403` (token not admitted in time) or `409` (sold out). Then confirm in Oracle that `seats_taken <= capacity` and `violations` is 0.

If k6 is not installed: `brew install k6`.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml booking-service/Dockerfile queue-gate/Dockerfile loadtest/
git commit -m "Add compose stack and synchronized-burst load test"
```

---

## Self-Review

**Spec coverage.** Spec §4 (architecture, gate proxies everything) → Tasks 1, 7. §5 (admission as a function of time, Redis data model, `GETDEL` single use) → Tasks 3, 4, 5. §6 (public and internal APIs, error mapping) → Tasks 1, 6, 7. §7 (testing strategy, injected `Clock`, Testcontainers Redis, WireMock, MockMvc) → Tasks 2, 3, 5, 7. §8 (running locally, compose) → Task 8. §2's consequence (k6 models a synchronized burst, not a ramp) → Task 8. §9 out-of-scope items are absent: no `GET /slots`, no authentication, no OpenShift manifests, no observability. §10's known weaknesses are documented in code comments where they live — the unauthenticated deposit endpoint is called out in `BookingProxyController`.

**Placeholders.** None. Every code step carries complete, compilable content.

**Type consistency.** `Admission`'s four static methods are used with identical signatures in Tasks 4 and 5. `QueueService.ADMIT_PREFIX` is defined in Task 4 and consumed by `AdmissionService` in Task 5. `JoinResult(String, long, long, double, boolean)` is constructed in Task 4 and destructured in Tasks 5, 6 and 7. `PositionView(long, boolean, long)` is defined in Task 5 and serialized in Task 6. `TokenRejectedException.getReason()` returns the exact strings asserted in Tasks 5 and 7 and rendered in Task 6's handler. `DropProperties` accessor names match the `drop.*` YAML keys via relaxed binding.

**Known gaps, carried forward deliberately.** The gate has no authentication, so `POST /bookings/{id}/deposit` remains guessable — documented in the spec and in a code comment rather than half-solved. Phase 1's deferred minors D4 (no index on `bookings.slot_id`) and D5/D8 (wildcard imports) are untouched, as none is load-bearing for this phase. The `Instant`-to-`TIMESTAMP` timezone trade-off recorded during Phase 1 is unchanged here and should be revisited before Phase 3 deploys against OCI.

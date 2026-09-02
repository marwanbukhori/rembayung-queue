# Booking Domain Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the booking domain core — slots, bookings, pessimistic locking, idempotency, and expiry — and prove under concurrent load that capacity is never oversold.

**Architecture:** A single Spring Boot service talking to real Oracle. Seat allocation is serialised by `SELECT ... FOR UPDATE` on the slot row; the expiry sweeper takes the same lock so releasing and claiming can never interleave. A database `CHECK` constraint backstops the application logic. No HTTP layer, no Redis, no cluster — this phase is domain correctness only.

**Tech Stack:** Java 25 (LTS), Spring Boot 4.1.1, Maven, Spring Data JPA, Flyway, Oracle Database 23ai Free, Testcontainers, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-09-02-rembayung-booking-queue-design.md`

## Global Constraints

- **Java 25**, Spring Boot **4.1.1**, Maven. Base package `dev.marwan.booking`.
- **Oracle only.** Never substitute H2 or Postgres in tests — `SELECT ... FOR UPDATE` semantics are the thing under test, and H2 does not reproduce them faithfully.
- Testcontainers image: **`gvenzl/oracle-free:23-slim-faststart`** (verified multi-arch, includes native `arm64`).
- Slot capacity in all fixtures: **250** (the real Rembayung figure).
- Deposit: **2000 cents per head** (RM20), configurable.
- Booking hold TTL: **10 minutes**, configurable.
- **Commit messages must not contain AI attribution lines** (no `Co-Authored-By`, no `Generated with`). This is a standing preference of the repository owner.
- TDD throughout: failing test first, verify it fails, minimal implementation, verify it passes, commit.

---

## File Structure

```
rembayung-queue/
└── booking-service/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/dev/marwan/booking/
        │   │   ├── BookingServiceApplication.java   app entry point
        │   │   ├── domain/
        │   │   │   ├── Slot.java                    slot entity, seat accounting
        │   │   │   ├── Booking.java                 booking entity
        │   │   │   └── BookingStatus.java           status enum
        │   │   ├── repository/
        │   │   │   ├── SlotRepository.java          incl. locking query
        │   │   │   └── BookingRepository.java
        │   │   ├── service/
        │   │   │   ├── BookingService.java          book, confirmDeposit
        │   │   │   └── ExpirySweeper.java           releases expired holds
        │   │   └── api/
        │   │       ├── BookingRequest.java
        │   │       ├── BookingResult.java
        │   │       ├── SlotSoldOutException.java
        │   │       ├── SlotNotFoundException.java
        │   │       └── BookingNotFoundException.java
        │   └── resources/
        │       ├── application.yml
        │       └── db/migration/V1__initial_schema.sql
        └── test/
            ├── java/dev/marwan/booking/
            │   ├── OracleTestBase.java              shared container
            │   ├── SchemaMigrationTest.java
            │   ├── BookingServiceTest.java
            │   ├── IdempotencyTest.java
            │   ├── ExpirySweeperTest.java
            │   └── ConcurrencyInvariantTest.java
            └── resources/application-test.yml
```

`booking-service/` is a standalone Maven project, not a module of a parent POM. Phase 2's `queue-gate/` will sit beside it as a second standalone project, matching the fact that they deploy independently.

---

## Task 1: Project skeleton with real Oracle in tests

Toolchain installation and project scaffolding are folded in here because Task 1's deliverable — a test that talks to real Oracle — cannot be demonstrated without them. This task carries the single biggest risk in the plan: if Oracle will not start in a container on this machine, everything downstream is blocked, so it is proven first.

**Files:**
- Create: `booking-service/pom.xml`
- Create: `booking-service/src/main/java/dev/marwan/booking/BookingServiceApplication.java`
- Create: `booking-service/src/main/resources/application.yml`
- Create: `booking-service/src/test/resources/application-test.yml`
- Test: `booking-service/src/test/java/dev/marwan/booking/OracleTestBase.java`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: `OracleTestBase` — an abstract `@SpringBootTest` base class exposing a running Oracle container to all later tests via `@ServiceConnection`. Every subsequent test class extends it.

- [ ] **Step 1: Install the toolchain**

```bash
brew install openjdk@25 maven
sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-25.jdk
java -version   # expect: openjdk version "25..."
mvn -version    # expect: Apache Maven 3.9+, Java version 25
```

- [ ] **Step 2: Create `booking-service/pom.xml`**

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
  <artifactId>booking-service</artifactId>
  <version>0.1.0-SNAPSHOT</version>

  <properties>
    <java.version>25</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-oracle</artifactId>
    </dependency>
    <dependency>
      <groupId>com.oracle.database.jdbc</groupId>
      <artifactId>ojdbc11</artifactId>
      <scope>runtime</scope>
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
      <artifactId>oracle-free</artifactId>
      <version>${testcontainers.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
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

- [ ] **Step 3: Create the application entry point and config**

`booking-service/src/main/java/dev/marwan/booking/BookingServiceApplication.java`:

```java
package dev.marwan.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
```

`booking-service/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: booking-service
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

booking:
  deposit-cents-per-head: 2000
  hold-ttl: PT10M
```

`booking-service/src/test/resources/application-test.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
logging:
  level:
    org.testcontainers: INFO
```

- [ ] **Step 4: Write the failing test**

`booking-service/src/test/java/dev/marwan/booking/OracleTestBase.java`:

```java
package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class OracleTestBase {

    @Container
    @ServiceConnection
    static final OracleContainer ORACLE = new OracleContainer(
            DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withDatabaseName("bookingdb")
            .withUsername("booking")
            .withPassword("booking")
            .withStartupTimeout(Duration.ofMinutes(5))
            .withReuse(true);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void oracleContainerIsReachable() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1 FROM DUAL", Integer.class);
        assertThat(one).isEqualTo(1);
    }
}
```

Note `.withReuse(true)`: Oracle takes 30–60 seconds to start. Reuse keeps the container alive between test runs, which makes the rest of this plan tolerable to work through. Enable it globally with `echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties`.

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd booking-service && mvn test -Dtest=OracleTestBase`
Expected: FAIL. `OracleTestBase` is abstract, so JUnit will not run it directly — this step is expected to report "No tests were executed". That is the correct failure, and Task 2 introduces the first concrete subclass that actually exercises it.

- [ ] **Step 6: Verify the container itself starts**

Because the abstract base cannot self-execute, prove the image runs directly before moving on. This is the real risk being retired:

```bash
docker run --rm -d --name oracle-smoke \
  -e ORACLE_PASSWORD=booking -p 1521:1521 \
  gvenzl/oracle-free:23-slim-faststart
docker logs -f oracle-smoke   # wait for "DATABASE IS READY TO USE!", then Ctrl-C
docker rm -f oracle-smoke
```

Expected: the readiness banner appears within ~2 minutes, with no `exec format error`.

- [ ] **Step 7: Commit**

```bash
git add booking-service/
git commit -m "Add booking-service skeleton with Oracle Testcontainers base"
```

---

## Task 2: Database schema

**Files:**
- Create: `booking-service/src/main/resources/db/migration/V1__initial_schema.sql`
- Test: `booking-service/src/test/java/dev/marwan/booking/SchemaMigrationTest.java`

**Interfaces:**
- Consumes: `OracleTestBase` from Task 1
- Produces: tables `SLOTS` and `BOOKINGS`. Column names used by every later task: `SLOTS(id, service_date, service_time, capacity, seats_taken, version)`, `BOOKINGS(id, slot_id, phone, party_size, status, deposit_cents, idempotency_key, created_at, expires_at)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends OracleTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void slotsAndBookingsTablesExist() {
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_tables WHERE table_name IN ('SLOTS','BOOKINGS')",
                Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void databaseRefusesToOversellASlot() {
        jdbc.update("INSERT INTO slots (service_date, service_time, capacity, seats_taken) "
                + "VALUES (DATE '2026-10-01', '19:00', 250, 0)");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE slots SET seats_taken = 251 "
                        + "WHERE service_date = DATE '2026-10-01' AND service_time = '19:00'"))
                .hasMessageContaining("CK_SLOTS_SEATS");
    }
}
```

The second test is the important one: it asserts that **the database itself** refuses to be oversold, independent of any application logic.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SchemaMigrationTest`
Expected: FAIL — table or view does not exist.

- [ ] **Step 3: Write the migration**

`V1__initial_schema.sql`:

```sql
CREATE TABLE slots (
    id           NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    service_date DATE          NOT NULL,
    service_time VARCHAR2(5)   NOT NULL,
    capacity     NUMBER(5)     NOT NULL,
    seats_taken  NUMBER(5)     DEFAULT 0 NOT NULL,
    version      NUMBER(10)    DEFAULT 0 NOT NULL,
    CONSTRAINT uq_slots_datetime UNIQUE (service_date, service_time),
    CONSTRAINT ck_slots_seats CHECK (seats_taken >= 0 AND seats_taken <= capacity)
);

CREATE TABLE bookings (
    id              NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    slot_id         NUMBER        NOT NULL,
    phone           VARCHAR2(20)  NOT NULL,
    party_size      NUMBER(3)     NOT NULL,
    status          VARCHAR2(20)  NOT NULL,
    deposit_cents   NUMBER(10)    NOT NULL,
    idempotency_key VARCHAR2(64)  NOT NULL,
    created_at      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    expires_at      TIMESTAMP     NOT NULL,
    CONSTRAINT fk_bookings_slot FOREIGN KEY (slot_id) REFERENCES slots(id),
    CONSTRAINT uq_bookings_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_bookings_party CHECK (party_size > 0),
    CONSTRAINT ck_bookings_status CHECK (status IN
        ('PENDING_DEPOSIT','CONFIRMED','EXPIRED','CANCELLED'))
);

CREATE INDEX ix_bookings_sweep ON bookings (status, expires_at);
```

`ix_bookings_sweep` exists because the expiry sweeper in Task 7 queries exactly `(status, expires_at)` and would otherwise full-scan.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SchemaMigrationTest`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add booking-service/src/main/resources/db/migration booking-service/src/test
git commit -m "Add slots and bookings schema with anti-oversell check constraint"
```

---

## Task 3: Entities and repositories

**Files:**
- Create: `booking-service/src/main/java/dev/marwan/booking/domain/Slot.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/domain/Booking.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/domain/BookingStatus.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/repository/SlotRepository.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/repository/BookingRepository.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/SlotRepositoryTest.java`

**Interfaces:**
- Consumes: schema from Task 2
- Produces:
  - `BookingStatus` enum: `PENDING_DEPOSIT`, `CONFIRMED`, `EXPIRED`, `CANCELLED`
  - `Slot` with `getId()`, `getCapacity()`, `getSeatsTaken()`, `takeSeats(int)`, `releaseSeats(int)`, `remainingSeats()`
  - `Booking` with `getId()`, `getSlotId()`, `getStatus()`, `setStatus(BookingStatus)`, `getPartySize()`, `getExpiresAt()`
  - `SlotRepository.findByIdForUpdate(Long)` returning `Optional<Slot>` — **the locking query every later task depends on**
  - `BookingRepository.findByIdempotencyKey(String)` returning `Optional<Booking>`
  - `BookingRepository.findByStatusAndExpiresAtBefore(BookingStatus, Instant)` returning `List<Booking>`

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.booking;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SlotRepositoryTest extends OracleTestBase {

    @Autowired
    private SlotRepository slotRepository;

    @Test
    @Transactional
    void findByIdForUpdateReturnsTheSlot() {
        Slot saved = slotRepository.save(new Slot(LocalDate.of(2026, 11, 1), "19:00", 250));

        Slot locked = slotRepository.findByIdForUpdate(saved.getId()).orElseThrow();

        assertThat(locked.getCapacity()).isEqualTo(250);
        assertThat(locked.getSeatsTaken()).isZero();
        assertThat(locked.remainingSeats()).isEqualTo(250);
    }

    @Test
    @Transactional
    void takeSeatsIncrementsAndReleaseSeatsDecrements() {
        Slot slot = new Slot(LocalDate.of(2026, 11, 2), "19:00", 250);
        slot.takeSeats(4);
        assertThat(slot.getSeatsTaken()).isEqualTo(4);
        slot.releaseSeats(3);
        assertThat(slot.getSeatsTaken()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SlotRepositoryTest`
Expected: FAIL — compilation error, `Slot` does not exist.

- [ ] **Step 3: Write the implementation**

`BookingStatus.java`:

```java
package dev.marwan.booking.domain;

public enum BookingStatus {
    PENDING_DEPOSIT, CONFIRMED, EXPIRED, CANCELLED
}
```

`Slot.java`:

```java
package dev.marwan.booking.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "slots")
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "service_time", nullable = false, length = 5)
    private String serviceTime;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "seats_taken", nullable = false)
    private int seatsTaken;

    @Version
    @Column(nullable = false)
    private long version;

    protected Slot() { }

    public Slot(LocalDate serviceDate, String serviceTime, int capacity) {
        this.serviceDate = serviceDate;
        this.serviceTime = serviceTime;
        this.capacity = capacity;
        this.seatsTaken = 0;
    }

    public int remainingSeats() {
        return capacity - seatsTaken;
    }

    public boolean canAccommodate(int partySize) {
        return remainingSeats() >= partySize;
    }

    public void takeSeats(int partySize) {
        if (!canAccommodate(partySize)) {
            throw new IllegalStateException(
                    "Cannot take " + partySize + " seats; only " + remainingSeats() + " remain");
        }
        this.seatsTaken += partySize;
    }

    public void releaseSeats(int partySize) {
        if (partySize > seatsTaken) {
            throw new IllegalStateException("Cannot release more seats than are taken");
        }
        this.seatsTaken -= partySize;
    }

    public Long getId() { return id; }
    public LocalDate getServiceDate() { return serviceDate; }
    public String getServiceTime() { return serviceTime; }
    public int getCapacity() { return capacity; }
    public int getSeatsTaken() { return seatsTaken; }
}
```

`Booking.java`:

```java
package dev.marwan.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "party_size", nullable = false)
    private int partySize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "deposit_cents", nullable = false)
    private long depositCents;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Booking() { }

    public Booking(Long slotId, String phone, int partySize, long depositCents,
                   String idempotencyKey, Instant createdAt, Instant expiresAt) {
        this.slotId = slotId;
        this.phone = phone;
        this.partySize = partySize;
        this.status = BookingStatus.PENDING_DEPOSIT;
        this.depositCents = depositCents;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getSlotId() { return slotId; }
    public String getPhone() { return phone; }
    public int getPartySize() { return partySize; }
    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }
    public long getDepositCents() { return depositCents; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
}
```

`SlotRepository.java`:

```java
package dev.marwan.booking.repository;

import dev.marwan.booking.domain.Slot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    /**
     * Loads a slot under a row-level write lock (SELECT ... FOR UPDATE).
     * Every mutation of seats_taken — booking and expiry alike — MUST go
     * through this method, or the two can interleave and oversell the slot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Slot s where s.id = :id")
    Optional<Slot> findByIdForUpdate(@Param("id") Long id);
}
```

`BookingRepository.java`:

```java
package dev.marwan.booking.repository;

import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SlotRepositoryTest`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add booking-service/src/main/java booking-service/src/test/java
git commit -m "Add Slot and Booking entities with pessimistic locking repository"
```

---

## Task 4: Booking with pessimistic locking

**Files:**
- Create: `booking-service/src/main/java/dev/marwan/booking/api/BookingRequest.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/api/BookingResult.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/api/SlotSoldOutException.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/api/SlotNotFoundException.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/service/BookingService.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/BookingServiceTest.java`

**Interfaces:**
- Consumes: `SlotRepository.findByIdForUpdate`, `BookingRepository`, entities from Task 3
- Produces:
  - `record BookingRequest(Long slotId, String phone, int partySize, String idempotencyKey)`
  - `record BookingResult(Long bookingId, BookingStatus status, boolean idempotentReplay)`
  - `BookingService.book(BookingRequest)` returning `BookingResult`, throwing `SlotSoldOutException` / `SlotNotFoundException`

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.booking;

import dev.marwan.booking.api.*;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingServiceTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;

    private Long seedSlot(int capacity) {
        return slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 1), String.valueOf(System.nanoTime() % 100000),
                        capacity)).getId();
    }

    @Test
    void bookingASeatMarksItPendingDeposit() {
        Long slotId = seedSlot(250);

        BookingResult result = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 2, "key-1"));

        assertThat(result.status()).isEqualTo(BookingStatus.PENDING_DEPOSIT);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(2);
    }

    @Test
    void bookingBeyondCapacityIsRejected() {
        Long slotId = seedSlot(4);
        bookingService.book(new BookingRequest(slotId, "+60111111111", 4, "key-2"));

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest(slotId, "+60122222222", 1, "key-3")))
                .isInstanceOf(SlotSoldOutException.class);

        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(4);
    }

    @Test
    void bookingAnUnknownSlotIsRejected() {
        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest(999_999L, "+60123456789", 2, "key-4")))
                .isInstanceOf(SlotNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=BookingServiceTest`
Expected: FAIL — compilation error, `BookingService` does not exist.

- [ ] **Step 3: Write the implementation**

`BookingRequest.java`:

```java
package dev.marwan.booking.api;

public record BookingRequest(Long slotId, String phone, int partySize, String idempotencyKey) { }
```

`BookingResult.java`:

```java
package dev.marwan.booking.api;

import dev.marwan.booking.domain.BookingStatus;

public record BookingResult(Long bookingId, BookingStatus status, boolean idempotentReplay) { }
```

`SlotSoldOutException.java`:

```java
package dev.marwan.booking.api;

public class SlotSoldOutException extends RuntimeException {
    public SlotSoldOutException(Long slotId, int requested, int remaining) {
        super("Slot " + slotId + " cannot seat " + requested + "; " + remaining + " remaining");
    }
}
```

`SlotNotFoundException.java`:

```java
package dev.marwan.booking.api;

public class SlotNotFoundException extends RuntimeException {
    public SlotNotFoundException(Long slotId) {
        super("No slot with id " + slotId);
    }
}
```

`BookingService.java`:

```java
package dev.marwan.booking.service;

import dev.marwan.booking.api.*;
import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class BookingService {

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final long depositCentsPerHead;
    private final Duration holdTtl;

    public BookingService(SlotRepository slotRepository,
                          BookingRepository bookingRepository,
                          @Value("${booking.deposit-cents-per-head}") long depositCentsPerHead,
                          @Value("${booking.hold-ttl}") Duration holdTtl) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.depositCentsPerHead = depositCentsPerHead;
        this.holdTtl = holdTtl;
    }

    @Transactional
    public BookingResult book(BookingRequest request) {
        Slot slot = slotRepository.findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new SlotNotFoundException(request.slotId()));

        if (!slot.canAccommodate(request.partySize())) {
            throw new SlotSoldOutException(
                    request.slotId(), request.partySize(), slot.remainingSeats());
        }

        slot.takeSeats(request.partySize());

        Instant now = Instant.now();
        Booking booking = bookingRepository.save(new Booking(
                slot.getId(),
                request.phone(),
                request.partySize(),
                depositCentsPerHead * request.partySize(),
                request.idempotencyKey(),
                now,
                now.plus(holdTtl)));

        return new BookingResult(booking.getId(), booking.getStatus(), false);
    }
}
```

The lock is acquired **before** the capacity check, and both the check and the increment happen inside one transaction. Any other transaction attempting the same slot blocks at `findByIdForUpdate` until this one commits. That ordering is the correctness argument for the entire system.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=BookingServiceTest`
Expected: PASS, all three tests.

- [ ] **Step 5: Commit**

```bash
git add booking-service/src
git commit -m "Add booking creation with pessimistic slot locking"
```

---

## Task 5: Idempotency

**Files:**
- Modify: `booking-service/src/main/java/dev/marwan/booking/service/BookingService.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/IdempotencyTest.java`

**Interfaces:**
- Consumes: `BookingService.book`, `BookingRepository.findByIdempotencyKey`
- Produces: `book()` returns `BookingResult.idempotentReplay() == true` when a key is reused, and consumes no additional seats.

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private SlotRepository slotRepository;

    @Test
    void replayingTheSameKeyReturnsTheOriginalBookingAndTakesNoExtraSeats() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 5), "19:00", 250)).getId();

        BookingRequest request =
                new BookingRequest(slotId, "+60123456789", 3, "idem-fixed-key");

        BookingResult first = bookingService.book(request);
        BookingResult replay = bookingService.book(request);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.bookingId()).isEqualTo(first.bookingId());
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(3);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=IdempotencyTest`
Expected: FAIL — seats taken is 6, not 3, and the second call throws a unique-constraint violation.

- [ ] **Step 3: Add the idempotency check to `BookingService.book`**

Insert this block at the very start of `book(...)`, before `findByIdForUpdate`:

```java
        var existing = bookingRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Booking prior = existing.get();
            return new BookingResult(prior.getId(), prior.getStatus(), true);
        }
```

The pre-check handles the common case cheaply. The `uq_bookings_idempotency` constraint from Task 2 remains the real guarantee: two genuinely simultaneous requests with the same key will both pass the pre-check, and the database will reject the loser. Task 8 exercises that race.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=IdempotencyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add booking-service/src
git commit -m "Return the original booking when an idempotency key is replayed"
```

---

## Task 6: Deposit confirmation

**Files:**
- Modify: `booking-service/src/main/java/dev/marwan/booking/service/BookingService.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/api/BookingNotFoundException.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/BookingServiceTest.java` (add methods)

**Interfaces:**
- Consumes: `BookingService.book`, `BookingRepository`
- Produces: `BookingService.confirmDeposit(Long bookingId)` returning `BookingResult`, throwing `BookingNotFoundException` or `IllegalStateException`

- [ ] **Step 1: Write the failing test**

Add to `BookingServiceTest`:

```java
    @Test
    void confirmingADepositMovesTheBookingToConfirmed() {
        Long slotId = seedSlot(250);
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 2, "key-deposit-1"));

        BookingResult confirmed = bookingService.confirmDeposit(booked.bookingId());

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void confirmingAnAlreadyConfirmedBookingIsRejected() {
        Long slotId = seedSlot(250);
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 2, "key-deposit-2"));
        bookingService.confirmDeposit(booked.bookingId());

        assertThatThrownBy(() -> bookingService.confirmDeposit(booked.bookingId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirmingAnUnknownBookingIsRejected() {
        assertThatThrownBy(() -> bookingService.confirmDeposit(999_999L))
                .isInstanceOf(BookingNotFoundException.class);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=BookingServiceTest`
Expected: FAIL — compilation error, `confirmDeposit` does not exist.

- [ ] **Step 3: Write the implementation**

`BookingNotFoundException.java`:

```java
package dev.marwan.booking.api;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(Long bookingId) {
        super("No booking with id " + bookingId);
    }
}
```

Add to `BookingService`:

```java
    @Transactional
    public BookingResult confirmDeposit(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.PENDING_DEPOSIT) {
            throw new IllegalStateException(
                    "Booking " + bookingId + " is " + booking.getStatus()
                            + ", expected PENDING_DEPOSIT");
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        return new BookingResult(booking.getId(), booking.getStatus(), false);
    }
```

Add the import `dev.marwan.booking.domain.BookingStatus;` to `BookingService`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=BookingServiceTest`
Expected: PASS, all six tests.

- [ ] **Step 5: Commit**

```bash
git add booking-service/src
git commit -m "Add deposit confirmation with status guard"
```

---

## Task 7: Expiry sweeper

This task implements correctness rule **R1** from the spec: the sweeper must take the same row lock as a booking, or the two can interleave and oversell the slot.

**Files:**
- Create: `booking-service/src/main/java/dev/marwan/booking/service/ExpirySweeper.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/ExpirySweeperTest.java`

**Interfaces:**
- Consumes: `BookingRepository.findByStatusAndExpiresAtBefore`, `SlotRepository.findByIdForUpdate`
- Produces: `ExpirySweeper.sweepExpired(Instant now)` returning `int` (count of bookings expired)

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.BookingResult;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import dev.marwan.booking.service.ExpirySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirySweeperTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private ExpirySweeper expirySweeper;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;

    @Test
    void expiredPendingBookingsReleaseTheirSeats() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 10), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 5, "sweep-key-1"));

        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(5);

        // Sweep with a clock far enough forward that the 10-minute hold has lapsed.
        int expired = expirySweeper.sweepExpired(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(expired).isEqualTo(1);
        assertThat(bookingRepository.findById(booked.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isZero();
    }

    @Test
    void confirmedBookingsAreNeverSwept() {
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2026, 12, 11), "19:00", 250)).getId();
        BookingResult booked = bookingService.book(
                new BookingRequest(slotId, "+60123456789", 5, "sweep-key-2"));
        bookingService.confirmDeposit(booked.bookingId());

        int expired = expirySweeper.sweepExpired(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(expired).isZero();
        assertThat(slotRepository.findById(slotId).orElseThrow().getSeatsTaken()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=ExpirySweeperTest`
Expected: FAIL — compilation error, `ExpirySweeper` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package dev.marwan.booking.service;

import dev.marwan.booking.domain.Booking;
import dev.marwan.booking.domain.BookingStatus;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.BookingRepository;
import dev.marwan.booking.repository.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;

    public ExpirySweeper(BookingRepository bookingRepository, SlotRepository slotRepository) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void sweep() {
        int expired = sweepExpired(Instant.now());
        if (expired > 0) {
            log.info("Expired {} unpaid bookings", expired);
        }
    }

    /**
     * Expires unpaid bookings whose hold has lapsed and returns their seats.
     *
     * Acquires the SAME pessimistic lock on the slot row that BookingService.book
     * uses. Without that, a release and a claim can interleave and oversell the slot.
     */
    @Transactional
    public int sweepExpired(Instant now) {
        List<Booking> stale = bookingRepository
                .findByStatusAndExpiresAtBefore(BookingStatus.PENDING_DEPOSIT, now);

        for (Booking booking : stale) {
            Slot slot = slotRepository.findByIdForUpdate(booking.getSlotId()).orElseThrow();
            slot.releaseSeats(booking.getPartySize());
            booking.setStatus(BookingStatus.EXPIRED);
        }
        return stale.size();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=ExpirySweeperTest`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add booking-service/src
git commit -m "Add expiry sweeper that releases seats under the same slot lock"
```

---

## Task 8: The concurrency invariant

This is the deliverable the entire phase exists to produce: proof that the invariant holds under concurrent load. It is also the test to demonstrate in an interview.

**Files:**
- Test: `booking-service/src/test/java/dev/marwan/booking/ConcurrencyInvariantTest.java`

**Interfaces:**
- Consumes: everything from Tasks 3–7
- Produces: no production code — this task adds proof, not behaviour

- [ ] **Step 1: Write the test**

```java
package dev.marwan.booking;

import dev.marwan.booking.api.BookingRequest;
import dev.marwan.booking.api.SlotSoldOutException;
import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.BookingService;
import dev.marwan.booking.service.ExpirySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyInvariantTest extends OracleTestBase {

    @Autowired private BookingService bookingService;
    @Autowired private ExpirySweeper expirySweeper;
    @Autowired private SlotRepository slotRepository;

    @Test
    void neverOversellsUnderConcurrentBooking() throws Exception {
        int capacity = 250;
        int contenders = 400;
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2027, 1, 1), "19:00", capacity)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        for (int i = 0; i < contenders; i++) {
            final int n = i;
            attempts.add(pool.submit(() -> {
                startGun.await();
                try {
                    bookingService.book(new BookingRequest(
                            slotId, "+6012" + n, 1, "concurrent-" + n));
                    return true;
                } catch (SlotSoldOutException e) {
                    return false;
                }
            }));
        }

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();

        int succeeded = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get()) succeeded++;
        }

        Slot finalSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(succeeded).isEqualTo(capacity);
        assertThat(finalSlot.getSeatsTaken()).isEqualTo(capacity);
        assertThat(finalSlot.getSeatsTaken()).isLessThanOrEqualTo(finalSlot.getCapacity());
    }

    @Test
    void neverOversellsWhileTheSweeperRunsConcurrently() throws Exception {
        int capacity = 100;
        Long slotId = slotRepository.save(
                new Slot(LocalDate.of(2027, 1, 2), "19:00", capacity)).getId();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger booked = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        // Bookers.
        for (int i = 0; i < 200; i++) {
            final int n = i;
            tasks.add(pool.submit(() -> {
                startGun.await();
                try {
                    bookingService.book(new BookingRequest(
                            slotId, "+6013" + n, 1, "sweeprace-" + n));
                    booked.incrementAndGet();
                } catch (SlotSoldOutException ignored) {
                    // expected once full
                }
                return null;
            }));
        }

        // Sweepers running against a clock far in the future, so every hold is expirable.
        for (int i = 0; i < 4; i++) {
            tasks.add(pool.submit(() -> {
                startGun.await();
                for (int r = 0; r < 20; r++) {
                    expirySweeper.sweepExpired(Instant.now().plusSeconds(3600));
                }
                return null;
            }));
        }

        startGun.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();
        for (Future<?> task : tasks) task.get();   // surface any thrown exception

        Slot finalSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(finalSlot.getSeatsTaken()).isBetween(0, capacity);
    }
}
```

**Do not annotate this test class or its methods with `@Transactional`.** A test-managed transaction rolls back and, worse, keeps every operation on one connection — which would make the concurrency being tested impossible to observe. The test must commit for real.

The second test is the one that would catch a violation of rule R1. If `ExpirySweeper` did not take the slot lock, a release and a claim could interleave and drive `seats_taken` above capacity — at which point the `ck_slots_seats` constraint from Task 2 fires and the test fails loudly.

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=ConcurrencyInvariantTest`
Expected: PASS, both tests. Runtime is roughly 1–3 minutes; 400 contenders serialising on one row is deliberately the worst case.

- [ ] **Step 3: Run the whole suite**

Run: `mvn verify`
Expected: PASS, all test classes.

- [ ] **Step 4: Commit**

```bash
git add booking-service/src/test
git commit -m "Prove capacity is never oversold under concurrent booking and sweeping"
```

---

## Self-Review

**Spec coverage.** Section 5 (data model) → Tasks 2–3. Section 6 flow steps 4–6 → Tasks 4, 6, 7. Rule R1 (sweeper takes the same lock) → Task 7, verified by Task 8. Rule R2 (idempotency at the database) → Tasks 2 and 5. The Section 6 invariant → Task 8. Spec Section 10 phase 1 items are all covered. Flow steps 1–3 (queue, admission tokens) are Phase 2 and deliberately out of scope; steps 4a (admission-token validation) likewise lands in the Phase 2 plan, where the token exists to validate.

**Placeholders.** None. Every code step carries complete, compilable content.

**Type consistency.** `findByIdForUpdate(Long)` is used identically in Tasks 4 and 7. `BookingResult(Long, BookingStatus, boolean)` is constructed identically in Tasks 4, 5 and 6. `sweepExpired(Instant)` returns `int` in both its definition and its assertions. `BookingStatus` values match the `ck_bookings_status` constraint in Task 2 exactly.

**Known gap, carried forward deliberately.** `BookingService.book` has no HTTP layer; it is invoked directly in tests. REST endpoints arrive in the Phase 2 plan alongside the queue gate, because the gate defines the contract they must satisfy.

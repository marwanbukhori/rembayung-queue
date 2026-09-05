# Demo Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deployed console a stranger can drive — creating their own sandboxed drop, sending real load at it, and watching the invariant hold — without a cluster account.

**Architecture:** The gate gains a Redis-backed drop registry so many drops can exist at once; tokens carry their own drop id, so only `POST /queue` changes shape. A new `console` service serves a public read-only view, renders the repository's Markdown, and — behind issued access keys — creates drops, launches bounded k6 Jobs, and exposes cluster state including the quotas that constrain it.

**Tech Stack:** Spring Boot 4.1.1, Redis, Kubernetes API via fabric8, k6 as a Job, Angular 20 built into the image in a multi-stage Docker build

**Spec:** [`docs/superpowers/specs/2026-09-05-demo-console-design.md`](../specs/2026-09-05-demo-console-design.md)

## Global Constraints

- **Constraints are content.** When an operation cannot run, the console names the limit and what is consuming it — never "something went wrong". A `Pending` Job against a real quota is a demonstration, not a failure to hide.
- **Enumerated operations only.** No command box, no script path, no SQL from the browser. A console that runs arbitrary `oc` is a remote shell with a login form.
- **Everything is behind the one key**, including reads. With two users there is no reason to leave anything open, and one rule is easier to reason about than two.
- **One key, checked server-side before any handler runs.** Drop ownership is deliberately not enforced — there is nobody to enforce it against.
- **The console's ServiceAccount cannot read Secrets**, matching the boundary `rembayung-cd` already proves.
- **Sessions are unlimited.** Create, run, discard, repeat. Idle drops are swept 30 minutes after their last request.
- **Read state through the Phase 6 providers.** `SlotStateProvider` and `QueueStateProvider` are the single computation of seats, oversold and queue depth. Nothing recomputes them.
- **No AI attribution in commit messages.** No `Co-Authored-By`, no `Claude-Session`, no claude.ai URL, no "Generated with", no robot emoji, no mention of an assistant, model or session. Hard standing rule of the repository owner; violated once already, requiring a history rewrite. After each commit run `git log -1 --format=%B`, read it, and amend if anything but your own text is present.

### Five things that will bite you

**Read the real signatures before writing tests against them.** Phases 6 and 7 lost hours to seven plan defects, every one a signature or API assumed rather than read — `Slot.getId()` returning a null `Long`, `DropProperties` having 7 constructor args and not 5, logback's `<if>` not working at all, an appender exposing `setLayout` and not `setEncoder`. Open the file first.

**No JDK on `PATH`, no root `mvnw`:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./queue-gate/mvnw -f queue-gate test > /tmp/qg.log 2>&1; echo "exit=$?"
```
`/usr/libexec/java_home -v 25` does **not** work. Suites start real Oracle and Redis containers and are slow — redirect to a file and read it.

**Containers ship no `curl`.** They are JRE images. Verify from an ephemeral pod:
`oc run probe --rm -i --restart=Never --image=registry.access.redhat.com/ubi9/ubi-minimal --command -- curl -s <url>`

**`oc apply -k` is bootstrap-only.** The overlay pins tags that CD overwrites at run time; applying a stale overlay rolls the cluster backwards. Deploy with the Phase 5 playbook.

**The cluster is live and public.** Real pods behind a Route. Every task must leave it serving.

---

## File Structure

```
queue-gate/src/main/java/dev/marwan/gate/
├── queue/DropRecord.java              NEW — one drop's window and limits
├── queue/DropRegistry.java            NEW — Redis-backed create/read/touch/sweep
├── queue/QueueService.java            MODIFY — namespaced counter, drop-aware join
├── queue/AdmissionService.java        MODIFY — resolve the drop from the token
└── web/QueueController.java           MODIFY — POST /queue takes an optional drop

booking-service/src/main/java/dev/marwan/booking/
└── web/InternalController.java        NEW — seed a slot, read slot state

console/                               NEW SERVICE
├── pom.xml
├── Dockerfile
└── src/main/
    ├── java/dev/marwan/console/
    │   ├── ConsoleApplication.java
    │   ├── auth/AccessKeys.java        tiers, expiry, lookup
    │   ├── auth/KeyFilter.java         resolves tier per request
    │   ├── state/ClusterState.java     pods, quota, HPA via fabric8
    │   ├── state/DemoState.java        aggregates gate + booking state
    │   ├── ops/DropOps.java            create / open a drop
    │   ├── ops/LoadOps.java            launch and watch a k6 Job
    │   ├── ops/DeployOps.java          operator tier: deploy, roll back, scale
    │   └── web/                        controllers
    └── resources/
        ├── static/                     Angular build output, copied in at image build
        └── docs/                       Markdown copied in at build time

console/design/demo-console-v3.html     the owner's design — the source of truth for layout
console/ui/                             NEW — Angular workspace
├── src/app/                            standalone components, one per design section
├── src/app/api/                        typed clients for /api/state, /api/docs, /api/cluster
└── angular.json, package.json

deploy/base/console/                    NEW — Deployment, Service, Route, SA, Role
```

---

## Task 1: A drop registry in the gate

**Files:**
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/DropRecord.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/queue/DropRegistry.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/queue/DropRegistryTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`, `DropProperties` (record of 7 components: `opensAt`, `closesAt`, `seats`, `ticketCap`, `admitRate`, `admissionWindow`, `ticketTtl`), `Clock`.
- Produces: `DropRecord` — a record `(String id, Instant opensAt, Instant closesAt, int ticketCap, int admitRate, Duration admissionWindow, Duration ticketTtl, Long slotId)`; and `DropRegistry` with `DropRecord create(int admitRate, Long slotId)`, `Optional<DropRecord> find(String id)`, `DropRecord defaultDrop()`, `void touch(String id)`. **Tasks 2, 3 and 6 consume these.** The canonical drop's id is the constant `DropRegistry.DEFAULT_ID = "default"`.

- [ ] **Step 1: Read the files you are about to depend on**

```bash
cat queue-gate/src/main/java/dev/marwan/gate/config/DropProperties.java
cat queue-gate/src/main/java/dev/marwan/gate/queue/QueueService.java
```

`DropProperties` has **seven** components. Any test constructing it needs all seven. This exact mistake cost a task in Phase 6.

- [ ] **Step 2: Write the failing test**

Create `queue-gate/src/test/java/dev/marwan/gate/queue/DropRegistryTest.java`:

```java
package dev.marwan.gate.queue;

import dev.marwan.gate.config.DropProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import dev.marwan.gate.RedisTestBase;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DropRegistryTest extends RedisTestBase {

    @Autowired private DropRegistry registry;

    @Test
    void theDefaultDropAlwaysExistsAndComesFromConfiguration() {
        DropRecord d = registry.defaultDrop();

        assertThat(d.id()).isEqualTo(DropRegistry.DEFAULT_ID);
        assertThat(d.ticketCap()).isPositive();
        assertThat(d.admitRate()).isPositive();
    }

    // A visitor's drop must open immediately — nobody waits for 21:00 to see a
    // demo — and must be independent of the canonical one.
    @Test
    void aCreatedDropOpensImmediatelyAndIsIndependent() {
        DropRecord created = registry.create(8, 4242L);

        assertThat(created.id()).isNotEqualTo(DropRegistry.DEFAULT_ID);
        assertThat(created.opensAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(created.admitRate()).isEqualTo(8);
        assertThat(created.slotId()).isEqualTo(4242L);

        assertThat(registry.find(created.id())).contains(created);
        assertThat(registry.defaultDrop().slotId())
                .isNotEqualTo(created.slotId());
    }

    @Test
    void twoCreatedDropsDoNotShareIdentity() {
        DropRecord a = registry.create(1, 1L);
        DropRecord b = registry.create(1, 2L);

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    @Test
    void anUnknownDropIsEmptyRatherThanAnError() {
        assertThat(registry.find("d-does-not-exist")).isEmpty();
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./queue-gate/mvnw -q -f queue-gate test -Dtest=DropRegistryTest > /tmp/t.log 2>&1; echo "exit=$?"
```

Expected: FAIL — `DropRegistry` and `DropRecord` do not exist.

- [ ] **Step 4: Create DropRecord**

`queue-gate/src/main/java/dev/marwan/gate/queue/DropRecord.java`:

```java
package dev.marwan.gate.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * One drop: a window, a rate, a ticket budget, and the slot its bookings land in.
 *
 * This exists because the gate previously had exactly one drop, defined by
 * environment variables and changeable only by restarting a pod. That was fine
 * for a single scheduled 21:00 opening and impossible for a console where any
 * visitor can start their own at any moment.
 *
 * Held in Redis rather than configuration precisely so creating one is a write,
 * not a redeploy.
 */
public record DropRecord(
        String id,
        Instant opensAt,
        Instant closesAt,
        int ticketCap,
        int admitRate,
        Duration admissionWindow,
        Duration ticketTtl,
        Long slotId) { }
```

- [ ] **Step 5: Create DropRegistry**

`queue-gate/src/main/java/dev/marwan/gate/queue/DropRegistry.java`:

```java
package dev.marwan.gate.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marwan.gate.config.DropProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores drops in Redis so a new one costs a write rather than a redeploy.
 *
 * Idle drops expire on their own: every read refreshes the key's TTL, so a
 * sandbox someone abandoned disappears 30 minutes later without a sweeper.
 * Letting Redis do the expiry is what makes unlimited retries affordable — a
 * visitor can create and discard drops all afternoon and leave nothing behind.
 */
@Service
public class DropRegistry {

    public static final String DEFAULT_ID = "default";

    private static final String KEY_PREFIX = "drop:";
    private static final Duration IDLE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final DropProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public DropRegistry(StringRedisTemplate redis, DropProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The canonical drop, built from configuration rather than stored.
     *
     * Deliberately not persisted: it is whatever the running configuration says,
     * so `open-drop.sh` and the ConfigMap keep working exactly as before and
     * this task cannot break the 21:00 path.
     */
    public DropRecord defaultDrop() {
        return new DropRecord(
                DEFAULT_ID,
                properties.opensAt(),
                properties.closesAt(),
                properties.ticketCap(),
                properties.admitRate(),
                properties.admissionWindow(),
                properties.ticketTtl(),
                null);
    }

    public DropRecord create(int admitRate, Long slotId) {
        Instant now = clock.instant();
        DropRecord drop = new DropRecord(
                "d-" + UUID.randomUUID().toString().substring(0, 8),
                // Opens now. A visitor will not wait until 21:00 to see a demo,
                // and admission is a pure function of elapsed time since opensAt.
                now,
                now.plus(Duration.ofHours(1)),
                properties.ticketCap(),
                admitRate,
                properties.admissionWindow(),
                properties.ticketTtl(),
                slotId);
        write(drop);
        return drop;
    }

    public Optional<DropRecord> find(String id) {
        if (DEFAULT_ID.equals(id)) {
            return Optional.of(defaultDrop());
        }
        String raw = redis.opsForValue().get(KEY_PREFIX + id);
        if (raw == null) {
            return Optional.empty();
        }
        touch(id);
        try {
            return Optional.of(json.readValue(raw, DropRecord.class));
        } catch (Exception e) {
            // A record we cannot parse is indistinguishable from one that is
            // gone, as far as a caller is concerned. Treating it as absent keeps
            // a corrupt key from turning every request into a 500.
            return Optional.empty();
        }
    }

    /** Refreshes the idle timer. Any read counts as activity. */
    public void touch(String id) {
        if (!DEFAULT_ID.equals(id)) {
            redis.expire(KEY_PREFIX + id, IDLE_TTL);
        }
    }

    private void write(DropRecord drop) {
        try {
            redis.opsForValue().set(KEY_PREFIX + drop.id(),
                    json.writeValueAsString(drop), IDLE_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("could not store drop " + drop.id(), e);
        }
    }
}
```

- [ ] **Step 6: Run the test and watch it pass**

```bash
./queue-gate/mvnw -q -f queue-gate test -Dtest=DropRegistryTest > /tmp/t.log 2>&1; echo "exit=$?"
grep -E 'Tests run:' /tmp/t.log | tail -2
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Run the whole gate suite — nothing existing may break**

```bash
./queue-gate/mvnw -f queue-gate test > /tmp/qg.log 2>&1; echo "exit=$?"
grep -E 'Tests run:.*Skipped: [0-9]+$|BUILD' /tmp/qg.log | tail -3
```

Expected: 42 existing plus 4 new, zero failures, zero skipped.

- [ ] **Step 8: Commit**

```bash
git add queue-gate
git commit -m "Hold drops in Redis so more than one can exist"
git log -1 --format=%B
```

Read that output. Amend if it contains anything you did not write.

---

## Task 2: Make the queue drop-aware

**Files:**
- Modify: `queue-gate/src/main/java/dev/marwan/gate/queue/QueueService.java`
- Modify: `queue-gate/src/main/java/dev/marwan/gate/queue/AdmissionService.java`
- Modify: `queue-gate/src/main/java/dev/marwan/gate/web/QueueController.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/queue/MultiDropTest.java`

**Interfaces:**
- Consumes: `DropRegistry`, `DropRecord` from Task 1.
- Produces: `QueueService.join(String dropId)`; `POST /queue?drop={id}` with the parameter optional and defaulting to `DropRegistry.DEFAULT_ID`. **`GET /queue/{token}` and `POST /bookings` keep their exact current shapes.**

- [ ] **Step 1: Understand the design before changing anything**

Tokens are UUIDs and already unique across drops. So rather than namespacing the token key and forcing every caller to carry a drop id, **the token's stored value carries the drop**:

```
before   admit:{token}            -> "{ticket}"
after    admit:{token}            -> "{dropId}:{ticket}"

before   queue:ticket             -> counter, global
after    queue:{dropId}:ticket    -> counter, per drop
```

`AdmissionService.position(token)` and `consume(token)` therefore need **no new parameter** — they read the drop id out of the value. That is why the public API barely changes.

- [ ] **Step 2: Write the failing test**

Create `queue-gate/src/test/java/dev/marwan/gate/queue/MultiDropTest.java`:

```java
package dev.marwan.gate.queue;

import dev.marwan.gate.RedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MultiDropTest extends RedisTestBase {

    @Autowired private QueueService queueService;
    @Autowired private AdmissionService admissionService;
    @Autowired private DropRegistry registry;

    // The point of the whole task: two visitors must not share a ticket counter.
    @Test
    void twoDropsHaveIndependentTicketNumbering() {
        DropRecord a = registry.create(1, 100L);
        DropRecord b = registry.create(1, 200L);

        JoinResult a1 = queueService.join(a.id());
        JoinResult a2 = queueService.join(a.id());
        JoinResult b1 = queueService.join(b.id());

        assertThat(a1.ticket()).isEqualTo(1);
        assertThat(a2.ticket()).isEqualTo(2);
        // b is a different drop, so it starts at 1 rather than continuing at 3.
        assertThat(b1.ticket()).isEqualTo(1);
    }

    // The token has to be self-describing, or GET /queue/{token} would need a
    // drop parameter and every existing client would break.
    @Test
    void aTokenResolvesItsOwnDropWithoutBeingTold() {
        DropRecord drop = registry.create(1, 300L);
        JoinResult joined = queueService.join(drop.id());

        assertThat(admissionService.position(joined.token())).isPresent();
    }

    @Test
    void joiningAnUnknownDropIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> queueService.join("d-nope"))
                .isInstanceOf(UnknownDropException.class);
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
./queue-gate/mvnw -q -f queue-gate test -Dtest=MultiDropTest > /tmp/t.log 2>&1; echo "exit=$?"
```

Expected: FAIL — `join(String)` and `UnknownDropException` do not exist.

- [ ] **Step 4: Add the exception**

`queue-gate/src/main/java/dev/marwan/gate/queue/UnknownDropException.java`:

```java
package dev.marwan.gate.queue;

/** Raised when a request names a drop that has expired or never existed. */
public class UnknownDropException extends RuntimeException {
    public UnknownDropException(String dropId) {
        super("unknown drop: " + dropId);
    }
}
```

- [ ] **Step 5: Rewrite QueueService.join**

Replace the body of `QueueService` with the drop-aware form, keeping the class's existing fields and constructor and adding `DropRegistry`:

```java
    private static String ticketCounter(String dropId) {
        return "queue:" + dropId + ":ticket";
    }

    /**
     * Joins a named drop. The old no-argument form is gone: every join belongs
     * to exactly one drop, and defaulting silently would let a console bug send
     * a visitor's traffic into the canonical 21:00 queue.
     */
    public JoinResult join(String dropId) {
        DropRecord drop = drops.find(dropId).orElseThrow(() -> new UnknownDropException(dropId));
        Instant now = clock.instant();

        if (now.isBefore(drop.opensAt())) {
            throw new DropNotOpenException(Duration.between(now, drop.opensAt()).toSeconds());
        }
        if (now.isAfter(drop.closesAt())) {
            throw new DropClosedException();
        }

        Long ticket = redis.opsForValue().increment(ticketCounter(dropId));
        if (ticket == null || ticket > drop.ticketCap()) {
            throw new SoldOutException();
        }

        String token = UUID.randomUUID().toString();
        // The drop id travels with the ticket so the token is self-describing:
        // position() and consume() resolve the drop without a parameter, which
        // is what keeps GET /queue/{token} and POST /bookings unchanged.
        redis.opsForValue().set(ADMIT_PREFIX + token,
                dropId + ":" + ticket, drop.ticketTtl());

        long admitted = Admission.admittedBy(now, drop.opensAt(), drop.admitRate());
        long position = Math.max(0, ticket - admitted);

        return new JoinResult(token, ticket, position,
                (double) position / drop.admitRate(),
                position == 0);
    }
```

- [ ] **Step 6: Make AdmissionService resolve the drop from the token**

In both `position(String token)` and `consume(String token)`, replace the parse of `raw` and every use of `drop.` with a resolved record:

```java
    /** Splits "{dropId}:{ticket}" and loads the drop it names. */
    private record Held(DropRecord drop, long ticket) { }

    private Optional<Held> resolve(String raw) {
        int sep = raw.lastIndexOf(':');
        if (sep < 0) {
            return Optional.empty();
        }
        return drops.find(raw.substring(0, sep))
                .map(d -> new Held(d, Long.parseLong(raw.substring(sep + 1))));
    }
```

Then in `position`, after reading `raw`, use `resolve(raw)` and read `opensAt`, `admitRate` and `admissionWindow` from `held.drop()` instead of the injected `DropProperties`. Do the same in `consume`. **A token whose drop has expired must behave exactly like an unknown token** — `position` returns empty and `consume` throws `TokenRejectedException("TOKEN_INVALID")` — because from the caller's side those are the same situation.

- [ ] **Step 7: Add the optional parameter to the controller**

In `QueueController`:

```java
    @PostMapping
    public JoinResult join(
            // Optional so every existing caller, the load test and open-drop.sh
            // included, keeps working against the canonical drop untouched.
            @RequestParam(name = "drop", defaultValue = DropRegistry.DEFAULT_ID) String dropId) {
        return queueService.join(dropId);
    }
```

- [ ] **Step 8: Map the new exception to a status**

In `GateExceptionHandler`, add a handler returning **404** for `UnknownDropException` with error code `UNKNOWN_DROP`. 404 rather than 400: the drop is a resource that has expired or never existed, which is exactly what 404 means, and a visitor returning to a stale bookmark should be told the drop is gone rather than that their request was malformed.

- [ ] **Step 9: Run the new test, then the whole suite**

```bash
./queue-gate/mvnw -q -f queue-gate test -Dtest=MultiDropTest > /tmp/t.log 2>&1; echo "exit=$?"
./queue-gate/mvnw -f queue-gate test > /tmp/qg.log 2>&1; echo "exit=$?"
grep -E 'Tests run:.*Skipped: [0-9]+$|BUILD' /tmp/qg.log | tail -3
```

Expected: all pass, zero skipped. **The existing 42 tests are the guard on this task** — they exercise the canonical drop, and they must still pass untouched.

- [ ] **Step 10: Commit**

```bash
git add queue-gate
git commit -m "Give every queue join a drop of its own"
git log -1 --format=%B
```

---

## Task 3: Internal state and slot seeding

**Files:**
- Create: `booking-service/src/main/java/dev/marwan/booking/web/InternalController.java`
- Create: `queue-gate/src/main/java/dev/marwan/gate/web/InternalController.java`
- Test: `booking-service/src/test/java/dev/marwan/booking/InternalControllerTest.java`

**Interfaces:**
- Consumes: `SlotStateProvider.stateFor(long)` returning `Optional<SlotState>` and `SlotStateProvider.trackedSlotIds()` returning `List<Long>` (Phase 6, booking-service); `QueueStateProvider.current()` returning `QueueState` (Phase 6, queue-gate); `SlotRepository`; `DropRegistry`.
- Produces: on booking-service, `GET /internal/slots/{id}` returning `SlotState` as JSON and `POST /internal/slots` returning `{"slotId": <long>}`; on queue-gate, `GET /internal/drops/{dropId}/state` returning `QueueState`. **Task 6 consumes all three.**

- [ ] **Step 1: Confirm these paths cannot be reached from outside**

```bash
GATE=https://queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com
curl -s -o /dev/null -w "  /internal/... -> %{http_code}\n" $GATE/internal/drops/default/state
```

Expected once deployed: reachable only because the gate is the routed service. **`booking-service` has no Route at all**, so its `/internal` paths are cluster-only by construction — verified in Phase 3 and again in Phase 6. Do not add a Route for it.

- [ ] **Step 2: Write the failing test**

Create `booking-service/src/test/java/dev/marwan/booking/InternalControllerTest.java`:

```java
package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InternalControllerTest extends OracleTestBase {

    @Autowired private MockMvc mvc;

    @Test
    void seedingReturnsANewSlotWithItsOwnCapacity() throws Exception {
        mvc.perform(post("/internal/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").isNumber());
    }

    @Test
    void slotStateReportsSeatsAndOversold() throws Exception {
        String body = mvc.perform(post("/internal/slots"))
                .andReturn().getResponse().getContentAsString();
        long slotId = com.jayway.jsonpath.JsonPath.parse(body).read("$.slotId", Integer.class);

        mvc.perform(get("/internal/slots/" + slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(250))
                .andExpect(jsonPath("$.seatsTaken").value(0))
                .andExpect(jsonPath("$.oversold").value(0));
    }

    @Test
    void anUnknownSlotIsNotFound() throws Exception {
        mvc.perform(get("/internal/slots/999999")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./booking-service/mvnw -q -f booking-service test -Dtest=InternalControllerTest > /tmp/t.log 2>&1; echo "exit=$?"
```

Expected: FAIL — no such endpoints.

- [ ] **Step 4: Create booking-service's internal controller**

`booking-service/src/main/java/dev/marwan/booking/web/InternalController.java`:

```java
package dev.marwan.booking.web;

import dev.marwan.booking.domain.Slot;
import dev.marwan.booking.domain.SlotState;
import dev.marwan.booking.repository.SlotRepository;
import dev.marwan.booking.service.SlotStateProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * Cluster-internal only. booking-service has no Route, so nothing outside the
 * namespace can reach these paths — the isolation is structural rather than a
 * check that could be forgotten.
 *
 * Reads go through SlotStateProvider rather than the repository, so the console,
 * the Prometheus gauges and the alert rules all report one computation of seats
 * and oversold. Phase 6 built that provider for exactly this reason.
 */
@RestController
@RequestMapping("/internal/slots")
public class InternalController {

    private final SlotStateProvider provider;
    private final SlotRepository slots;

    public InternalController(SlotStateProvider provider, SlotRepository slots) {
        this.provider = provider;
        this.slots = slots;
    }

    /** A fresh 250-seat slot for one visitor's sandbox. */
    @PostMapping
    public Map<String, Long> seed() {
        Slot slot = slots.save(new Slot(LocalDate.now().plusDays(30), "19:00", 250));
        return Map.of("slotId", slot.getId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SlotState> state(@PathVariable long id) {
        return provider.stateFor(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: Create queue-gate's internal controller**

`queue-gate/src/main/java/dev/marwan/gate/web/InternalController.java`:

```java
package dev.marwan.gate.web;

import dev.marwan.gate.queue.DropRegistry;
import dev.marwan.gate.queue.QueueState;
import dev.marwan.gate.queue.QueueStateProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/drops")
public class InternalController {

    private final QueueStateProvider provider;
    private final DropRegistry drops;

    public InternalController(QueueStateProvider provider, DropRegistry drops) {
        this.provider = provider;
        this.drops = drops;
    }

    @GetMapping("/{dropId}/state")
    public ResponseEntity<QueueState> state(@PathVariable String dropId) {
        return drops.find(dropId)
                .map(d -> ResponseEntity.ok(provider.currentFor(dropId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

**Note:** this needs `QueueStateProvider.currentFor(String dropId)`. Phase 6's provider has only `current()`, which reads the global counter. Add the overload, keeping `current()` delegating to `currentFor(DropRegistry.DEFAULT_ID)` so the existing gauges and their tests are untouched.

- [ ] **Step 6: Run both suites**

```bash
./booking-service/mvnw -f booking-service test > /tmp/bs.log 2>&1; echo "bs exit=$?"
./queue-gate/mvnw -f queue-gate test > /tmp/qg.log 2>&1; echo "qg exit=$?"
grep -E 'Tests run:.*Skipped: [0-9]+$' /tmp/bs.log /tmp/qg.log | tail -4
```

- [ ] **Step 7: Commit**

```bash
git add booking-service queue-gate
git commit -m "Expose slot and drop state to cluster-internal callers"
git log -1 --format=%B
```

---

## Task 4: The console service, and its public page

**Files:**
- Create: `console/pom.xml`, `console/Dockerfile`, `console/mvnw` (copy from `queue-gate/`)
- Create: `console/src/main/java/dev/marwan/console/ConsoleApplication.java`
- Create: `console/src/main/java/dev/marwan/console/state/DemoState.java`
- Create: `console/src/main/java/dev/marwan/console/web/StateController.java`
- Create: `console/src/main/resources/static/index.html`, `console/src/main/resources/static/console.js`
- Test: `console/src/test/java/dev/marwan/console/state/DemoStateTest.java`

**Interfaces:**
- Consumes: `GET http://booking-service:8081/internal/slots/{id}` and `GET http://queue-gate:8080/internal/drops/{dropId}/state` from Task 3.
- Produces: `GET /api/state?drop={id}` returning `{drop, slot, queue, pods}` as JSON. **Tasks 6 and 7 extend this controller.**

- [ ] **Step 1: Create the module from the existing one**

```bash
mkdir -p console/src/main/java/dev/marwan/console console/src/main/resources/static console/src/test/java/dev/marwan/console
cp queue-gate/mvnw console/ && cp -r queue-gate/.mvn console/ && chmod +x console/mvnw
```

Copying the wrapper rather than relying on a root one is deliberate — **there is no `mvnw` at the repository root**, and each service carries its own.

- [ ] **Step 2: Write `console/pom.xml`**

Copy `queue-gate/pom.xml` and change `<artifactId>` to `console`, `<name>` to `console`. Keep the Spring Boot 4.1.1 parent and the `spring-boot-starter-web`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus` and `logstash-logback-encoder` dependencies. **Remove** the Redis and Oracle dependencies — the console talks to services over HTTP, never to their datastores. Add:

```xml
    <dependency>
      <groupId>io.fabric8</groupId>
      <artifactId>kubernetes-client</artifactId>
      <version>6.13.4</version>
    </dependency>
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark</artifactId>
      <version>0.22.0</version>
    </dependency>
```

Verify both resolve before writing code against them:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./console/mvnw -q -f console dependency:get -Dartifact=io.fabric8:kubernetes-client:6.13.4 2>&1 | tail -3
./console/mvnw -q -f console dependency:get -Dartifact=org.commonmark:commonmark:0.22.0 2>&1 | tail -3
```

If either fails, find the version that resolves and **report which you used** — do not silently substitute. Phase 6 lost a task to an artifact that was not in Maven Central.

- [ ] **Step 3: Write the failing test**

Create `console/src/test/java/dev/marwan/console/state/DemoStateTest.java`:

```java
package dev.marwan.console.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoStateTest {

    // A service being unreachable is normal — pods restart, drops expire — and
    // must render as "unknown" rather than throwing. A console that 500s when a
    // dependency blinks is worse than one that says it cannot see right now.
    @Test
    void anUnreachableServiceBecomesUnknownRatherThanAnError() {
        DemoState state = DemoState.unavailable("queue-gate did not answer");

        assertThat(state.available()).isFalse();
        assertThat(state.detail()).contains("queue-gate");
    }

    @Test
    void aCompleteStateReportsSeatsAndQueueTogether() {
        DemoState state = new DemoState(true, null, "d-abc",
                250, 202, 48, 0, 40, 10, 30);

        assertThat(state.capacity()).isEqualTo(250);
        assertThat(state.oversold()).isZero();
        assertThat(state.waiting()).isEqualTo(30);
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

```bash
./console/mvnw -q -f console test -Dtest=DemoStateTest > /tmp/t.log 2>&1; echo "exit=$?"
```

Expected: FAIL — `DemoState` does not exist.

- [ ] **Step 5: Create DemoState**

`console/src/main/java/dev/marwan/console/state/DemoState.java`:

```java
package dev.marwan.console.state;

/**
 * One snapshot of a drop, flattened for the browser.
 *
 * Every number here is fetched from the owning service's /internal endpoint,
 * never recomputed. seatsTaken and oversold in particular come from
 * SlotStateProvider, which is also what the Prometheus gauges and the
 * SlotOversold alert read — so the console cannot show a different answer from
 * the one that would page someone.
 */
public record DemoState(
        boolean available,
        String detail,
        String dropId,
        int capacity,
        int seatsTaken,
        int remaining,
        int oversold,
        long ticketsIssued,
        long admitted,
        long waiting) {

    public static DemoState unavailable(String detail) {
        return new DemoState(false, detail, null, 0, 0, 0, 0, 0, 0, 0);
    }
}
```

- [ ] **Step 6: Create the application and the state controller**

`ConsoleApplication.java` is a bare `@SpringBootApplication`. `StateController` exposes `GET /api/state?drop={id}`, calling both internal endpoints with a `RestClient`, a 2-second timeout, and a 1-second cache so viewer count cannot amplify load onto the services. On any failure it returns `DemoState.unavailable(...)` with the reason — **never a 500**.

- [ ] **Step 7: Scaffold the Angular workspace from the existing design**

**The design at `console/design/demo-console-v3.html` is already Angular** — 155
`{{ }}` interpolations and 24 `ng-` attributes. It is the source of truth for
layout, and its sections map directly onto this plan: a Public / Visitor /
Operator tier switcher, *Your sandbox*, *Constraints*, *Operations*, *Cluster*,
*Audit trail*, *Documentation*, *Deploy history*, *Links out*. Its palette is
DHL's — `#D40511` red, `#A80410` dark red, and the grey ramp `#1A1A1A`,
`#4A4A4A`, `#767676`, `#D9D9D9`, `#EDEDED`.

```bash
cd console && npx --yes @angular/cli@20 new ui --routing=false --style=css \
  --skip-git --skip-tests --standalone --package-manager=npm
```

Then port the design's markup into standalone components, one per section, and
lift its inline CSS into `styles.css` **keeping the hex values exactly** — the
palette is the part that makes it look deliberate rather than defaulted.

Bind the templates to a `StateService` that polls `/api/state` every 2 seconds.
**`oversold` is the largest number on the page** — it is the claim the project
makes. When `available` is false, render `detail` in place of the numbers rather
than blanking the page.

Node 25.6.0 and npm 11.8.0 are installed locally; `ubuntu-latest` ships node, so
CI needs no extra setup beyond the build step Task 8 adds.

- [ ] **Step 7a: Make the Dockerfile multi-stage**

`console/Dockerfile` gets a node stage that runs `npm ci && npm run build`, and
the JRE stage copies `ui/dist/ui/browser/` into `/app/static/`. Spring Boot
serves it as static content, so there is no second container and no separate
Route.

This is the first multi-language build in the project, and it is worth doing
properly: the node stage must not appear in the final image.

- [ ] **Step 8: Run it locally and see it**

```bash
./console/mvnw -f console spring-boot:run > /tmp/console.log 2>&1 &
sleep 25
curl -s localhost:8082/api/state | head -c 200; echo
kill %1
```

Expected: JSON with `available: false` and a detail naming the unreachable service — correct, because the services are not running locally. That is the failure path working.

- [ ] **Step 9: Commit**

```bash
git add console
git commit -m "Add a console service with a public state view"
git log -1 --format=%B
```

---

## Task 5: Render the documentation

**Files:**
- Modify: `console/pom.xml` (resource copy of the Markdown)
- Create: `console/src/main/java/dev/marwan/console/web/DocsController.java`
- Test: `console/src/test/java/dev/marwan/console/web/DocsControllerTest.java`

**Interfaces:**
- Consumes: `commonmark` from Task 4.
- Produces: `GET /api/docs` listing available documents, `GET /api/docs/{id}` returning rendered HTML.

- [ ] **Step 1: Copy the Markdown in at build time**

Add to `console/pom.xml` inside `<build>`:

```xml
      <resources>
        <resource>
          <directory>${project.basedir}/src/main/resources</directory>
        </resource>
        <!-- The notes and specs are the strongest artefact this project has for
             someone evaluating it, and they are worth more inside the console
             than in a repository nobody will clone. Copied at build time so the
             image is self-contained and no volume or git checkout is needed at
             run time. -->
        <resource>
          <directory>${project.basedir}/../docs</directory>
          <targetPath>docs</targetPath>
          <includes>
            <include>notes/*.md</include>
            <include>superpowers/specs/*.md</include>
            <include>superpowers/plans/*.md</include>
          </includes>
        </resource>
      </resources>
```

- [ ] **Step 2: Write the failing test**

```java
package dev.marwan.console.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DocsControllerTest {

    @Autowired private MockMvc mvc;

    @Test
    void theNotesAreListed() throws Exception {
        mvc.perform(get("/api/docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("observability")));
    }

    // Path traversal: the id is used to open a file, so it is the one place a
    // string from the browser touches the filesystem.
    @Test
    void aTraversingPathIsRefused() throws Exception {
        mvc.perform(get("/api/docs/..%2F..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run it, watch it fail, then implement**

`DocsController` enumerates the copied files **once at startup into a fixed map of id to path**, and serves only from that map. It never concatenates a request string onto a path — which is what makes the traversal test pass by construction rather than by filtering.

- [ ] **Step 4: Run the suite and commit**

```bash
./console/mvnw -f console test > /tmp/c.log 2>&1; echo "exit=$?"
git add console
git commit -m "Render the project's notes and specs in the console"
git log -1 --format=%B
```

---

## Task 6: One access key, and sandbox drops

**Files:**
- Create: `console/src/main/java/dev/marwan/console/auth/AccessKey.java`, `auth/KeyFilter.java`
- Create: `console/src/main/java/dev/marwan/console/ops/DropOps.java`
- Modify: `queue-gate/src/main/java/dev/marwan/gate/web/InternalController.java`
- Test: `console/src/test/java/dev/marwan/console/auth/AccessKeyTest.java`

**Interfaces:**
- Consumes: booking-service `POST /internal/slots`; queue-gate `POST /internal/drops` added here; `DropRegistry.create` from Task 1.
- Produces: `POST /api/drops` creating a sandbox, `POST /api/drops/{id}/open`. **Task 7 extends these.**

**There are no tiers.** An earlier draft had three, with per-drop ownership
checks. The audience is two trusted people, so all of that protected users from
each other who do not exist. One key, one dashboard, everything visible.

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.console.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccessKeyTest {

    private final AccessKey key = new AccessKey("s3cret-demo-key");

    @Test
    void theConfiguredKeyIsAccepted() {
        assertThat(key.accepts("s3cret-demo-key")).isTrue();
    }

    @Test
    void anythingElseIsRejected() {
        assertThat(key.accepts("wrong")).isFalse();
        assertThat(key.accepts("")).isFalse();
        assertThat(key.accepts(null)).isFalse();
    }

    // Compared in constant time. A dashboard is not a high-value target, but a
    // string comparison that returns early on the first differing character
    // leaks the key's prefix to anyone willing to time the responses, and the
    // fix is one method call.
    @Test
    void comparisonDoesNotShortCircuitOnLength() {
        assertThat(key.accepts("s3cret-demo-key-longer")).isFalse();
        assertThat(key.accepts("s3")).isFalse();
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement**

`AccessKey` wraps the value of `CONSOLE_ACCESS_KEY`, sourced from a Secret, and
compares with `java.security.MessageDigest.isEqual` on UTF-8 bytes.

`KeyFilter` reads `X-Console-Key` or the `key` query parameter. **It rejects with
401 before any handler runs.** Read-only `GET /api/state` and `GET /api/docs` are
also gated — with two users there is no reason to leave anything open, and one
rule is easier to reason about than two.

- [ ] **Step 3: Add drop creation to the gate**

`POST /internal/drops` on queue-gate taking `{"admitRate": 8, "slotId": 4242}`
and returning the `DropRecord`, delegating to `DropRegistry.create`.

- [ ] **Step 4: Wire DropOps**

`POST /api/drops` seeds a slot on booking-service, creates a drop bound to it on
queue-gate, and returns both ids. **The drop id is the session** — there is
nothing else to store, and no ownership to check.

- [ ] **Step 5: Run the suites and commit**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./console/mvnw -f console test > /tmp/c.log 2>&1; echo "exit=$?"
./queue-gate/mvnw -f queue-gate test > /tmp/qg.log 2>&1; echo "exit=$?"
git add console queue-gate
git commit -m "Gate the console behind one shared key"
git log -1 --format=%B
```

---

## Task 7: Bounded load, and the constraints panel

**Files:**
- Create: `console/src/main/java/dev/marwan/console/ops/LoadOps.java`
- Create: `console/src/main/java/dev/marwan/console/state/ClusterState.java`
- Create: `console/src/main/resources/k6/drop.js` (copied from `loadtest/drop.js`, parameterised by drop id)
- Test: `console/src/test/java/dev/marwan/console/state/ClusterStateTest.java`

**Interfaces:**
- Consumes: fabric8 `KubernetesClient`; `AccessKey` and `KeyFilter` from Task 6.
- Produces: `POST /api/drops/{id}/load` launching a Job; `GET /api/cluster` returning quota, pod and HPA state.

- [ ] **Step 1: Launch k6 as a Job**

`LoadOps` creates a `Job` running `grafana/k6`, with the script from a ConfigMap, `VUS` and the drop id as environment. **`activeDeadlineSeconds: 300`** so nothing runs forever, and one Job per drop enforced by naming the Job after the drop.

- [ ] **Step 2: Surface why a Job is not running**

When a Job's pod is `Pending`, read its conditions and report the reason verbatim — `Insufficient cpu` — alongside the namespace quota and what is consuming it. **This is the feature, not error handling.** A visitor watching their run wait on a real quota has been shown a real constraint.

- [ ] **Step 3: Build ClusterState**

Reads via fabric8: pod phases and readiness, `ResourceQuota` used versus hard, and each HPA's current and desired replicas with its target. Cached 2 seconds. On an API failure it returns an unavailable marker with the reason, never a 500.

- [ ] **Step 4: Test what matters**

```java
    // The console must render a partial picture rather than nothing when the
    // Kubernetes API is unreachable — the demo's own state still matters even
    // when cluster introspection is unavailable.
    @Test
    void clusterStateDegradesToUnavailableRatherThanThrowing() {
        ClusterState state = ClusterState.unavailable("API server refused");
        assertThat(state.available()).isFalse();
        assertThat(state.detail()).contains("API server");
    }
```

- [ ] **Step 5: Add the admission-rate control**

`POST /api/drops/{id}/rate` with `1`, `8` or `200`. 200/s is offered deliberately: it exhausts the connection pool and produces `503` with `Retry-After` **while `oversold` stays at zero**, which is the single most persuasive thing the console can show. The UI labels it as such rather than hiding it behind a warning.

- [ ] **Step 6: Commit**

```bash
git add console
git commit -m "Let a visitor send real load and see what stops it"
git log -1 --format=%B
```

---

## Task 8: Deploy the console

**Files:**
- Create: `deploy/base/console/deployment.yaml`, `service.yaml`, `route.yaml`, `rbac.yaml`, `kustomization.yaml`
- Modify: `deploy/base/kustomization.yaml`
- Modify: `.github/workflows/ci.yml` (build and publish the console image)

**Interfaces:**
- Consumes: everything above.
- Produces: a public Route serving the console.

- [ ] **Step 1: A ServiceAccount that can read but not touch secrets**

`rbac.yaml` grants `get`/`list`/`watch` on pods, resourcequotas and horizontalpodautoscalers, plus `create`/`get`/`list`/`delete` on jobs. **No secrets, no deployments** — the operator tier triggers CD through the GitHub API rather than patching Deployments directly, which keeps this account read-mostly.

Verify after applying:

```bash
SA=system:serviceaccount:marwanbukhori-dev:console
for v in "get pods" "get resourcequotas" "create jobs" "get secrets" "patch deployments"; do
  printf "  %-22s %s\n" "$v" "$(oc auth can-i $v --as=$SA -n marwanbukhori-dev)"
done
```

Expected: the first three `yes`, the last two `no`.

- [ ] **Step 2: Deployment, Service, Route**

One replica, `100m`/`256Mi` requested. Liveness and readiness on the management port with `timeoutSeconds: 5` and a `startupProbe` of `failureThreshold: 40`, `periodSeconds: 5` — matching what Phase 6 established after a startup race rolled a deploy back.

- [ ] **Step 3: Add to CI**

Extend `.github/workflows/ci.yml` to build and push `ghcr.io/marwanbukhori/console`
alongside the other two, tagged with the same commit SHA. The Angular build runs
inside the Dockerfile's node stage, so the workflow needs no `setup-node` step —
keeping the CI change to one more `docker/build-push-action` block.

Note this makes the pipeline multi-language: Java tests, then a node build, then
two image builds. Worth a sentence in Task 9's note.

- [ ] **Step 4: Deploy and verify from outside**

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml -e image_tag=$(git rev-parse HEAD)
CONSOLE=https://$(oc get route console -o jsonpath='{.spec.host}')
curl -s -o /dev/null -w "  /          -> %{http_code}\n" $CONSOLE/
curl -s -o /dev/null -w "  /api/state -> %{http_code}\n" $CONSOLE/api/state
curl -s -o /dev/null -w "  any endpoint without a key -> %{http_code}\n" $CONSOLE/api/state
```

Expected: the first two `200` only when a key is supplied, and **`401`** without one. If an unkeyed request returns anything but 401, stop — the gate is not closed.

- [ ] **Step 5: Commit**

```bash
git add deploy .github
git commit -m "Deploy the console"
git log -1 --format=%B
```

---

## Task 9: Document it

**Files:**
- Create: `docs/notes/09-demo-console.md`
- Modify: `docs/notes/README.md`, `deploy/README.md`

- [ ] **Step 1: Write note 09**

Cover: why per-visitor sandboxes replaced confirmation dialogs; why the token carries its own drop id and what that bought (`GET /queue/{token}` and `POST /bookings` unchanged); why access keys rather than OpenShift OAuth; why constraints are rendered rather than hidden, with a real example of a Job pending on quota; why load runs in-cluster and what that costs in fidelity; and the measured figures from Task 8's verification.

- [ ] **Step 2: Update both READMEs and commit**

```bash
git add docs deploy/README.md
git commit -m "Document the demo console"
git log -1 --format=%B
```

---

## Self-Review

**1. Spec coverage.** §3 sandboxes → Tasks 1, 2, 6. §4 authentication → Task 6. §5 constraints-as-content → Task 7 Steps 2–3. §5's load generation → Task 7 Steps 1 and 5. §5a dynamic and easy → Task 4 Step 7 and Task 7 Step 5. §6 public surface → Tasks 4 and 5; visitor → Task 6; operator → Task 7 Step 5 and Task 8. §7 security → Task 6 Step 5, Task 8 Step 1, Task 5 Step 3's traversal test. §8 delivery order → the task order. §9 weaknesses → Task 9.

**2. Placeholder scan.** Every code step carries real content. Three steps deliberately verify before depending on a value rather than asserting one: Task 1 Step 1 (read `DropProperties` — seven components), Task 4 Step 2 (both new artifacts resolve), and Task 8 Step 1 (the ServiceAccount boundary). All three exist because Phases 6 and 7 lost time to exactly those assumptions.

**3. Type consistency.** `DropRecord` is `(id, opensAt, closesAt, ticketCap, admitRate, admissionWindow, ticketTtl, slotId)` in Task 1 and used with those accessors in Tasks 2, 3 and 6. `DropRegistry.DEFAULT_ID` is referenced identically in Tasks 1, 2 and 3. `DemoState`'s ten components in Task 4 match its test. `AccessKey.accepts(String)` is defined in Task 6 and used unchanged in Tasks 7 and 8. There is no tier type; an earlier draft had one and it was removed once the audience turned out to be two trusted people.

**One gap named rather than hidden.** Task 3 requires `QueueStateProvider.currentFor(String dropId)`, which Phase 6 did not build — its provider reads the global counter only. Task 3 Step 5 adds the overload and keeps `current()` delegating to it so the existing gauges and their tests are untouched. Flagged because it is the one place this plan reaches back into a finished phase.

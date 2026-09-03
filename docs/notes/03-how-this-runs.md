# 03 — How this runs: triggers, and why there is no UI

**Covers:** the shape of the project after Phase 1, not any single task
**Branch state:** `d66ad38`, 25/25 tests passing

A reasonable question after Phase 1: *there is backend code and a test directory —
so how is any of it actually triggered?*

Short answer: **right now, only by the test suite.** This note explains why that
is deliberate, what the other trigger types are, and what arrives in each later
phase.

---

## What exists today is a library, not a server

There is nothing to start. `mvn spring-boot:run` fails immediately, for two
reasons that are worth confirming rather than taking on trust:

**No datasource is configured outside tests.** `src/main/resources/application.yml`
contains `spring.jpa`, `spring.flyway` and the `booking.*` block, but no
`spring.datasource`. The database connection is supplied only in tests, by
Testcontainers — see below.

**There is no web layer at all.** `pom.xml` has no `spring-boot-starter-web`, and
there is not a single `@RestController`, `@GetMapping` or `@PostMapping` in the
project. Verify either claim:

```bash
grep -A5 datasource booking-service/src/main/resources/application.yml
grep -rl 'RestController\|starter-web' booking-service/src booking-service/pom.xml
```

So there is no port, no endpoint, and nothing to `curl`.

---

## Trigger type 1 — the test suite

The only thing that runs application code today:

```
mvn test
  └─ Testcontainers starts real Oracle in Docker
     OracleTestBase.java:25   static final OracleContainer ORACLE = ...
     OracleTestBase.java:33   static { ORACLE.start(); }
      └─ @ServiceConnection hands Spring the container's random port
         OracleTestBase.java:24
          └─ Spring boots the application context (no web server — no web starter)
              └─ Flyway applies db/migration/V1__initial_schema.sql
                  └─ Hibernate validates entity mappings (ddl-auto: validate)
                      └─ the test method calls the service DIRECTLY
                         BookingServiceTest.java:31
                         bookingService.book(new BookingRequest(...))
```

That last step is the one to absorb. `BookingServiceTest` is **not** sending an
HTTP request. It calls a Java method, the same way any class calls another. The
"client" is JUnit.

The same is true of the concurrency proof — it just does it 400 times at once:

```
ConcurrencyInvariantTest.java:28   void neverOversellsUnderConcurrentBooking()
ConcurrencyInvariantTest.java:43       bookingService.book(new BookingRequest(...))
ConcurrencyInvariantTest.java:62       assertThat(succeeded).isEqualTo(capacity);
```

400 threads, one 250-seat slot, a plain method call from each. No network, no
JSON, no framework between the test and the thing being tested.

---

## Trigger type 2 — the clock

There is one non-test trigger already written. It is not a request; nobody calls
it:

```java
// ExpirySweeper.java:30
@Scheduled(fixedDelayString = "PT30S")
public void sweep() {
    int expired = sweepExpired(Instant.now());
    ...
}
```

Once the app runs as a real service, Spring fires this every 30 seconds on its
own. This is why `@EnableScheduling` went on the application class in Task 1
(`BookingServiceApplication.java:8`) long before anything used it.

Note the split, which is what makes the sweeper testable:

| Method | Line | Role |
|---|---|---|
| `sweep()` | `ExpirySweeper.java:31` | supplies the **real** clock, scheduled |
| `sweepExpired(Instant now)` | `ExpirySweeper.java:48` | takes the clock as a **parameter** |

Tests call the second one with a time an hour in the future, so a 10-minute hold
has "lapsed" without waiting ten minutes. Passing the clock in rather than
calling `Instant.now()` inside the logic is what makes time-dependent behaviour
testable at all.

**Two ways backend code starts:** something asked for it (a request), or the
clock said so (a schedule). This project has the second; the first arrives in
Phase 2.

---

## Why it was built in this order

In a typical Node project you write the Express route first, `curl` it, and grow
outward. This plan deliberately inverted that:

> Prove the domain is correct before anything can reach it.

The hard problem here is not HTTP. It is that hundreds of people hitting the same
row at once must not oversell it — a database and locking problem. Putting a REST
endpoint in front of that first would add a layer between you and the actual
difficulty, and make every test slower and noisier for no gain in confidence.

So Phase 1 answers one question — *is the core correct under concurrency?* — with
a test that calls the method 400 times simultaneously and then checks the
database. The plan's own words: no HTTP layer, no Redis, no cluster; this phase
is domain correctness only.

---

## What each phase adds

| Phase | Adds | How you trigger it |
|---|---|---|
| **1 — done** | Domain core: slots, bookings, locking, idempotency, expiry | `mvn test` |
| **2** | Redis queue gate, admission tokens, **REST controllers** | `curl`, Postman, browser |
| **3** | OpenShift deployment | a public URL (an OpenShift `Route`) |
| **4–5** | GitHub Actions CI, Ansible CD | `git push` builds and deploys |
| **6** | OpenTelemetry → Dynatrace, Logback → Splunk | dashboards |

Phase 2 is where `POST /bookings` appears and this stops being a library.

Worth noting from the design spec: **`booking-service` never gets a public
Route.** Only `queue-gate` is exposed to the internet. The queue cannot be
bypassed because the booking endpoint is not reachable from outside the cluster
at all — that is a security boundary, not an omission.

---

## There is no UI, and that is the design

The demo script in the design spec has five steps, and none of them is a
customer-facing web app:

| # | Step | What it proves |
|---|---|---|
| 1 | Book a seat normally | baseline — a `curl` call |
| 2 | Push a commit | CI builds, CD deploys |
| 3 | k6 fires 50,000 virtual users | queue absorbs the spike; latency stays flat |
| 4 | `SELECT seats_taken FROM slots` | **exactly 250, never 251** |
| 5 | `oc delete pod` mid-load | no confirmed booking lost or duplicated |

The "interface" of this demo is **k6 driving load, Splunk and Dynatrace showing
what happened, and a SQL prompt proving the invariant held.** A booking form
would take weeks and demonstrate nothing about the property under test.

Step 4 is the one the spec calls the step that matters, and it already works
today.

---

## Running the proof by hand

```bash
cd booking-service
export JAVA_HOME=/opt/homebrew/opt/openjdk@25

mvn test                                    # everything — 25 tests, ~10s
mvn test -Dtest=ConcurrencyInvariantTest    # just the concurrency proof
```

Then inspect the result directly in Oracle. The container keeps running between
test runs (`withReuse(true)`), and `OracleTestBase`'s cleanup runs *before* each
test — so whatever ran last leaves its rows behind, which is what makes this
inspectable:

```bash
docker ps --filter ancestor=gvenzl/oracle-free:23-slim-faststart   # get the name
docker exec <container> sqlplus -S booking/booking@//localhost:1521/BOOKINGDB
```

The PDB is **`BOOKINGDB`**, not `FREEPDB1`, because `OracleTestBase` calls
`.withDatabaseName("bookingdb")`.

```sql
SELECT capacity, seats_taken, capacity - seats_taken AS remaining FROM slots;
```

```
  CAPACITY SEATS_TAKEN  REMAINING
       250         250          0
```

400 attempts, 250 succeeded, 150 correctly rejected.

### The line that makes the argument

Then try to break it by hand, bypassing every line of Java in the project:

```sql
UPDATE slots SET seats_taken = 251 WHERE capacity = 250;
```

```
ERROR at line 1:
ORA-02290: check constraint (BOOKING.CK_SLOTS_SEATS) violated
```

The database refuses. The invariant is not enforced by application logic you have
to trust — it is enforced *below* the application, where no code path, no second
service, and no human at a SQL prompt can get around it.

That sequence — run the test, `SELECT` the row, then fail the `UPDATE` — is the
demo.

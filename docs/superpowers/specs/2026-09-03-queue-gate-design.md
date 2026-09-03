# Queue Gate — Design (Phase 2)

**Date:** 2026-09-03
**Status:** Approved, ready for implementation planning
**Phase:** 2 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)

---

## 1. Purpose

Phase 1 proved the booking domain never oversells a slot, under 400 concurrent
bookers racing four expiry sweepers. It has no HTTP layer, no queue, and no way
to reach it except a JUnit method call.

Phase 2 puts a **virtual waiting room** in front of it and gives the system a
real HTTP surface. The gate absorbs a synchronized demand spike so the database
never sees it.

### What Phase 1 produced, which this builds on

```java
BookingService.book(BookingRequest) → BookingResult
    throws SlotSoldOutException, SlotNotFoundException
BookingService.confirmDeposit(Long) → BookingResult
    throws BookingNotFoundException, IllegalStateException

record BookingRequest(Long slotId, String phone, int partySize, String idempotencyKey)
record BookingResult(Long bookingId, BookingStatus status, boolean idempotentReplay)
```

This spec was written against those signatures as they actually exist, not
against guesses made in advance.

---

## 2. New context: the drop is scheduled

From the restaurant owner:

> *"Reservation akan dibuka setiap hari jam 9 malam (kecuali hari jumaat)"*
> — reservations open every day at 21:00, except Friday.

This makes the spike **worse**, not milder. Random traffic spreads itself out; a
publicly announced 21:00 drop synchronizes every client to the same second.
People set alarms, and anyone scripting it fires at `21:00:00.000`. The arrival
burst is ~1 second wide.

It recurs six nights a week, so the system must be reliably good rather than
luckily good once — and the real event can be rehearsed daily.

**Open question, deliberately unresolved:** whether *"kecuali hari Jumaat"*
means the booking window does not open on Fridays, or that Friday sittings are
not offered. The drop schedule is configuration either way, so this does not
block implementation.

### Consequence: pre-scaling beats autoscaling

HPA is reactive and takes 15–30 seconds to move 2 → 10 replicas. A one-second
spike is over before it responds, so the gate would absorb the entire burst at
minimum replicas.

Because the drop time is known, **scale before it** — raise `minReplicas` at
20:55, lower it at 21:10. This is Phase 3 work, but the manifests must be
designed for it.

---

## 3. The inventory reality

One slot holds **250 seats**. At an average party of 2–4, that is roughly
**80–125 bookings** before it is gone — about **one second** of admission at 200/s.

With 50,000 people queued:

```
tickets 1 – ~125         win a seat
tickets ~126 – 50,000    queue for something that no longer exists
```

Position accuracy therefore matters far less than it first appears: the useful
part of the queue is its first few hundred tickets. Everyone else is counting
down to disappointment regardless of how precisely the number is computed.

**The gate still earns its place**, because the losers load the database too.
50,000 concurrent `POST /bookings` all returning `SlotSoldOutException` is still
50,000 transactions, connection acquisitions and row-lock attempts. The gate
meters *failure* as much as success.

**Therefore the queue is capped at inventory.** Past a configured ticket cap the
gate returns an immediate, honest "sold out" instead of a four-minute countdown
to failure.

---

## 4. Architecture

Two standalone Maven projects side by side. `queue-gate/` joins `booking-service/`;
neither is a module of a parent POM, matching the fact that they deploy
independently.

```
                  Browser / k6
                       │
                       ▼
        ┌──────────────────────────────┐
        │  queue-gate   (public)       │  the ONLY public entry point
        │  Spring Boot + Redis         │
        │                              │
        │  POST /queue          ───────┼──► Redis: INCR ticket
        │  GET  /queue/{token}  ───────┼──► Redis: GET ticket
        │  POST /bookings       ───────┼──► validate token, forward ↓
        │  POST /bookings/{id}/deposit │
        └──────────────┬───────────────┘
                       │  internal HTTP
                       ▼
        ┌──────────────────────────────┐
        │  booking-service  (internal) │  Phase 1 code, unchanged
        │  + thin REST layer           │
        └──────────────┬───────────────┘
                       ▼
                    Oracle
```

### Decision: the gate proxies everything

The parent spec gives `queue-gate` the only Route and states that
`booking-service` is **unreachable from the internet**. A browser therefore
cannot call `booking-service` directly, so the gate must forward the call.

Consequences, all of them wanted:

- **`booking-service` gains only controllers.** No Redis client, no admission
  logic, no change to `BookingService`, `ExpirySweeper`, the entities, or any
  Phase 1 test. All 25 existing tests keep passing untouched.
- **Admission tokens never reach `booking-service`.** The gate validates and
  consumes them. The trust boundary lives in one place, and `booking-service`
  has no idea a queue exists.
- **The gate is now an availability dependency**, not merely a doorman. If it is
  down, nothing is booked. That is the deliberate cost of making the queue
  unbypassable, and it is the right trade: a bypassable queue is not a queue.

---

## 5. Admission

### Admission is a function of time, not a counter

The obvious design — a `@Scheduled` task advancing an `admitted` counter — is
wrong here. The gate runs at 2–10 replicas, so **every replica would run its own
scheduler** and admission would advance at `rate × replicaCount`, changing as
pods come and go. Correcting that needs leader election or a distributed lock.

Instead, derive it:

```
admitted(now) = floor((now − opensAt) × rate)
```

No counter, no scheduler, no lock. Every replica computes the identical value
because it is arithmetic rather than shared state.

> **Admission needs no coordination between replicas, because it is not state —
> it is a function of time.**

The same derivation gives each ticket its admission window:

```
myTurnAt = opensAt + (ticket ÷ rate)
admitted = now ≥ myTurnAt
expired  = now > myTurnAt + 5 minutes
```

The window opens when the holder's turn actually arrives, not when they happened
to poll — which is both simpler and more correct.

### Redis data model

Two key patterns. That is the entire datastore.

| Key | Value | TTL | Operations |
|---|---|---|---|
| `queue:ticket` | monotonic counter | none | `INCR` |
| `admit:{token}` | ticket number | 30 min | `SET`, `GET`, `GETDEL` |

```
JOIN    reject if now < opensAt   → 503 + Retry-After
        reject if now > closesAt  → 409 DROP_CLOSED
        ticket = INCR queue:ticket
        if ticket > ticketCap     → 409 SOLD_OUT
        SET admit:{token} {ticket} EX 1800

POLL    ticket   = GET admit:{token}
        position = ticket − admitted(now)        ← arithmetic, no write
        eta      = position ÷ rate

BOOK    ticket = GETDEL admit:{token}            ← atomic single-use
        reject unless myTurnAt ≤ now ≤ myTurnAt + 5min
        forward to booking-service
```

**A poll costs one `GET`.** Polling is by far the highest-volume operation, so
this matters more than any other performance property of the design.

**`GETDEL` enforces single use.** It is atomic: two simultaneous requests
carrying the same token race inside Redis, exactly one receives the value, the
other receives nil and is rejected. No lock, no check-then-act window.

### Configuration

Per the parent spec, these live in a ConfigMap so they are tunable during a demo
without a rebuild.

```yaml
drop:
  opens-at:    21:00
  closes-at:   21:30
  seats:       250     # seats in the drop
  ticket-cap:  250     # TICKETS, not seats — see below
admit:
  rate: 200            # tickets admitted per second
```

**`ticket-cap` is counted in tickets, not seats.** One ticket becomes at most one
booking, and one booking consumes 2–4 seats. So 250 seats is roughly **125
bookings**, and a cap of 250 tickets is about **2× the bookings the drop can
actually satisfy** — enough headroom that no one who could have won is turned
away, while still cutting the queue off far short of 50,000. Setting the cap
from the seat count directly would over-admit by the average party size.

**After `closes-at`:** `POST /queue` returns `409 {reason: "DROP_CLOSED"}` and
issues no further tickets. Tokens already issued keep their admission windows
until those expire naturally, so someone admitted at 21:29 still gets their five
minutes. The drop closing stops new entrants; it does not evict people mid-flow.

---

## 6. API

### Public — queue-gate

```
POST /queue
  200 → {token, ticket: 4317, position: 1317, etaSeconds: 6.6, admitted: false}
  503 + Retry-After: 240             before opensAt
  409 → {reason: "SOLD_OUT"}         ticket > ticketCap
  409 → {reason: "DROP_CLOSED"}      after closesAt

GET /queue/{token}
  200 → {position: 0, admitted: true, expiresInSeconds: 287}
  404                             unknown or expired token

POST /bookings                    header: X-Admission-Token
  201 → {bookingId, status, idempotentReplay}
  403 → {reason: "TOKEN_INVALID" | "TOKEN_EXPIRED" | "TOKEN_CONSUMED"}
  409 → {reason: "SLOT_SOLD_OUT", slotId, requested, remaining}

POST /bookings/{id}/deposit
  200 → {bookingId, status: "CONFIRMED"}
```

### Internal — booking-service (no Route)

```
POST /bookings                → BookingService.book(...)
POST /bookings/{id}/deposit   → BookingService.confirmDeposit(...)
```

### Error mapping

Phase 1's exceptions carry their data only inside message strings. Phase 2 adds
getters so error bodies can be structured rather than parsed from prose. (This
closes a loose end recorded during Phase 1 review.)

| Exception | HTTP | Body carries |
|---|---|---|
| `SlotSoldOutException` | 409 | `slotId`, `requested`, `remaining` |
| `SlotNotFoundException` | 404 | `slotId` |
| `BookingNotFoundException` | 404 | `bookingId` |
| `IllegalStateException` | 409 | current status |

---

## 7. Testing

**Design constraint:** admission derives from `now`, so `java.time.Clock` **must
be an injected bean**, never `Instant.now()` called inline. Tests inject
`Clock.fixed(...)` and step time forward deterministically — no sleeps, no
flakes. This mirrors Phase 1, where `sweepExpired(Instant now)` takes the clock
as a parameter, and is why those tests run in milliseconds.

| Layer | Approach | Rationale |
|---|---|---|
| Gate queue mechanics | Testcontainers **Redis** + fixed `Clock` | real Redis semantics, deterministic time |
| Gate → booking-service | **WireMock** stub | proxying and error mapping without Oracle |
| booking-service REST | **MockMvc** over existing `OracleTestBase` | real Oracle, reuses the Phase 1 container |
| End to end | docker-compose smoke test | proves wiring, not logic |

Phase 1's 25 tests must keep passing unmodified. If a Phase 2 change requires
editing a Phase 1 test, that is a signal the change is wrong.

### Load test

A k6 script modelling a **synchronized burst at T=0**, not a ramp. A ramp would
be testing a spike that does not exist.

---

## 8. Running it locally

```yaml
# docker-compose.yml
redis            → 6379
oracle           → gvenzl/oracle-free:23-slim-faststart
booking-service  → 8081   (internal)
queue-gate       → 8080   (public)
```

```bash
TOKEN=$(curl -s -XPOST localhost:8080/queue | jq -r .token)
curl -s localhost:8080/queue/$TOKEN
curl -s -XPOST localhost:8080/bookings \
  -H "X-Admission-Token: $TOKEN" \
  -d '{"slotId":1,"phone":"+60123456789","partySize":2,"idempotencyKey":"abc"}'
```

This is Phase 2's answer to "how do I trigger it": a real HTTP surface that can
be exercised by hand.

---

## 9. Out of scope

- **`GET /slots`.** The demo reads seat counts via SQL and k6 takes a slot id
  from configuration. Not inventing an endpoint nothing needs.
- **Authentication.** Deferred with the rest of security hardening.
- **OpenShift, CI/CD, observability.** Phases 3–6.
- **Per-user-fair admission.** The parent spec accepts rate-based admission as a
  known weakness.

---

## 10. Known weaknesses

Stated so they are acknowledged rather than discovered.

- **There is no authentication anywhere, and three findings chain into
  account takeover of a booking.** A background security scan surfaced these on
  the Phase 2 REST layer; all three are real and all are accepted for the demo:

  1. **Broken access control.** `POST /bookings/{id}/deposit` is unauthenticated
     and takes a sequential integer id, so anyone can confirm anyone's booking
     by guessing. The admission token was consumed by the booking itself, so it
     cannot guard this call.
  2. **Information disclosure via the idempotency replay path.**
     `idempotencyKey` is client-supplied, and `book()` returns the prior
     booking's id and status whenever a key already exists. Submitting a
     guessed key therefore reveals another user's `bookingId` and
     `BookingStatus`.
  3. **Existence oracle in the error codes.** `BOOKING_NOT_FOUND` (404) versus
     `BOOKING_NOT_PENDING` (409) tells an unauthenticated caller whether a
     booking id exists and whether it is still unpaid.

  **Chained:** guess an idempotency key → learn a real `bookingId` and its
  status → call the unauthenticated deposit endpoint on it. The fix is
  authentication plus a server-generated secret returned by the booking and
  required by the deposit call, with idempotency keys scoped per authenticated
  user rather than global. That is deferred with the rest of security hardening,
  which the parent spec lists as an explicit non-goal, and payment is mocked so
  nothing of value moves. Documented here rather than discovered later.
- **Changing the admit rate mid-drop is unsafe.** Lowering it makes
  `admitted(now)` jump backwards and un-admit people. Rate changes apply from
  the next drop.
- **A consumed token is gone even if the booking fails.** Being admitted,
  spending the token and hitting `SlotSoldOutException` yields no second chance.
  That is what single use means; in practice the drop is over for that user
  anyway.
- **Position is by ticket, not live headcount.** Abandoned tickets still consume
  admission budget, so the displayed position is conservative. A sorted set
  would give a true position at `O(log N)` per poll and considerably more state;
  the cheaper model was chosen deliberately because the demo's thesis is the
  database invariant, not queue fairness.
- **Redis restart loses every queue position.** Accepted by the parent spec:
  Redis has no persistent volume, queue state is disposable, and **no confirmed
  booking is lost** because those live only in Oracle.

---

## 11. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| How bookings reach the domain | Gate proxies everything | `booking-service` has no Route; keeps its Phase 1 code and tests untouched |
| Queue model | Ticket counter + derived admission | `O(1)` polls, two Redis keys, explainable in one sentence |
| Admission advance | Function of time | Needs no coordination across 2–10 replicas; a scheduler would multiply the rate per pod |
| Admission window | Derived from `myTurnAt` | Starts when the turn arrives, not when the user polls; requires no write |
| Single use | `GETDEL` | Atomic; no check-then-act window |
| Overflow past inventory | Cap tickets, return `SOLD_OUT` | 99%+ of a 50,000 queue is waiting for nothing; an honest immediate answer beats a countdown to failure |
| Drop window | Gate owns it | Enables "not open yet" and the inventory cap; keeps the gate free of database access |
| Arrival smoothing | **Rejected** | Moves the herd rather than removing it, and the parent spec deliberately preserves the spike |
| Position model | Ticket-based, not live headcount | Inventory is ~125 bookings; position precision is close to irrelevant past the first few hundred tickets |

# Running the load test

Reproduces the 21:00 drop as a synchronised burst against the cluster, then
verifies in the database that the slot was never oversold.

Every number here was measured on this deployment, not chosen. Where a value
looks arbitrary, the reason it is not is written down.

---

## TL;DR — the whole run

```zsh
# 0. prerequisites
oc whoami                       # if this fails, see "Expired token" below
k6 version                      # brew install k6

# 1. reset both stores  (REQUIRED — see "Why the reset matters")
oc exec deploy/redis -- redis-cli FLUSHALL
#    then in the OCI SQL worksheet:
#      DELETE FROM booking.bookings;
#      UPDATE booking.slots SET seats_taken = 0;
#      COMMIT;

# 2. pre-scale the gate
oc scale deploy/queue-gate --replicas=10
oc rollout status deploy/queue-gate --timeout=300s

# 3. open the drop
./deploy/scripts/open-drop.sh

# 4. sanity-check before spending five minutes
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
curl -s -XPOST $GATE/queue          # must return a token, not NOT_OPEN or 500

# 5. run
k6 run -e GATE=$GATE -e SLOT_ID=1 -e VUS=200 loadtest/drop.js

# 6. verify in SQL  (this is the authoritative result)
#      SELECT capacity, seats_taken FROM booking.slots;
#      SELECT COUNT(*) FROM booking.slots WHERE seats_taken > capacity;  -- 0

# 7. scale back
oc scale deploy/queue-gate --replicas=2
```

---

## What a good run looks like

```
checks_succeeded ....... 100.00%  600 out of 600
✓ never a server error
bookings_created ....... 80
bookings_rejected ...... 120
```

and in the database:

```
CAPACITY  SEATS_TAKEN  REMAINING
     250          249          1

VIOLATIONS
         0
```

**249, not 250, is the correct terminal state.** Parties are two seats, so once
one seat remains no party fits: `canAccommodate(2)` is false and every further
contender is refused. The system declines a party that will not fit the
*remainder*, not merely one that exceeds capacity.

---

## Why the reset matters

**A second run without a reset measures nothing**, and it will look like a
system failure rather than an operator error.

Two pieces of state survive:

| State | Where | If not reset |
|---|---|---|
| `queue:ticket` | Redis | Monotonic, capped at 250. Every join returns `409 SOLD_OUT`, no booking is attempted, and the run proves nothing. |
| `seats_taken` | Oracle | Already at capacity, so every booking returns `409 SLOT_SOLD_OUT`. |

`deploy/scripts/open-drop.sh` flushes Redis. **The seat count must be reset
separately in SQL** — there is no network path from a laptop to the database.

Note the schema: the tables belong to `BOOKING`, and Database actions connects
you as `ADMIN`. Unqualified names give `ORA-00942`. Write `booking.slots`, or
run `ALTER SESSION SET CURRENT_SCHEMA = booking;` once per session.

---

## The numbers, and why they are what they are

### `VUS=200` — not 5000

Measured ceiling. A ladder counting how many requests actually reached the gate:

| Offered | Arrived | Failed |
|---|---|---|
| 200 | 200 | 0% |
| 1000 | 662 | 75% |
| 3000 | 818 | 92% |

Throughput plateaus around 600–800 regardless of offered load, and the
application logs zero errors at every level — the requests never arrive. That is
a connection ceiling on the shared sandbox router, not the application.

Above ~200, the shedding happens at the *edge*, which demonstrates nothing about
the system under test. At 200 every request lands and the excess is refused by
the *application*, which is the behaviour worth showing.

Reproduce it with `VUS=1000` if you want to see the ceiling for yourself.

### `DROP_ADMIT_RATE: 8` — not 200

This is the number the whole architecture turns on, so it is worth understanding.

```
cluster ──250ms RTT──> database (ap-kulai-2)

one booking = SELECT ... FOR UPDATE + INSERT + COMMIT
            = 3 sequential round trips
            = 750ms of pure network before Oracle does any work
            = 2.7s observed end to end

2 pods × Hikari default 10 = 20 connections
  (ADB Always Free caps sessions near 20, so the pool cannot grow)

20 ÷ 2.7s ≈ 7.4 bookings/sec sustainable
```

Admitting 200/s into a system that sustains 7.4/s exhausted the connection pool
and returned 500s — `HikariPool-1 - Connection is not available ... (total=10,
active=10, idle=0, waiting=9)`.

**Metering admission to what the database can actually take is what this gate is
for.** The 200 figure was chosen during design and never validated against real
hardware; 8 was measured.

Co-locating the database with the cluster would raise this by two orders of
magnitude — at ~1ms RTT the same transaction is roughly 50ms, giving ~400/s. The
OCI home region is immutable, so 8 is the honest number for *this* topology.

### `POLL_SECONDS=90` — polling once a second, not spinning

The script polls with a one-second sleep. An earlier version spun 30 times with
no delay, covering only ~9 seconds — written when admission was 200/s and
everyone was in within a second. At a realistic admit rate, later tickets were
not yet admitted when the loop gave up, so they booked unadmitted and took a 403
that said nothing about the system.

One second is also how a real client behaves.

### Pre-scaling to 10, rather than trusting the HPA

The arrival burst is about one second wide. The HPA polls every 15 seconds and
takes 15–30 to schedule pods, so **the spike is over before autoscaling reacts.**

For a *known* event you pre-scale. That is not a workaround — it is the correct
answer, and worth saying out loud during a demo. The HPA still earns its place
handling the sustained polling load afterwards and returning capacity when it
subsides.

---

## The database is the source of truth

**k6 undercounts.** One run reported 28 bookings while the database held 73.

A dropped response after a committed transaction reads as a failure and is not
one: the server took the lock, wrote the row, committed, and the connection died
before the `201` got back. The seats are taken; the client never learned.

So `bookings_created` is a **lower bound**. The authoritative result is:

```sql
SELECT capacity, seats_taken, capacity - seats_taken AS remaining FROM booking.slots;
SELECT COUNT(*) AS violations FROM booking.slots WHERE seats_taken > capacity;
SELECT status, COUNT(*), SUM(party_size) FROM booking.bookings GROUP BY status;
```

`violations` must be `0`.

### Then try to break it by hand

This is the demo's closing move. Bypass every line of Java:

```sql
UPDATE booking.slots SET seats_taken = seats_taken + 1;
```

```
ORA-02290: check constraint (BOOKING.CK_SLOTS_SEATS) violated
```

The database refuses. The invariant is not enforced by application logic you
have to trust — it is enforced *below* the application, where no code path and
no human at a SQL prompt can get around it.

---

## Thresholds

The run fails if either is crossed:

| Threshold | Why |
|---|---|
| `bookings_created: count>50` | A run booking *nothing* means admission is broken — yet every response would still be a well-formed rejection. Without this, a totally broken system reports 100% passed. |
| `http_req_failed: rate<0.01` | Only **server errors** count. `403` and `409` are correct answers for most contenders, so `http.setResponseCallback` treats everything below 500 as expected. Without it a healthy run reports ~89% "failure". |

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Every join returns `SOLD_OUT` | Ticket counter exhausted | `oc exec deploy/redis -- redis-cli FLUSHALL` |
| Every join returns `NOT_OPEN` | Drop window closed | `./deploy/scripts/open-drop.sh` |
| Every request returns 500 | Redis refusing writes | `oc logs deploy/redis`; check `redis-cli CONFIG GET save` is empty |
| 500s under load only | Connection pool exhausted | Lower `DROP_ADMIT_RATE`; see the calibration above |
| `bookings_created` is 0 | Slot not reset, or not seeded | Check `SELECT * FROM booking.slots` |
| Most bookings return 403 | Poll loop shorter than the queue | Raise `POLL_SECONDS` |
| `oc: Unauthorized` | Sandbox token expired | Console → your name → Copy login command |
| Pods `Pending` | Quota | `oc describe resourcequota compute-deploy` |

**Expired token blocks you, not the application.** Your pods keep serving
traffic. If it happens mid-demo, say so — it is not an outage.

---

## Seeding a slot from scratch

If `booking.slots` is empty:

```sql
INSERT INTO booking.slots (service_date, service_time, capacity, seats_taken)
VALUES (DATE '2026-10-01', '19:00', 250, 0);
COMMIT;
SELECT id, capacity, seats_taken FROM booking.slots;
```

Note the returned `id` — pass it as `SLOT_ID`.

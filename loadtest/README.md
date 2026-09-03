# Load test

Reproduces the 21:00 drop as a synchronized burst: 5000 virtual users arrive in
the same instant rather than ramping, because a ramp would be testing a spike
that does not exist.

```bash
docker compose up -d --build
./loadtest/reset.sh          # required before every run — see below
k6 run -e SLOT_ID=<id> loadtest/drop.js
```

Environment overrides: `GATE` (default `http://localhost:8080`), `SLOT_ID`
(default `1`).

---

## The run is single-use — reset before each one

**A second run without a reset does nothing, and the reason is by design.**

`queue:ticket` is a monotonic Redis counter, and the gate refuses any ticket
past `DROP_TICKET_CAP` (250). One 5000-user run therefore exhausts the cap
permanently. Every join in a subsequent run correctly returns
`409 SOLD_OUT`, no booking is attempted, and the test proves nothing.

This is the gate working, not failing — the drop really is sold out. But it
means the load test measures nothing on its second run unless both stores are
reset:

- **Redis** holds the ticket counter and the issued tokens.
- **Oracle** holds `seats_taken`, which stays at capacity from the previous run.

`reset.sh` clears both. Run it before each load test, and before demonstrating
the drop live.

---

## Thresholds

The run fails if either threshold is crossed:

| Threshold | Why |
|---|---|
| `bookings_created: count>50` | A run in which *nothing* was booked means admission is broken — but every response would still be a well-formed rejection. Without this, a totally broken system reports 100% passed. |
| `http_req_failed: rate<0.01` | Only **server errors** count. `403` (not admitted) and `409` (sold out) are correct answers for ~97% of contenders. |

Note the `http.setResponseCallback(...)` near the top of `drop.js`: it tells k6
to treat everything below 500 as an expected outcome. Without it, a perfectly
healthy run reports roughly 89% "failure", because k6 counts every 4xx as a
failed request by default.

A healthy run on a 250-seat slot books about **125** parties of two and rejects
the rest.

---

## Verifying the invariant

This is the step that matters, and it involves no application code — the
question is put directly to the database:

```bash
docker compose exec oracle \
  sqlplus -S booking/booking@//localhost:1521/FREEPDB1
```

```sql
SELECT capacity, seats_taken, capacity - seats_taken AS remaining FROM slots;
SELECT COUNT(*) AS violations FROM slots WHERE seats_taken > capacity;
```

`violations` must be `0`, and `seats_taken` must never exceed `capacity`.

Then try to break it by hand, bypassing every line of Java:

```sql
UPDATE slots SET seats_taken = seats_taken + 1;
```

```
ORA-02290: check constraint (BOOKING.CK_SLOTS_SEATS) violated
```

The database refuses. The invariant is not enforced by application logic you
have to trust — it is enforced below the application, where no code path and no
human at a SQL prompt can get around it.

### Reading the seat count

A full slot may finish at 250 **or** at 249, and both are correct. Parties are
two seats, so once one seat remains no further party fits:
`canAccommodate(2)` is false and the remaining contenders are refused. The
system declines a party that will not fit the remainder, not merely one that
exceeds capacity.

---

## Ports

Only `queue-gate` (8080) and Oracle (1521) are published to the host.

`booking-service` and `redis` are `expose`d on the compose network only. That
is deliberate and mirrors the OpenShift topology: the gate is the sole public
entry point, so the queue cannot be bypassed. Publishing either one breaks that
boundary — a reachable `booking-service` accepts bookings with no admission
token, and a reachable Redis lets anyone forge a token with a single `SET`.

If you need to reach one of them while debugging, add a `ports:` mapping
temporarily and take it out again.

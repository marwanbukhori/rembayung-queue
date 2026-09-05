# 09 — The demo console: giving a stranger the controls

**Covers:** why every visitor gets their own drop instead of a confirmation
dialog; why the queue token grew a drop id rather than the Redis key growing a
namespace; why one shared key and no accounts; why the cluster's limits are
drawn on the page rather than hidden behind it; the measured deployment; the
two bugs this console found that nothing else had; and what it does not do.

---

## The problem this phase actually had to solve

Everything before this phase is demonstrated by an operator: `open-drop.sh`,
`oc scale`, `k6 run` from a laptop, then `sqlplus` to read the seat count. That
sequence proves the system works and proves nothing to anyone who is not
sitting at that laptop.

The console exists so that a person who has been sent a link can drive the
thing themselves. That single change of audience — from the person who built it
to a person who has never seen it — is what every decision below falls out of.
A demo you run is a different artefact from a demo you hand over.

---

## Why per-visitor sandboxes replaced confirmation dialogs

The first design had one drop, one 250-seat slot, and a **Reset** button that
meant what reset has always meant here: flush Redis, `UPDATE slots SET
seats_taken = 0`, restart the gate. Shared state, one copy of it, and every
control that touched it needing a dialog to ask whether you were sure — because
you might be resetting the drop somebody else was halfway through.

The design that shipped gives each visitor a drop of their own: its own slot
row in Oracle, its own Redis ticket counter, its own admission window, its own
load Job. Nothing is shared and nothing collides.

The consequence worth naming is not that resets became safe. It is that
**"reset" stopped meaning `DELETE FROM bookings` and started meaning "create a
new one."** A destructive verb turned into a constructive one, and most of the
confirmation dialogs disappeared — not because they were suppressed, but
because the blast radius they were guarding against no longer existed. A dialog
is a warning that an action reaches further than the person taking it can see.
Shrink the reach and there is nothing to warn about.

Sessions are unlimited, which is affordable for the same reason. Idle drops
expire out of Redis after 30 minutes on their own key TTL, refreshed on every
read; their slot rows carry `sandbox_expires_at` and are reaped by
`SandboxSweeper`. A visitor can create and abandon drops all afternoon and
leave nothing behind that anyone has to clean up.

---

## Why the token carries its own drop id

Once drops multiply, every queue operation needs to know which drop a token
belongs to. There were two obvious places to put that: namespace the Redis key
(`admit:{dropId}:{token}`), or put the drop id in the stored value.

The key was the obvious choice and it was wrong, because tokens are already
random UUIDs — globally unique without any namespacing at all. Namespacing them
would have added a component that buys no uniqueness and costs an API change:
every endpoint that takes a token would have needed the drop id alongside it.

So the value became `{dropId}:{ticket}` instead, and the token stayed a bare
UUID. What that bought is precise: **`GET /queue/{token}` and `POST /bookings`
kept their exact existing shapes.** Not "compatible" shapes — the same ones.
`AdmissionService.resolve()` splits the stored value and loads the drop it
names, so `position()` and `consume()` need no extra parameter. Only
`POST /queue` changed at all, and only by gaining an optional `?drop=`.

### The rolling-deploy consequence, which is the part that is easy to miss

Tokens issued before this change stored a bare `{ticket}` with a live TTL. A
rolling deploy replaces pods one at a time, so for the length of the rollout
**both encodings are in flight simultaneously** — old pods still writing bare
numbers, new pods reading whatever they find. Rejecting the old shape would
have handed a 403 to somebody queueing at 21:00 who did nothing but be
mid-queue while a deploy happened.

`resolve()` therefore accepts a bare number as a ticket in the default drop,
which is the only drop that existed when such a token could have been written.
The branch carries a comment saying exactly when it is safe to delete: one
`ticketTtl` after the rollout completes, at which point no token of that shape
can still be alive. Compatibility code with no stated end date becomes
permanent by default; this one has a stated end date.

---

## Why one shared key, and why the tiers were deleted

An earlier draft of this phase had three access tiers — public read, visitor,
operator — with per-request ownership checks so one visitor could not touch
another's drop.

All of it was removed, and the reason is not that it was hard. It is that the
audience is **two people**: the repository owner and whoever they send the link
to. Tiers, sessions, accounts and ownership checks are machinery for protecting
users from each other, and here there are no users to protect from each other.
Every one of those mechanisms would have been code to keep correct forever in
exchange for a guarantee nobody needed. The drop id *is* the session: whoever
holds it can watch that drop, and the console stores nothing on anyone's behalf
between requests.

What replaced it is one key, checked by `KeyFilter` across `/api/*` — reads
included. There is no reason to leave reads open when there are two users, and
one rule is easier to reason about than two. The key may travel as `?key=` in
the query string as well as the `X-Console-Key` header, because the owner sends
a *link*, and a header cannot be put in a link. The cost is honest and small: a
demo key that opens a dashboard lands in browser history and in any access log
in front of the Route, and can be rotated by editing one Secret.

Two things are deliberately outside the filter. The static page — the browser
fetches its own script and stylesheet without the query string that opened the
page, so gating them would break the link at the moment it worked, and the
shell renders nothing on its own anyway. And the management port, 9090, which
carries `/actuator` and is not on the public Route at all.

### Why `MessageDigest.isEqual` and not `equals`

`String.equals` returns on the first differing byte, so how long a rejection
takes is a measurement of how much of the key the caller got right — leak the
prefix, and the rest follows by repetition. Nobody is going to sit and time ten
thousand requests against a demo dashboard. But "nobody would bother" is the
reasoning behind most of the timing attacks that eventually happened, and the
fix is one method call that compares every byte whatever it finds.

---

## Why the constraints are rendered rather than hidden

The instinct with a public demo is to make sure nothing ever looks broken:
disable the controls that could fail, cap the inputs, hide the errors. That
instinct would have deleted the most interesting thing this project has to
show.

**A `Pending` Job against a real quota is a demonstration, not a failure.** It
is the cluster refusing work it does not have the CPU for, which is the same
behaviour the whole admission-control story is about, one layer down. So the
constraints panel draws it rather than hiding it: quota used against hard, with
the per-workload consumers that add up to the used figure; both HPAs with
current and desired replicas and their own `ScalingLimited` message quoted
verbatim; and the connection-pool arithmetic spelled out.

Verbatim matters. A paraphrase of a Kubernetes condition is a second place for
the explanation to be wrong, and the visitor cannot check it against anything.
The real string can be pasted into a search engine.

### Why a Pending reason is read from three places

Because a quota refusal and an unschedulable pod are recorded in genuinely
different objects, and a console that looked at only one of them would report
"Pending" with no reason attached — the least useful possible answer:

1. **The pod's `PodScheduled=False` condition.** Where `Insufficient cpu` is
   written when a pod exists but nothing will take it.
2. **A `FailedCreate` Event on the Job.** When the quota rejects the pod
   outright, **no pod is ever created**, so there is nothing to carry a
   condition. The only record of `exceeded quota: compute-deploy, requested:
   ...` is an Event about the Job.
3. **The Job's `Failed` condition.** For a run that started and then died.

The first two look identical from the outside — a Job sitting at zero active
pods — and are recorded nowhere near each other. That is the whole reason the
Role grants `events`.

---

## The CPU quota cannot bind, and saying so is the point

It would be neater if the constraints panel showed a demo running out of CPU.
It does not, and the measured numbers say why plainly:

| | CPU |
|---|---|
| In use at rest | **700m** of 3000m |
| Scaling `queue-gate` to 10 replicas adds | **800m** |
| A 3000-VU load Job adds | **800m** |
| Still free after all of that | **700m** |

You cannot exhaust this quota by driving this console, however hard you drive
it. **The scarce resource was never CPU.**

The constraints that actually bind are both below the scheduler:

- **The database session budget.** `booking-service` runs a Hikari pool of 5
  per replica, and its HPA maxes at 4 replicas: `4 × 5 = 20` sessions, sitting
  exactly at Oracle Autonomous Database Always Free's ~20-session cap. Adding
  replicas past that does not add capacity; it takes the database down. The
  arithmetic is `docs/notes/08`'s and it is the real ceiling.
- **The row lock beneath it.** Bookings against one slot serialise on a
  pessimistic lock, holding throughput near **one booking per second**
  regardless of how many pods, connections or virtual users are pointed at it.

And that is precisely why this system has admission control rather than a
higher autoscaler ceiling. A queue exists because the bottleneck is somewhere
you cannot buy your way past by scaling the tier in front of it. A console that
showed CPU as the limiting factor would be teaching the opposite lesson to the
one the architecture embodies — so the panel reports the CPU headroom honestly
and puts the pool arithmetic next to it.

---

## Why load runs in the cluster, and what that costs

A stranger cannot install k6 on their laptop, and the console generating load
in its own process would mostly measure the console. So **Send load** creates a
`Job` running `grafana/k6`, which is the same tool the Phase 3 measurements
used, shows up in `oc get jobs` where anyone can check it, and — the part that
matters — has to ask the scheduler for CPU like everything else, so it can be
refused.

`activeDeadlineSeconds` is set on the **Job**, not the pod template, and that
placement is load-bearing twice. It stops a run outliving the person who
started it, and it keeps the pod out of the `Terminating` quota scope: this
namespace carries `compute-build` (Terminating) and `compute-deploy`
(NotTerminating), and a pod is Terminating only when its own spec carries the
deadline. On the pod template the run would spend a separate, empty budget and
could never be refused — quietly deleting the point of the constraints panel.

### Why the default is 200 VUs

Not resource caution. 200 is the **measured ceiling of usefulness**, from the
Phase 3 ladder:

| Offered | Arrived | Failed |
|---|---|---|
| 200 | 200 | 0% |
| 1000 | 662 | 75% |
| 3000 | 818 | 92% |

Past 200 the edge sheds load, so a bigger number measures the ingress path
rather than the queue. Higher counts are still offered, and labelled as edge
shedding rather than forbidden — hiding the control would hide the finding, and
the finding is one of the more interesting things Phase 3 produced.

### The cost, stated plainly

The Job talks to `queue-gate` over the ClusterIP Service, so **it skips the
public Route entirely.** It exercises the queue, the admission rate and the
seat invariant. It does not exercise the ingress path. A clean run here is not
evidence that the edge would have carried the same traffic — and the table
above is the evidence that it would not.

---

## The measured deployment

From the verification run:

- `grafana/k6:0.53.0` pulled in **3.268s** on its first pull in this cluster.
- The Job completed **1/1 in 31s**, with **200/200 iterations**.
- The `compute-deploy` quota moved from **700m to 1000m** while it ran — proof
  the run competes for the same budget as the Deployments rather than
  occupying a scope of its own.

Verified from outside the cluster, through the public Route:

- `/api/state` with no key → **401**
- The page → **200**
- `/actuator/health` → **404**

That last one is the one worth checking rather than assuming. The management
port is not on the Route, and the 404 is what proves it structurally rather
than by reading YAML.

---

## Two bugs the console found that nothing else had

Both belong in this note rather than a changelog, because the lesson is not
either bug. It is that both were only reachable by *running* things — one by
running load inside the cluster for the first time, one by running the console
outside it for the first time. Neither is the kind of defect a review finds.

### 1. The overload response was 500, not 503

The first load run ever executed inside this cluster returned **HTTP 500 for
188 of 200 bookings**. `loadtest/drop.js` asserted 503, and the project's own
notes described overload as producing 503 with `Retry-After`. Both were
describing behaviour the code did not have.

`RestExceptionHandler` caught `DataAccessResourceFailureException`, on a
comment's claim that it is the superclass of `CannotCreateTransactionException`.
Verified against the running classpath, it is not:

```
CannotCreateTransactionException
  -> org.springframework.transaction.TransactionException
  -> org.springframework.core.NestedRuntimeException
```

It lives in `org.springframework.transaction` and shares no ancestor with
`org.springframework.dao` below `RuntimeException`. So the handler covered the
case where code *already inside* a transaction asks for a connection, and
missed the one `@Transactional` throws when it cannot get a connection to
**open** the transaction — which is the overwhelmingly common shape under pool
exhaustion, and therefore the only one that matters here.

Two things about the severity, in both directions. The invariant never moved:
`seatsTaken 24, oversold 0` throughout, so no seat was ever at risk and nothing
was oversold. But a documented behaviour was simply false, and a 500 tells a
client to give up where a 503 with `Retry-After` tells it to come back — the
difference between a queue that sheds politely and one that looks broken.

Fixed by handling both types. The regression test was verified to fail against
the old handler rather than merely written and assumed meaningful.

The reason no test caught it is worth keeping: nothing in the suite exhausts a
connection pool, and no laptop run had ever pushed hard enough to. The bug was
sitting in a path that only exists under real load, which is exactly the path
this console made routine to reach.

### 2. A hardcoded namespace in the UI

The console header printed `ns/rembayung` — a namespace that does not exist and
never has — while the backend was reading `marwanbukhori-dev`.

It is invisible in the cluster, because the Deployment supplies
`CONSOLE_NAMESPACE` and everything that matters reads that. It was found only
by running the console on a laptop, where the two values diverge and the page
starts describing a cluster nobody has. The API now reports the namespace it
actually read from, and the page displays that value rather than a literal.

A display-only string is the easiest kind of wrong thing to leave in place, and
the hardest to notice, because nothing fails. It just quietly says something
untrue on a page whose entire purpose is to be believed.

---

## Documentation rendering, and why traversal is impossible rather than filtered

The console serves the project's notes, specs and plans — 23 markdown files
baked into the image at the time of writing, this note making 24. They are
enumerated **once, at startup, into a fixed map**, and both endpoints read only
from that map. The request path is never appended to a directory to build a
file path.

So an id nobody enumerated — a traversal attempt, a typo, anything — is simply
not a key, and resolves to 404 the same way a misspelling would. There is
nothing to filter because there is no path left to construct. This is worth
stating as a design property rather than a validation step: a blocklist is a
guess about what an attacker will send and eventually gets bypassed; a map
lookup cannot be, because the set of reachable files is closed before any
request arrives.

---

## What is not done

**The operator surface's buttons are described, not wired.** Deploy, roll back
and scale are drawn and explained on the page; none of them is connected to
anything. The console's ServiceAccount deliberately cannot patch Deployments —
that path belongs to CD, running as `rembayung-cd` — so wiring them means
deciding how a public page triggers CD, which this phase did not do. The panel
currently documents an intention.

**Two NetworkPolicies enforce nothing.** `booking-service-from-gate-only` and
`redis-from-gate-only` read as though they restrict ingress to `queue-gate`,
and in this namespace they do not, because NetworkPolicy is **additive and has
no deny**: the sandbox ships its own `allow-same-namespace` policy, and a pod
selected by both is reachable by the union of what they permit. Every pod in
the namespace can reach both services. The policies are correct in themselves
and would enforce what they say in a namespace without that blanket allow —
which is precisely what makes them dangerous to read at face value here. They
are documentation of intent that currently looks like enforcement.

**The sandbox overlay has no console tag pin.** `deploy/base/console/deployment.yaml`
leaves the image untagged on purpose — two systems own that field and a third
would silently disagree — but the overlay pins tags only for `queue-gate` and
`booking-service`. A fresh `oc apply -k` bootstrap of a new namespace would
therefore give the console `:latest`, which is the one thing this project has
none of in the registry and has argued against everywhere else
(`docs/notes/07`). The bootstrap path for a new sandbox is incomplete until
that pin exists.

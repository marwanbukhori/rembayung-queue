# 08 — Observability: three tools, on the clock they can't extend

**Covers:** why the stack is Prometheus, Dynatrace and Splunk rather than one
of them; what the sandbox's SCC actually rules out and what that costs; why
Prometheus is the piece that has to outlive the other two; the plan defects
this phase's own execution found before any of it worked; the two production
bugs systematic debugging turned up along the way, both worse than anything
this phase shipped; and the connection-pool arithmetic that ties autoscaling
to the database's session cap.

---

## Why three tools, not one

Prometheus does metrics and alerts. Dynatrace does traces and topology.
Splunk does logs and SPL. That is a division by job, not a shopping list —
three tools each doing one job well beats one tool doing three jobs badly,
and it would also beat three tools each doing the same job redundantly. A
single pane of glass sounds appealing until you notice it means teaching one
product to be good at everything; nothing here tries to.

The three are not peers, though, and the rest of this note is mostly about
why. Prometheus is permanent — it is the cluster's own stack, on the
`monitoring.coreos.com` CRDs already running in every OpenShift project, and
it costs no quota beyond what a `ServiceMonitor` and a `PrometheusRule`
already occupy. Dynatrace and Splunk are both **trials**: 15 days and 14
days respectively, started to get real traces and real log search into this
project without paying for either. When they expire, the demo cannot depend
on them, and it doesn't — see "Why Prometheus is the backbone" below.

---

## The SCC evidence: what `restricted-v2` rules out

Before choosing "application-only" for Dynatrace and "HEC over HTTP" for
Splunk, both were checked against what this sandbox actually grants. Queried
directly:

```
restricted-v2 is the only SCC available.
privileged:  denied
hostaccess:  denied
anyuid:      denied
get nodes:   denied
```

That single fact eliminates two whole categories of instrumentation, not as
a matter of preference but as a matter of what the API server will accept:

- **OneAgent full-stack** — Dynatrace's usual deployment as a DaemonSet with
  host and kernel access, so it can see every process on every node — needs
  `privileged` and `hostaccess`. Neither is available. `get nodes` being
  denied removes even a node-level fallback.
- **The Splunk universal forwarder** — the usual way to ship OS and platform
  logs — needs the same host-level reach to tail files outside its own
  container.

Neither is negotiable with a different YAML shape; both need capabilities
this SCC does not grant to anything in this namespace. That leaves exactly
one option for either vendor: instrument the application process itself,
from inside its own container, with no visibility into the node it runs on.
That is what got built — Dynatrace's OneAgent in **application-only** mode
(Task 6), attached per-JVM with `-agentpath`, and Splunk reached over HTTP
Event Collector from the application's own logging framework (Task 5), never
by tailing a file.

**Say the cost plainly rather than around it: there is no infrastructure
monitoring on this cluster at all.** Nothing here watches node CPU, node
memory, kubelet health, container runtime behaviour, or anything below the
JVM boundary. Both agents see exactly what the process they're attached to
does and nothing else. `restricted-v2` did not make that harder to get —
it removed it as an option. Prometheus's `kube_deployment_status_*` and
`kube_endpoint_address_available` series (used by `RedisDown` and
`ServiceHasNoEndpoints` below) are Kubernetes-object metrics from
kube-state-metrics, not node telemetry; they tell you a Deployment has zero
ready replicas, never why the node underneath it is unhealthy.

---

## The quota evidence: why Splunk isn't self-hosted

The obvious alternative to a 14-day Splunk Cloud trial is running Splunk
Enterprise in the cluster. It was not tried, and not because self-hosting is
hard — because the numbers don't fit.

Splunk Enterprise's own sizing guidance wants roughly **2 CPU and 4GB** for
even a minimal single-instance deployment. The namespace's total compute
budget is **3000m**, and the spike demo alone — `queue-gate` pre-scaled to
10 replicas, `booking-service` to 4, both under HPA — needs **1900m** of
that just to run. There is not 2000m left over for a log platform, let alone
2000m plus its own memory footprint against a 30Gi RAM cap already shared
with everything else.

This is the same constraint that already removed two other things from this
project on the same grounds: **Ansible Automation Platform**, which arrived
consuming 1950m of the 3000m quota and had to be deleted before the
autoscaling demonstration could run at all (`docs/notes/07`), and the
original **Jenkins** plan, dropped because an in-cluster server would have
competed for the same CPU the spike demo exists to spend. Splunk-in-cluster
would have been the third instance of the identical failure: a permanent
resident that eats the budget the temporary demonstration needs. A SaaS
trial costs nothing from this namespace's quota, which is the actual reason
it was chosen over self-hosting — not convenience.

---

## Why Prometheus is the backbone, not a fourth wheel

Dynatrace's trial expires around **2026-09-19**. Splunk's expires around
**2026-09-18**. Both are hard stops — after those dates, traces and log
search on this cluster go away, whatever else is true about the code.

Prometheus does not expire. It is the in-cluster CR-based stack that ships
with OpenShift's user-workload monitoring, and nothing about it depends on a
vendor account. That is why the alerting that has to still be watching this
system in a month lives there and nowhere else: `PrometheusRule` (Task 3),
not a Dynatrace problem-detection policy or a Splunk saved search. If the
choice had gone the other way — alerting built on whichever tool had the
nicer UI — the alerting itself would have had an expiry date, which defeats
the reason alerting exists. Prometheus is not one of three equal legs; it is
the one the other two are allowed to disappear out from under.

The `RedisDown` rule exists because of exactly this kind of gap. Redis
drifted to zero replicas and the public Route served 503 for roughly nine
hours before anyone noticed — a real incident, documented in
`docs/notes/07`, and it happened with none of these three tools in place.
Nothing was watching. `RedisDown` is that hole closed with the one tool that
cannot itself expire mid-demo.

---

## `rembayung_slot_oversold`: the headline metric

The gauge that matters most in this whole stack is the smallest one:

```
rembayung_slot_oversold{slot="1"} 0.0
```

Read right now, and it must read `0` forever. It is not a soft indicator —
it is deliberately **redundant** with the database's own
`ck_slots_seats` CHECK constraint (`docs/notes/02`), which makes
`seats_taken > capacity` unrepresentable at the storage layer. Two
independent things assert the same fact for a reason: if the CHECK
constraint is the only proof, a bug in the constraint itself, or a row
written by something that bypasses it, has no second witness.

`SlotOversold`'s `expr: rembayung_slot_oversold > 0` fires with `for: 0m` —
no debounce, unlike every other rule in this file. That is intentional. If
this metric ever moves off zero, there are exactly two possibilities:
either the database invariant this project's whole pitch rests on has
actually broken, or the metric itself is lying about a database that is
still fine. Both of those deserve waking someone up immediately, and
neither benefits from waiting two minutes to see if it's a blip — a
transient blip on this specific series is not a category that exists.

---

## Seven plan defects, found by execution, not review

This phase's plan was reviewed before it was executed. Review caught
nothing here. Every one of the following was found by actually running the
code — building it, starting it, or shipping a real event through it — and
each is recorded because the pattern, not any single defect, is the lesson:
a plan that reads correctly and a system that behaves correctly are
different claims, and only one of them was checked by reading.

In the order they were hit:

1. **`Slot.getId()` NPEs on an unpersisted entity.** The plan's
   `SlotStateProvider.stateFor` read the slot id back off the entity
   `findById` returned; on the brief's own test fixture — a `Slot` built
   with `new Slot(...)` and never persisted — `getId()` is a boxed `Long`
   that is `null`, and unboxing it throws. Fixed by using the id already in
   hand as the method argument instead of reading it back off the entity —
   the same value for anything `findById` actually returns, so production
   behaviour is unchanged.

2. **`DropProperties` was written with 5 constructor arguments; the record
   has 7.** `(opensAt, closesAt, seats, ticketCap, admitRate,
   admissionWindow, ticketTtl)`. The test fixture was written against an
   assumed shape rather than the real one and simply did not compile.

3. **`splunk-library-javalogging` is not in Maven Central.** It resolves
   only from Splunk's own Artifactory
   (`https://splunk.jfrog.io/splunk/ext-releases-local`), which both poms
   now declare as a repository. A non-Central source is now a build-time
   trust dependency for both services — acceptable for a first-party vendor
   artefact, but a real change to what the build relies on.

4. **Logback's `<if>` was thought to need Janino** to evaluate its
   condition attribute, and Janino was added on that assumption.

5. **Logback's `<if>` does not work at all in logback 1.5.38** — the
   version Spring Boot 4.1.1 actually pulls in — regardless of whether
   Janino is present. Every variant tried (`condition="..."` with Janino,
   the newer `<condition class="...">` element, `<if>` nested inside
   `<root>`, `<root>` nested inside `<then>`/`<else>`) parses cleanly,
   starts cleanly, and then executes **neither branch**. `<springProfile
   name="splunk">` — Spring Boot's own mechanism — was used instead, and
   Janino was removed from both poms as a dependency that had stopped
   solving anything.

6. **The Splunk appender exposes `setLayout`, not `setEncoder`.** The
   plan's `<encoder class="...LogstashEncoder">` block is silently accepted
   by Joran as an unknown property and simply ignored, leaving the
   appender's layout `null`. Fixed with `<layout
   class="net.logstash.logback.layout.LogstashLayout">`, from the same
   `logstash-logback-encoder` artefact Task 4 already depends on.

7. **The Splunk Cloud trial stack serves a self-signed certificate** on the
   HEC port, 8088. With certificate validation on, every POST failed with
   `SSL certificate problem: self-signed certificate in certificate chain`;
   with it off, identical requests succeeded. `disableCertificateValidation`
   is `true` here, with a comment that a production stack must set it back
   to `false` and install the stack's CA in the container's trust store
   instead — this setting is a trial-environment accommodation, not a
   recommendation.

**Defects 5, 6 and 7 all failed silently.** Each produced a clean Maven
build, a clean startup with no ERROR and no WARN in the application's own
log, and zero events reaching Splunk. That is the specific point worth
taking away: none of the three would have been caught by a code review, and
none would have been caught by a passing test suite, because nothing about
the failure surfaces as a stack trace, a non-zero exit code, or a red build.
It surfaces only as an absence — the thing that never showed up — which is
the same shape of failure the whole logging story exists to make visible
for the *application*, and here it was the logging pipeline's own failure
mode. Each was only found by shipping a real event through a real listener
and checking the far end.

---

## Two production bugs, found by systematic debugging

Two defects were found in code this phase did not write — both predating
Phase 6, both far more consequential than anything above, and both found
only because the observability work this phase built gave something to look
at.

### The expiry sweeper had never worked

`ExpirySweeper.sweep()`, the `@Scheduled` entry point, called
`sweepExpired()` on `this`. Spring applies `@Transactional` through a
proxy, and a call from one method of a bean to another goes straight to
`this`, never through the proxy. `sweepExpired()` carried `@Transactional`
on paper, but the caller had bypassed the one thing that would have made it
apply — so every scheduled run executed with **no active transaction at
all**, and `findByIdForUpdate`'s pessimistic lock requires one.

The result: **197 `"No active transaction"` errors on one production pod**,
and in that same window, **zero** `"Expired N unpaid bookings"` lines were
ever written. Unpaid holds never expired. Their seats never returned to
inventory. This had been true since the sweeper first shipped, running
silently every 30 seconds, for the whole span from Phase 1 through Phase 6.

The tests never caught it because all three of `ExpirySweeperTest`'s cases
call `sweepExpired()` directly on the Spring-injected bean — which **is**
the proxy, so those calls got a transaction and passed. Production entered
through `sweep()`, a path the tests never exercised. A test suite that is
green while exercising a path production does not take cannot fail the way
production fails.

Fixed by moving `@Transactional` onto `sweep()` itself, so the transaction
starts before `this.sweepExpired(...)` is ever called. The regression test
added enters through `sweep()` specifically — `sweepRunsInsideATransaction`
— and was verified to actually fail with `No active transaction` when the
fix is reverted, not merely written and assumed to be meaningful.

### A startup/liveness race

Booking-service's startup, instrumented with the Dynatrace agent, was
measured at **61.6s, 56.8s and 59.3s** across separate rollouts. The
liveness probe kills at roughly **70s** — `initialDelaySeconds: 40` plus
three failed probes at `periodSeconds: 10`. Those numbers should never
collide, and on one rollout they didn't: the pod came up and stayed up.
On the very next rollout, with the same manifest and the same image, it
did — a **race**, not a deterministic failure, which is the worse kind to
chase because "it worked five minutes ago" is not evidence it will work
again.

Fixed with a `startupProbe` (`periodSeconds: 5`, `failureThreshold: 40`)
rather than a longer `initialDelaySeconds`. The difference matters: a
startup probe holds liveness and readiness off until the process answers
once, then hands over to the tight, short-interval liveness probe — so a
slow start is tolerated without paying the cost of a longer fixed delay on
*every* restart, including the fast, ordinary ones. Widening
`initialDelaySeconds` to cover the slow case would have made a genuinely
hung process take on the order of 200s to be detected and killed, every
single time, to protect against a startup that is normally over in a third
of that.

---

## The connection-pool arithmetic

The sharpest systems point in this project sits in one Hikari setting.

Hikari's connection pool is **per JVM**. Every replica of `booking-service`
holds its own pool, independent of every other replica's. That single fact
means the number that actually matters against the database is not "the
pool size" but:

```
sessions = replicas × maximum-pool-size
```

The HPA for `booking-service` is allowed to scale to **4 replicas**. At
Hikari's *default* pool size of 10, that is **40 sessions** — against an
Oracle Autonomous Database Always Free tier session cap near **20**. Under
that configuration, the exact event that triggers autoscaling — load
crossing the CPU threshold at the 21:00 spike — is the event that would
have exhausted the database's session budget and taken it down. Autoscaling
would have caused the outage it exists to prevent, at precisely the moment
it was supposed to help.

Fixed by lowering `maximum-pool-size` to **5**, so that `4 × 5 = 20` sits
exactly at the cap rather than double it. `deploy/base/booking-service/hpa.yaml`
and `booking-service/src/main/resources/application.yml` both carry comments
saying the two numbers must be changed together, because either one changed
alone reopens the same arithmetic.

Worth stating plainly, because it's counterintuitive: **more replicas do not
raise booking throughput.** Throughput is bounded by one pessimistic row
lock on the slot being booked, serialising at roughly **one booking per
second** regardless of how many pods are running. What extra replicas buy
is HTTP-layer concurrency — more requests accepted, queued, and answered
concurrently — and resilience against a pod loss, not a faster path to
selling the 250th seat.

---

## The one-computation rule

`SlotStateProvider` (booking-service) and `QueueStateProvider` (queue-gate)
are each the **only** place their respective state is computed. `BookingMetrics`
and `GateMetrics` do no arithmetic of their own — they project the accessors
of `SlotState` and `QueueState` through Micrometer gauges. There is no
second code path anywhere that recomputes seats remaining, or tickets
admitted, by any other route.

This is why Phase 8's UI is specified to call these two types directly
rather than write its own queries against the slot table or the Redis
ticket counter. Every consumer — a Prometheus scrape, an alert expression,
and eventually a UI polling endpoint — reads the same computation. A second
implementation of "how many seats are left" is a second place for that
answer to drift from the first, and the two here already carry properties a
naive rewrite would have to rediscover: `SlotStateProvider` reads with
`findById`, never `findByIdForUpdate`, so a metrics scrape never takes the
pessimistic lock real bookings serialise on — a monitor that contended with
the thing it monitors would be its own kind of incident.

`QueueStateProvider` adds one more property worth being explicit about: it
memoises its Redis read and clock read for **500ms**, deliberately shorter
than any scrape interval. Without it, each of the three queue gauges
(`tickets_issued`, `admitted`, `waiting`) independently reads Redis and the
clock, and because `admitted` is a function of the clock, three
independent reads straddling a second boundary can publish an `admitted`
that does not match the `admitted` implicitly used to derive `waiting` —
a dashboard where `issued - admitted != waiting`, on a project whose entire
claim is that these numbers can be trusted. The 500ms window unifies the
three reads **within** one scrape without ever holding a value stale
**across** two scrapes, so Phase 8 inherits consistency for free rather
than having to re-derive it.

---

## The Splunk failure policy: async, bounded, drop on full

The Splunk appender runs behind an `AsyncAppender` with `neverBlock=true`
and `discardingThreshold=0`. Read those two settings against what they
sound like they do, because the names are easy to get backwards:

- `neverBlock=true` means that when the appender's internal queue is full,
  the thread producing the log event **drops it and moves on** rather than
  waiting for room. This is the one that protects the booking path — a
  logging pipeline is never allowed to make a booking request wait.
- `discardingThreshold=0` disables logback's *default* behaviour, which is
  to start silently dropping INFO-and-below events once the queue reaches
  80% full, to protect ERROR and WARN events from being crowded out.
  Setting it to 0 keeps *more* events, not fewer — it removes the
  automatic downgrade rather than adding one.

Together: losing log lines is an acceptable outcome; adding latency to a
booking is not. That trade was measured, not assumed. With Splunk
genuinely unreachable, 300 timed `POST /queue` requests averaged
**10.23 ms/req**, against a **9.15–9.93 ms/req** baseline with no Splunk
profile active at all. The gap sits inside the spread between the two
baseline runs themselves — indistinguishable from noise. No
`OutOfMemoryError`, no thread exhaustion, no appender error surfaced to the
application. The pipeline drops what it cannot ship and the request path
never notices.

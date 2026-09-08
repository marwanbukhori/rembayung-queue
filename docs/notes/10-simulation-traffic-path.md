# 10 — The simulation traffic path: one click, traced to the row lock

**Covers:** every hop a simulation makes, from the button in the browser to the
`UPDATE` on a locked Oracle row; which service owns which path and why two of
them look confusingly alike; the boundary each hop crosses and what enforces it;
what the run deliberately does not exercise; where the run shows up in Splunk,
Dynatrace and Prometheus; and one claim in this repo that the platform quietly
overrides.

---

## Two API surfaces, and the name collision between them

The single most common misreading of this system is that the browser asks
queue-gate to generate load. It does not. There are two `drops` paths on two
different services:

| Path | Service | Reachable from | File |
|---|---|---|---|
| `/api/drops/{id}/load` | console | the internet | `console/…/ops/LoadOps.java:88` |
| `/api/drops` | console | the internet | `console/…/ops/DropOps.java:40` |
| `/api/docs` | console | the internet | `console/…/web/DocsController.java:42` |
| `/queue` | queue-gate | the internet | `queue-gate/…/web/QueueController.java:12` |
| `/bookings` | queue-gate | the internet | `queue-gate/…/web/BookingProxyController.java:16` |
| `/internal/drops` | queue-gate | pods only | `queue-gate/…/web/InternalController.java:29` |

`/api/…` is the console's own surface, spoken by a browser. `/internal/…` is
queue-gate's, spoken by another pod. The console calls the second; it never asks
queue-gate for load.

The console does not generate load either. It asks Kubernetes for a Job, and the
Job is what generates load. That indirection is the whole design, and the reason
is in the next section.

---

## The trace

```
browser
  │  POST /api/drops/{dropId}/load  { vus }
  ▼
Route (edge TLS)  →  Service console:8080  →  console pod
  │  KeyFilter: a write, so ?key= is required
  ▼
LoadOps.start()                                   console/…/ops/LoadOps.java:162
  │  1. replaceFinishedRun(jobName)   clear the previous tombstone
  │  2. applyScript()                 write drop.js into ConfigMap console-k6-drop
  │  3. jobs().create(...)            ask the API server for a Job
  ▼
Kubernetes API server
  │  authorises the console ServiceAccount: batch/jobs get,create,delete
  ▼
Job controller  →  Pod  →  scheduler
  │  the pod requests cpuMillis(vus); compute-deploy may refuse it
  ▼
kubelet on some node  →  CRI-O  →  k6 container
  │  k6 run /scripts/drop.js
  │  env: GATE, DROP_ID, SLOT_ID, VUS
  ▼
Service queue-gate:8080  (ClusterIP — the Route is not involved)
  │  POST /queue                      a ticket, not a seat
  │  POST /bookings  X-Admission-Token
  ▼
queue-gate
  │  AdmissionService.consume(token)  Redis GETDEL, single use
  ▼
Service booking-service:8081
  │  POST /bookings
  ▼
BookingService.book()                 booking-service/…/service/BookingService.java:108
  │  SELECT … FOR UPDATE on the slot row
  ▼
Oracle
     ck_slots_seats CHECK (seats_taken >= 0 AND seats_taken <= capacity)
```

---

## What enforces each boundary

Every hop is refused by something other than application code wherever that was
possible. This is the part worth explaining out loud: the checks are not `if`
statements this project has to keep correct.

**Browser to console.** `KeyFilter` requires `?key=` for writes. Reads are open
so a stranger sent the link sees a working system; starting a run is gated
because it puts real load on a real namespace.

**Console to Kubernetes.** The console authenticates as its own ServiceAccount,
which holds `get,create,delete` on `batch/jobs` and `get,create,update` on
configmaps, and nothing else (`deploy/base/console/rbac.yaml`). It cannot create
a Deployment, read a Secret, or reach any other resource. A visitor pressing the
button can start a Job and can do nothing further, and that is enforced by the
API server.

**A second click.** The Job is named after the drop, so a second create is
rejected by the API server as a duplicate name. There is no counter in the
console to keep correct, and no race between two browser tabs.

**The scheduler.** The k6 pod asks for CPU like every other pod. If
`compute-deploy` has no headroom the pod stays `Pending` and the event says why.
A run that will not schedule is not a defect in this feature. It is the feature:
the console exists to draw the cluster's limits rather than hide them.

**k6 to queue-gate.** The ClusterIP Service, pod to pod. No Route, no TLS
termination, no router in the path.

**Admission.** `X-Admission-Token` is consumed with Redis `GETDEL`, which is
atomic, so there is no check-then-act window in which two callers redeem the same
token. `BookingProxyController` also verifies the token was admitted *for that
slot*: a valid token for a visitor's own sandbox, replayed against the canonical
250 seats, passes every upstream check and must still be refused.

**The seat count.** `SELECT … FOR UPDATE` serialises every mutation of
`seats_taken` for one slot, and the CHECK constraint is the backstop underneath
it. Application bugs cannot get past the constraint, which is why it lives in the
schema rather than in a service method.

---

## Why `activeDeadlineSeconds` sits on the Job

This namespace carries two CPU quotas with different scopes: `compute-build`
(Terminating) and `compute-deploy` (NotTerminating). **A pod is Terminating only
when its own spec carries a deadline.**

Setting `activeDeadlineSeconds` on the Job bounds the run without marking the pod
Terminating, so the run is charged to `compute-deploy` and can be refused. Moving
that same field onto the pod template would move the run into a separate, nearly
empty budget where it could never be refused, and the constraints panel would
become decoration. The placement is load-bearing twice: it stops a run outliving
the person who started it, and it keeps the run inside the budget the page is
about.

`backoffLimit: 0` for a related reason. A failed load run is a result, not a
transient error. Kubernetes would otherwise helpfully re-run it.

---

## What the simulation does not exercise

**The public Route.** The Job talks to the ClusterIP Service, so the ingress path
is not in the trace at all. A clean run is evidence about the queue, the
admission rate and the seat invariant. It is not evidence that the edge would
have carried the same traffic, and the page says so.

The Phase 3 ladder measured that the edge would not:

| offered | arrived | failed |
|---|---|---|
| 200 | 200 | 0% |
| 1000 | 662 | 75% |
| 3000 | 818 | 92% |

Which is why 200 is the default. It is the measured ceiling of usefulness rather
than a resource compromise, and higher values are still offered because hiding
the option would hide the finding.

---

## Where the run shows up

**Splunk.** Every service ships structured JSON over HEC with
`source="rembayung"`, so a run is searchable by service and outcome:

```spl
source="rembayung" message.outcome=* | stats count by message.service, message.outcome
```

The seat count can be argued from the log rather than the database, and the two
must agree.

**Prometheus.** A `ServiceMonitor` scrapes `:9090`; a `PrometheusRule` alerts if
`oversold` ever leaves zero. That is the metric worth alerting on, not CPU.

**Dynatrace.** Distributed traces and the service map, from an application-only
OneAgent on queue-gate and booking-service. It ships no logs at all, which is why
log views there are empty by design.

**The cluster itself.** `oc get jobs`, then `oc logs job/<name>` for the k6
summary. Using a Job rather than a thread pool inside the console means the run
is visible to anyone with namespace access, instead of being a private detail of
one process.

---

## Failure modes, and what each looks like

| Symptom | Where it broke | How to tell |
|---|---|---|
| `401` from the console | `KeyFilter` | no `?key=` on a write |
| `409` on start | API server | a Job of that name exists; a run is already going |
| Job created, pod `Pending` | scheduler | `compute-deploy` has no headroom; `oc describe pod` names it |
| `503` from the console | Kubernetes API unreachable | `LoadOps` catches, invalidates the client and reports |
| k6 finishes, `bookings_created` under 50 | admission | every response well-formed, nothing admitted |
| `http_req_failed` high | genuinely broken | 403 and 409 are excluded; only server errors count |

The last two are the thresholds that took a correction. A 250-seat slot satisfies
about 125 of 5000 contenders, so roughly 97% of responses are a 403 or a 409.
k6's default counts those as failures and reported about 89% failure for a system
behaving exactly as designed. Both thresholds exist because the obvious reading
is wrong in both directions: counting rejections as failures condemns a healthy
run, and counting nothing at all would pass a run in which admission was dead.

---

## One claim the platform overrides

`deploy/base/networkpolicy.yaml` declares two policies:

- `redis-from-gate-only` — ingress to `app: redis` on 6379 from `app: queue-gate`
- `booking-service-from-gate-only` — ingress to `app: booking-service` on 8081
  from `app: queue-gate`

The console nevertheless reads `/internal/slots/{id}` from booking-service on
every poll, and that call succeeds. Both cannot be true of a namespace where
those two policies are the only ones in force.

NetworkPolicies are additive: a pod selected by any policy becomes deny-by-default
for that direction, but traffic permitted by **any** matching policy is allowed.
The Developer Sandbox ships its own `allow-same-namespace` policy with an empty
pod selector, which admits traffic from anywhere inside the namespace. That is
almost certainly what the console's call is riding on, and it means the two
policies above constrain nothing that matters *in this cluster*.

They are not wrong, and they would take effect on a cluster without a blanket
same-namespace allow. But the honest statement is that pod-to-pod isolation here
is declared rather than demonstrated. To confirm:

```zsh
oc describe networkpolicy allow-same-namespace
oc get networkpolicy
```

The fix, if the isolation is meant to bind, is to name the console as a permitted
source on booking-service and stop relying on the platform's blanket rule being
absent — or to accept that in this environment the boundary is the
`InternalGuard` header check rather than the network layer.

---

## The one-sentence version

The console does not generate load. It asks the cluster for permission to, and
the cluster is allowed to say no.

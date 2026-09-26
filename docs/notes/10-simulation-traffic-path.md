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
| `/api/drops/{id}/load` | console | the internet | `console/…/ops/LoadOps.java:91` |
| `/api/drops` | console | the internet | `console/…/ops/DropOps.java:40` |
| `/api/docs` | console | the internet | `console/…/web/DocsController.java:42` |
| `/api/state` | console | the internet | `console/…/web/StateController.java:44` |
| `/api/cluster` | console | the internet | `console/…/web/ClusterController.java:32` |
| `/api/observability` | console | the internet | `console/…/web/ObservabilityController.java:35` |
| `/api/metrics/{chart}` | console | the internet | `console/…/metrics/MetricsController.java:26` |
| `/api/objects`, `/api/pods/{name}/logs` | console | the internet | `console/…/objects/ObjectsController.java:42` |
| `/api/analyses` | console | the internet | `console/…/agent/AnalysesController.java:42` |
| `/api/demo-key` | console | the internet | `console/…/web/DemoKeyController.java:31` |
| `/mcp` | console | the internet | `console/…/mcp/McpConfiguration.java:31` |
| `/queue` | queue-gate | the internet | `queue-gate/…/web/QueueController.java:12` |
| `/bookings` | queue-gate | the internet | `queue-gate/…/web/BookingProxyController.java:16` |
| `/internal/drops` | queue-gate | pods only | `queue-gate/…/web/InternalController.java:29` |

`/api/…` is the console's own surface, spoken by a browser; `/mcp` is the same
console spoken by an MCP client. `/internal/…` is queue-gate's, spoken by
another pod (booking-service has an `/internal/slots` of its own, with no Route
in front of it at all). The console calls the second; it never asks
queue-gate for load.

The console does not generate load either. It asks Kubernetes for a Job, and the
Job is what generates load. That indirection is the whole design, and the reason
is in the next section.

---

## The trace

```
browser
  │  POST /api/drops/{dropId}/load  { vus, waves }
  ▼
Route (edge TLS)  →  Service console:8082  →  console pod
  │  KeyFilter: a write, so ?key= is required
  ▼
LoadOps.start()                                   console/…/ops/LoadOps.java:187
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
  │       (+ WAVES=2, WAVE_GAP, DROP_ID_2, SLOT_ID_2 for a two-wave rush)
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
BookingService.book()                 booking-service/…/service/BookingService.java:80
  │  → claimSeat()                     BookingService.java:109
  │  SELECT … FOR UPDATE on the slot row  BookingService.java:123
  ▼
Oracle
     ck_slots_seats CHECK (seats_taken >= 0 AND seats_taken <= capacity)
```

---

## What enforces each boundary

Every hop is refused by something other than application code wherever that was
possible. This is the part worth explaining out loud: the checks are not `if`
statements this project has to keep correct.

**Browser to console.** `KeyFilter` requires the key, as `?key=` or the
`X-Console-Key` header, for writes. Reads are open so a stranger sent the link
sees a working system; starting a run is gated because it puts real load on a
real namespace. With sharing on, `GET /api/demo-key` hands the key to anyone who
asks, so the gate is now a deliberate click rather than a secret.

**Console to Kubernetes.** The console authenticates as its own ServiceAccount
(`deploy/base/console/rbac.yaml`). Its only writes are `get,list,create,delete` on
`batch/jobs` and `get,create,update` on configmaps (the k6 script and the run
agent's reports). Everything else it holds is read-only: pods and their logs,
events, the workloads, Services, Routes, HPAs and the like, for the inspector.
It cannot create a Deployment, read a Secret, or change anything but those two
kinds. A visitor pressing the
button can start a Job and can do nothing further, and that is enforced by the
API server.

**A second click.** The Job is named after the drop, so a second create is
rejected by the API server as a duplicate name. (The console now looks first:
a finished Job of that name is deleted and replaced, a running one answers 409.) There is no counter in the
console to keep correct, and no race between two browser tabs.

**The scheduler.** The k6 pod asks for CPU like every other pod. If
`compute-deploy` has no headroom the pod stays `Pending` and the event says why.
A run that will not schedule is not a defect in this feature. It is the feature:
the console exists to draw the cluster's limits rather than hide them.

**k6 to queue-gate.** The ClusterIP Service, pod to pod. No Route, no TLS
termination, no router in the path.

**Admission.** `X-Admission-Token` is consumed with Redis `GETDEL`, which is
atomic, so there is no check-then-act window in which two callers redeem the same
token. `BookingProxyController` also takes the slot from the *token*, not the
request body: a valid token for a visitor's own sandbox, sent with the canonical
250-seat slot in its body, passes every upstream check, so the gate overwrites
`slotId` with the one the token was admitted for rather than trusting the
caller.

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

Which is why 200 is the ceiling of usefulness rather than a resource compromise,
and higher values are still offered because hiding the option would hide the
finding. The default is lower still, 60 VUs: at the one admission a second this
database commits, 60 customers drain inside k6's ninety-second polling window,
and 200 do not ([note 09](09-demo-console.md)).

---

## Where the run shows up

**Splunk** (until its trial ended on 2026-09-25). Every service shipped
structured JSON over HEC with `source="rembayung"`, so a run was searchable by
service and outcome:

```spl
source="rembayung" message.outcome=* | stats count by message.service, message.outcome
```

The seat count could be argued from the log rather than the database, and the
two had to agree.

**Prometheus.** A `ServiceMonitor` scrapes `:9090`; a `PrometheusRule` alerts if
`oversold` ever leaves zero. That is the metric worth alerting on, not CPU. The
simulation page draws its charts from the same series, through the Thanos
tenancy port.

**Dynatrace** (until its trial ended on 2026-09-24). Distributed traces and the
service map, from an application-only OneAgent on queue-gate and
booking-service. It shipped no logs at all, which is why log views there were
empty by design. The agent is now switched off
([note 08](08-observability.md)).

**The cluster itself.** `oc get jobs`, then `oc logs job/<name>` for the k6
summary. Its last line is `K6_SUMMARY` followed by one JSON object: bookings,
latencies, and the customer funnel's counters (joined, admitted, booked, sold
out, gave up, overloaded, faults), per wave when there were two. The console's
run agent reads that line from every finished run and writes its report into
the `run-analyses` ConfigMap ([note 12](12-run-agent.md)). Using a Job rather than a thread pool inside the console means the run
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
| `http_req_failed` high | genuinely broken | everything below 500 is excluded, and so is 503, the deliberate shed-load answer; only other server errors count |

The last two are the thresholds that took a correction. A 250-seat slot satisfies
about 125 of 5000 contenders, so roughly 97% of responses are a 403 or a 409.
k6's default counts those as failures and reported about 89% failure for a system
behaving exactly as designed. The same reasoning later took 503 out: a saturated
pool answers 503 with `Retry-After` on purpose, and counting it tripped the
threshold on the very run built to show back-pressure. Both thresholds exist because the obvious reading
is wrong in both directions: counting rejections as failures condemns a healthy
run, and counting nothing at all would pass a run in which admission was dead.

---

## One claim the platform overrides

`deploy/base/networkpolicy.yaml` declares two policies:

- `redis-from-gate-only` — ingress to `app: redis` on 6379 from `app: queue-gate`
- `booking-service-from-gate-only` — ingress to `app: booking-service` on 8081
  from `app: queue-gate` (and, since the observability work, on 9090 from the
  console and the user-workload monitoring namespace, for metrics only)

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
source on booking-service's 8081 as well, as it already is on 9090, and stop
relying on the platform's blanket rule — or to accept that in this environment
the boundary is somewhere else: `InternalGuard`'s shared-secret header on
queue-gate's `/internal`, and on booking-service, having no Route at all.

---

## The one-sentence version

The console does not generate load. It asks the cluster for permission to, and
the cluster is allowed to say no.

# Observability — Design (Phase 6)

**Date:** 2026-09-04
**Status:** Draft, awaiting review
**Phase:** 6 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Previous phase:** [`2026-09-04-continuous-delivery-design.md`](2026-09-04-continuous-delivery-design.md)
**Feeds:** Phase 8, the public viewer UI — see §8, which is a binding constraint on this phase, not a note about a later one.

---

## 1. Purpose

The system has no observability at all. That is not a theoretical gap: on
2026-09-04 Redis was found scaled to zero replicas, `queue-gate` had been out of
its Service for roughly **nine hours**, and the public Route had been returning
503 that entire time. Nobody knew. It was discovered by a subagent that happened
to try deploying into it.

A demo that a stranger may open unannounced cannot be in that state. This phase
makes the system observable and makes it *tell you* when it is not working.

It also has a second job. Three tools appear here rather than one because two of
them — Dynatrace and Splunk — are named in the job description this project
exists to support. Installing all three to do the same job would be worse than
installing none, so each is given the job it is actually best at.

---

## 2. Why these three, and what the constraints forced

Every choice below was measured against this cluster, not chosen from
familiarity.

### What the sandbox forbids

```
SCCs available to workloads:  restricted-v2 only
  privileged  no    hostaccess  no    hostmount-anyuid  no    anyuid  no
get nodes:                    no
create daemonsets:            yes — but useless without host access
```

**This rules out infrastructure monitoring entirely.** Dynatrace OneAgent's
full-stack mode and Splunk's universal forwarder both need `privileged` or
`hostaccess` to read host processes, `/proc` and the container runtime. A
DaemonSet would schedule and see nothing.

Application-level instrumentation needs none of that, and is what this phase
uses.

### What the quota forbids

`requests.cpu` is capped at 3000m and the autoscaling demonstration needs about
1900m. Self-hosted Splunk Enterprise wants roughly 2 CPU and 4GB before it will
start. It does not fit, and this is the same constraint that already removed
Ansible Automation Platform (1950m) and Jenkins from this project.

### What the calendar allows

The interview is on or about 2026-09-11, seven days out. Dynatrace's trial is 15
days and Splunk's is 14, so both cover the window. **Neither is permanent**, and
that is the whole reason Prometheus is the backbone rather than a fourth wheel:
when the trials lapse, the alerting that keeps the demo alive must not lapse
with them.

### Egress, verified

```
www.dynatrace.com        DNS ok   TCP443 REACHABLE
www.splunk.com           DNS ok   TCP443 REACHABLE
ingest.eu0.signalfx.com  DNS ok   TCP443 REACHABLE
```

Every NetworkPolicy in the namespace is `Ingress`-only, so nothing restricts
outbound traffic. An earlier probe reported two of these as unreachable; that was
a bad hostname on the tester's part — Dynatrace and Splunk Cloud both use
per-tenant prefixes — and not an egress block. Recorded because the wrong
conclusion would have killed this phase before it started.

### Feasibility of the cluster's own stack, verified

```
oc apply --dry-run=server  ServiceMonitor   → created (server dry run)
oc apply --dry-run=server  PrometheusRule   → created (server dry run)
```

Both accepted, so the platform's Prometheus can scrape and alert on our
workloads at **zero quota cost**. Nothing new runs in the cluster.

### The division of labour

| Layer | Job | Lifespan | Why it, and not the others |
|---|---|---|---|
| Prometheus + ServiceMonitor | Metrics and alerting | Permanent | Free, zero quota, already present, outlives both trials |
| Dynatrace | APM: traces, service topology, hotspots | 15-day trial | Shows the Oracle call chain and the row-lock hotspot as a map, which metrics cannot |
| Splunk | Log aggregation and SPL search | 14-day trial | The 21:00 burst is a log-analysis problem; metrics aggregate away the individual request |

Three tools, three jobs, no overlap. If asked why not one, the answer is that a
p99 latency graph, a distributed trace and a full-text search over 3,000 booking
attempts answer different questions.

---

## 3. What we measure

JVM, HTTP and HikariCP metrics arrive free with Micrometer and are worth having.
They are not the point. These are:

```
rembayung_slot_oversold{slot}            gauge   ← the headline; must always be 0
rembayung_slot_seats_taken{slot}         gauge
rembayung_slot_capacity{slot}            gauge
rembayung_slot_remaining{slot}           gauge
rembayung_queue_tickets_issued           gauge   ← the Redis counter
rembayung_queue_admitted                 gauge   ← admittedBy(now), the pure function
rembayung_queue_waiting                  gauge   ← issued − admitted
rembayung_bookings_total{outcome}        counter ← created | sold_out | not_admitted | expired
rembayung_booking_lock_wait_seconds      timer   ← time spent waiting on SELECT ... FOR UPDATE
```

### Why `rembayung_slot_oversold` is the important one

It is computed as `max(0, seats_taken − capacity)` and must read zero forever. A
graph that stays flat at zero while 200 virtual customers contend for one
database row **is the entire project in a single line**.

It is deliberately *redundant* with the database's `ck_slots_seats` CHECK
constraint, which makes the condition impossible to persist. That redundancy is
the value: if the gauge ever moves, either the invariant broke or the metric is
lying, and both are worth being paged about at 21:00.

### Why `rembayung_booking_lock_wait_seconds` earns its place

Phase 3 measured ten concurrent bookings completing 1.00s apart — perfectly
serialised by the row lock, not by the connection pool. That finding currently
lives in a note nobody will read during a demo. As a histogram it is a shape on
a screen, and it explains the admission rate of 1/second better than any
sentence.

---

## 4. Architecture: one computation, several projections

This is the section that constrains Phase 8, and the reason it exists is
concrete.

The metrics endpoint lives on the **private management port 9090**, which is not
on the public Route (verified: `/actuator/health` through the Route returns 404,
and that boundary is deliberate). The Phase 8 UI therefore cannot read metrics
directly, and a browser cannot query the cluster's Prometheus.

The naive consequence is that Phase 8 writes its own "seats taken" query. Then
the number on the public dashboard and the number in the alerting rule are
computed by two different pieces of code — and the number in question is the one
the project's entire claim rests on. They would eventually disagree, and the
disagreement would surface in front of an audience.

So:

```
        ┌──────────────────────┐
        │  SlotStateProvider   │   one component, one computation
        │  seats / capacity /  │   reads Oracle and Redis
        │  oversold / queue    │
        └──────────┬───────────┘
                   │
        ┌──────────┴───────────┐
        │                      │
   Micrometer gauges      (Phase 8) public
   port 9090, private     JSON endpoint, port 8080
        │                      │
   Prometheus              the viewer UI
   alerts, dashboards
```

`SlotStateProvider` is introduced in **this** phase and returns an immutable
`SlotState` record. Phase 6 wires the gauges to it. Phase 8 adds a controller
that serialises the same record. Neither can drift from the other, because there
is nothing to drift from.

**Binding rules this places on Phase 8:**

- Phase 8 must not query the database or Redis directly for state that
  `SlotStateProvider` already computes.
- Field names in `SlotState` match metric names minus the `rembayung_` prefix, so
  a reader moving between the dashboard and the API sees the same words.
- The UI builds its own client-side history by polling. It does **not** get a
  time-series backend, and Prometheus is not exposed to make one. A viewer that
  wants five minutes of history polls for five minutes; that is enough for a
  drop that lasts one second and a demo that lasts ten minutes.

### Cost of this choice

`SlotStateProvider` reads the database on a schedule to keep gauges fresh, which
is load that did not exist before. The read is a single indexed primary-key
select against a slot row, on a 250ms-RTT link, at a fixed interval — negligible
next to a booking, which takes three round trips. It is cached for the gauge
refresh interval so that scrape frequency cannot amplify it.

---

## 5. Dynatrace: application-only injection

Full-stack is impossible here (§2). Application-only is not, and needs no
privileged access:

1. An `initContainer` downloads the Java agent using a **PaaS token**, into a
   shared `emptyDir`.
2. The application container mounts that volume and sets
   `JAVA_TOOL_OPTIONS=-agentpath:/dynatrace/agent/lib64/liboneagent.so`.
3. `DT_TENANT`, `DT_TENANTTOKEN` and `DT_CONNECTION_POINT` come from a Secret.

No operator install, no DaemonSet, no cluster-scoped permissions — all of which
the sandbox would refuse anyway.

**What it is for:** the service flow map showing `queue-gate → booking-service →
Oracle`, and the method-level hotspot that makes the 250ms round trip and the
lock wait visible without reading a note about them.

**What it is not for:** infrastructure or host metrics, which it cannot see here.
Say that plainly rather than letting a viewer assume otherwise.

---

## 6. Splunk: logs over HEC, with no agent

Splunk's HTTP Event Collector accepts JSON over HTTPS. That means **no forwarder,
no sidecar, no DaemonSet, and nothing to schedule** — the application posts its
own logs.

Prerequisite, and worth having regardless: **structured logging.**
`logstash-logback-encoder` turns every line into JSON carrying `slot_id`,
`booking_id`, `ticket`, `outcome`, `party_size` and the trace id. Unstructured
text in Splunk is a text search; structured JSON is a queryable dataset, and the
difference is the whole point of using it.

The appender posts to `http-inputs-<tenant>.splunkcloud.com` with a HEC token
from a Secret.

**What it is for:** the 21:00 burst is a log problem. "Show me every booking
attempt in the second the drop opened, grouped by outcome" is SPL, and metrics
cannot answer it because aggregation has already thrown away the individual
request.

**Failure mode that matters:** if Splunk is unreachable the appender must not
block the request thread or fill the heap. It is configured asynchronous with a
bounded queue and a drop-on-full policy. **Losing logs is acceptable; losing
bookings is not.** A logging integration that can take down the booking path
would be a worse bug than having no logging.

---

## 7. Alerting

`PrometheusRule`, written from failures that actually happened rather than
imagined ones:

| Alert | Condition | Why |
|---|---|---|
| `RedisDown` | redis ready replicas `== 0` for 2m | This exact failure went unnoticed for nine hours |
| `ServiceHasNoEndpoints` | endpoints `== 0` for 2m | The symptom a user sees: the Route 503s |
| `SlotOversold` | `rembayung_slot_oversold > 0` | Should be impossible. If it fires, the project's central claim is false |
| `BookingLatencyDegraded` | p99 `> 10s` for 5m | The pool-exhaustion signature from Phase 3 |
| `DeployStuck` | a Deployment has unavailable replicas for 10m | Catches a CD run killed mid-rollback |

`SlotOversold` is the only one that should never fire in the system's lifetime.
That is what makes it worth having.

---

## 8. What Phase 8 inherits

Stated here so the UI phase is designed against it rather than around it:

- `SlotStateProvider` and the `SlotState` record — the single source for seats,
  capacity, oversold, tickets issued, admitted and waiting.
- Structured JSON logs already carrying the identifiers a UI would want to link
  on.
- Dynatrace and Splunk dashboard URLs, recorded in `deploy/README.md`, so the UI
  can link out rather than reimplement.
- The rule that the management port stays private. The UI gets a purpose-built
  public endpoint in Phase 8; it never gets `/actuator`.

---

## 9. Secrets

Three new credentials: a Dynatrace PaaS token, a Dynatrace tenant token, and a
Splunk HEC token. All three are created by a human and stored as OpenShift
Secrets, exactly as the Oracle wallet is.

The CD ServiceAccount **cannot read Secrets** — verified `oc auth can-i get
secrets` returns `no` — so this phase adds nothing that CD could exfiltrate.

Neither token is ever committed, echoed, logged, or written into a ConfigMap.

---

## 10. Testing

| Layer | How |
|---|---|
| Metric correctness | Unit tests on `SlotStateProvider` — a slot at capacity reports `remaining=0` and `oversold=0`; a hypothetically oversold slot reports the overage |
| Endpoint exposure | Integration test asserting `/actuator/prometheus` serves on the management port and carries the `rembayung_` series |
| The private boundary | Assert `/actuator/prometheus` through the **public Route** returns 404, exactly as the health check already does |
| Alert rules | `promtool check rules` on the PrometheusRule before applying |
| Splunk resilience | Point the appender at an unroutable host and assert booking latency does not change and the heap does not grow |

Note the containers ship no `curl` — they are JRE images. Verification from
inside a pod uses an ephemeral debug pod, not `oc exec ... curl`.

---

## 11. Out of scope

- **Log aggregation beyond Splunk.** No Loki, no EFK — both want quota.
- **Grafana Cloud.** Redundant with the cluster's own Prometheus.
- **Custom Grafana dashboards.** The OpenShift console's Developer → Observe view
  is free and sufficient.
- **Tracing between services via OpenTelemetry.** Dynatrace's agent already
  provides it for the trial window; adding OTel as well would be a fourth tool
  doing a third tool's job.
- **Alert routing to email or Slack.** The alerts fire and are visible in the
  console. Delivery is a notification-channel exercise that proves nothing extra.

---

## 12. Known weaknesses

- **Two of the three layers expire.** Dynatrace on ~2026-09-19 and Splunk on
  ~2026-09-18. After that the screenshots remain and the integrations stop. This
  is why Prometheus carries the alerting.
- **No infrastructure monitoring.** `restricted-v2` forbids it. The demo shows
  application observability only, and claiming otherwise would be false.
- **Alerts fire into the console, and nobody is paged.** A real on-call setup
  needs Alertmanager routing. Given the demo has one operator who is usually
  looking at it, this is a stated limit rather than an oversight.
- **`SlotStateProvider` adds a periodic database read** that did not exist
  before. Small, cached, and on the same link every booking already crosses.
- **The oversold gauge cannot detect a slot it does not know about.** It reports
  on slots it is asked to track; a slot created outside the demo path is
  invisible to it.

---

## 13. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| Backbone | Cluster Prometheus via ServiceMonitor | Free, zero quota, permanent; ServiceMonitor and PrometheusRule creation both verified against this cluster |
| Dynatrace mode | Application-only, initContainer + `JAVA_TOOL_OPTIONS` | `restricted-v2` is the only SCC available; full-stack needs privileged and cannot work here |
| Splunk transport | HEC over HTTPS from a logback appender | Needs no agent, no sidecar and no quota; egress to Splunk verified reachable |
| Splunk's job | Logs, not metrics | Dynatrace already does traces and Prometheus does metrics; three tools doing one job would be worse than one |
| Splunk failure policy | Async, bounded queue, drop on full | Losing logs is acceptable; a logging integration that can stall the booking path is not |
| Structured logging | `logstash-logback-encoder` | Unstructured text makes Splunk a text search rather than a dataset |
| State computation | One `SlotStateProvider`, many projections | Stops the public UI and the alerting rule computing the project's central number two different ways |
| History for the UI | Client-side polling, no time-series backend | The drop lasts one second; a viewer does not need a TSDB, and Prometheus stays private |
| Alert selection | Written from failures that actually occurred | `RedisDown` comes from a real nine-hour outage, not a checklist |
| Alert routing | Console only | Alertmanager routing proves nothing this phase does not already show |

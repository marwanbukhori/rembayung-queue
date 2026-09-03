# Rembayung Booking Queue — Design

**Date:** 2026-09-02
**Status:** Approved, ready for implementation planning
**Purpose:** Portfolio / interview demonstration piece for a DHL role

---

## 1. Problem and context

Rembayung is a restaurant in Kampung Baru, Kuala Lumpur, opened early 2026 by
Khairul Aming. It seats ~250 diners at a time and draws demand from a social
media following in the millions. Two failures are publicly documented:

- **The booking platform crashed five minutes after going live.** ~3,000 booking
  attempts; 2,641 completed. The owner apologised publicly: *"We have tried our
  best, but was still unable to cater to high traffic."*
- **Scalpers captured inventory and resold it**, in at least one case selling a
  single slot to two different buyers, leaving the restaurant to handle the
  conflict at the door.

The owner has publicly rejected requiring identity cards at booking, and the
common objection holds: a scalper can collect IC numbers from buyers in advance
and enter them later, so identity-at-booking does not prevent resale.

These are **two separate problems** and conflating them is why most proposed
solutions fail:

| Problem | Nature | Solved by |
|---|---|---|
| Thundering herd | Scale / availability | Queueing and load shedding |
| Scalping | Identity / incentives | Making a slot costly to transfer |

This project builds a system that addresses both, as a vehicle for
demonstrating an enterprise Java + OpenShift + observability + CI/CD stack.

### Non-goal

This is **not** a production system for Rembayung and makes no claim to be. It
is a technical demonstration built around a real, well-documented problem
because a real problem produces better engineering conversation than a
synthetic one.

---

## 2. Goals

1. Demonstrate breadth across the target stack: **Spring Boot, Oracle,
   OpenShift, Dynatrace, Splunk, Ansible, GitHub Actions**.
2. Survive a realistic demand spike without overselling capacity.
3. Be **explainable end to end** by its author under questioning. An
   unexplainable demo is worse than no demo.

### Explicit non-goals

- Real payment processing (deposit is a mocked status transition).
- Per-user fairness in admission (rate-based admission; a faster-retrying
  client is admitted sooner). Acknowledged, not hidden.
- Production-grade security hardening, HA, or multi-region.

---

## 3. Verified constraints

These were checked against the live environment, not assumed.

| Constraint | Finding | Consequence |
|---|---|---|
| Sandbox CPU quota | `requests.cpu: 3` | **Binding constraint.** Caps HPA ceilings. |
| Sandbox memory quota | `requests.memory: 30Gi` | Not a limitation. |
| Sandbox storage | 80Gi, 10 PVCs (2 used) | Not a limitation. |
| Cluster-admin | **Not available** | No privileged DaemonSets. Rules out Dynatrace OneAgent and Splunk cluster log collection. |
| Oracle on OpenShift | Needs `anyuid` SCC + initContainer to chown its volume | **Cannot run Oracle in the Sandbox.** Use managed Oracle instead. |
| Dev machine | Apple M1 Pro, **arm64**, 16GB | Locally built images are arm64 and will not run on the x86_64 Sandbox. |
| Oracle on Apple Silicon | Native ARM64 images of 23ai Free exist (Full and Lite) | Local development against real Oracle is viable. |
| Sandbox validity | 30 days from provisioning (29 remaining as of 2026-09-02) | Renewable; not a demo-day constraint. |

---

## 4. Architecture

Two application deployments, separated so they can scale independently. The
gate is stateless and cheap; the booking service is database-bound and
deliberately narrow.

```
                    Internet
                       |
              [ Route ]
                       |
            +----------v-----------+
            |   queue-gate         |   Deployment + Service + Route
            |   (Spring Boot)      |   HPA 2 -> 10, stateless, no DB access
            +----------+-----------+
                  |         |
      issues token |         | checks position
                  v         v
            +------------------------+
            |   Redis                |   Deployment + Service (internal only)
            |   queue + admissions   |   no PVC — state is disposable
            +------------+-----------+
                         ^
          admits ~200/s  |
            +------------+-----------+
            |   booking-service      |   Deployment + Service (internal only)
            |   (Spring Boot)        |   HPA 2 -> 4, holds DB connections
            +------------+-----------+
                         |  JDBC + wallet
                         v
            +------------------------+
            |  Oracle Autonomous DB  |   External, OCI Always Free
            |  (OCI, Always Free)    |   2 instances / 20GB available
            +------------------------+
```

### OpenShift object mapping

| Component | Objects | Rationale |
|---|---|---|
| `queue-gate` | Deployment, Service, **Route** | Only public entry point |
| `booking-service` | Deployment, Service | **No Route** — unreachable from the internet |
| `redis` | Deployment, Service | Internal only |
| Oracle wallet | **Secret**, mounted as a volume | Credentials never baked into an image |
| Slot times, admit rate | **ConfigMap** | Tunable live during a demo without a rebuild |

### Deliberate architectural decisions

**The booking service has no Route.** The queue cannot be bypassed, because the
booking endpoint is not exposed to the internet at all. This is a security
boundary, not an omission.

**Redis has no persistent volume.** Queue state is intentionally disposable. If
Redis dies mid-drop, queue positions are lost but **no confirmed booking is
lost**, because confirmed bookings live only in Oracle. Clean, explainable
durability boundary.

**Oracle is external.** Forced by the SCC constraint, but it also reflects how
enterprises actually run: applications on the cluster, database managed
separately.

### HPA ceilings, derived from the real 3-core budget

| Workload | CPU request | Max replicas | Worst case |
|---|---|---|---|
| `queue-gate` | 100m | 10 | 1.0 core |
| `booking-service` | 200m | 4 | 0.8 core |
| `redis` | 100m | 1 | 0.1 core |
| **Total** | | | **1.9 / 3.0 cores** |

The gate scales; the booking service does not. This is the point: the database
is the scarce resource, so load is absorbed in front of it rather than passed
through to it.

---

## 5. Data model

Oracle holds all durable state. Redis holds only ephemeral queue state.

```sql
slots     ( id, service_date, service_time, capacity, seats_taken, version )
bookings  ( id, slot_id, phone, party_size, status,
            deposit_cents, idempotency_key, created_at, expires_at )
```

Booking status transitions:

```
PENDING_DEPOSIT ──pay──> CONFIRMED
                └─timeout─> EXPIRED     (seats released)
                └─user────> CANCELLED   (seats released)
```

`idempotency_key` carries a **unique constraint**.

---

## 6. Booking flow

```
1. POST /queue                  -> Redis: issue token, return position
2. GET  /queue/{token}          -> poll position (or SSE)
3. gate admits ~200/sec         -> Redis: mark ADMITTED, TTL 5 min
4. POST /bookings               -> booking-service:
                                     a. validate admission token (single use)
                                     b. BEGIN
                                     c. SELECT ... FOR UPDATE on slots row
                                     d. seats_taken + party_size <= capacity ?
                                     e. INSERT booking PENDING_DEPOSIT
                                     f. COMMIT
5. POST /bookings/{id}/deposit  -> CONFIRMED  (mocked payment)
6. scheduled sweeper            -> expire stale PENDING, release seats
```

### Correctness rules

These two rules are where double-booking bugs are actually introduced.

**R1 — The sweeper must take the same lock.** The expiry sweeper (step 6) and a
live booking (step 4) both mutate `slots.seats_taken`. The sweeper **must**
acquire the same `SELECT ... FOR UPDATE` lock on the slot row, so releasing and
claiming can never interleave. This is the single most important invariant in
the system.

**R2 — Idempotency is enforced at the database.** A double-tapped submit button
or a gate retry must not produce two bookings. The unique constraint on
`idempotency_key` is the enforcement point, not application logic.

### Invariant to be demonstrated

> `slots.seats_taken` never exceeds `slots.capacity`, under any load, including
> concurrent expiry sweeps and pod failures.

---

## 7. CI/CD

Split deliberately, mirroring common enterprise practice: CI in the cloud, CD
from a server with network reach into the cluster.

```
GitHub Actions = CI               Ansible = CD
──────────────────────            ──────────────────────
git push                          triggered on CI success
  ├── mvn verify (unit + IT)        ├── kubernetes.core.k8s: apply overlay
  ├── build image (x86_64)          ├── set the image tag
  ├── push to ghcr.io               ├── wait for rollout
  └── trigger the deploy workflow   ├── smoke test against the Route
                                    └── roll back to the previous tag on failure
```

**Revised 2026-09-03: Ansible replaces Jenkins for CD.** The original split put
Jenkins on a server with network reach into the cluster. That rationale
disappears once the deploy runs from a GitHub Actions runner authenticated with
an `oc` token — and the sandbox cannot host Jenkins anyway. See the decision log.

**Architecture gotcha:** the dev machine is arm64, the Sandbox is x86_64.
Images built locally with Docker will fail on the cluster with an exec-format
error (`CrashLoopBackOff`). GitHub Actions runners are x86_64, so **building in
CI solves this for free**. Local builds must pass `--platform linux/amd64`.

**Registry:** Quay.io (free public repositories, Red Hat ecosystem).
`ghcr.io` is an acceptable alternative.

**The deploy playbook takes the cluster URL and credentials from environment
bindings, never hardcoded.** That keeps it runnable from a GitHub Actions
runner, from a laptop, or as an Ansible Automation Platform Job Template
without modification — which is the point: the same playbook is the unit of
work in all three.

---

## 8. Observability

**All instrumentation is at the application layer, not the cluster layer.** This
is forced by the absence of cluster-admin, but it is also the better design:
vendor-neutral instrumentation means the backend is a configuration change.

```
  Spring Boot (queue-gate + booking-service)
     │
     ├── OpenTelemetry SDK ──OTLP──> Dynatrace   (traces, metrics)
     │      auto-instruments HTTP, JDBC, Redis
     │
     └── Logback + HEC appender ───> Splunk      (structured JSON logs)
            every line carries trace_id
```

**Trace correlation is the centrepiece.** OpenTelemetry populates `trace_id`
into the logging MDC, so every Splunk log line carries the trace ID Dynatrace
knows about. A failed booking can be found in Splunk, its `trace_id` pasted
into Dynatrace, and the exact failing span — including the JDBC call —
inspected. Demonstrating that pivot is worth more than any dashboard, because
it is what people actually do during an incident.

| Tool | Responsibility |
|---|---|
| **Splunk** | Queue depth, admission rate, rejections, business events |
| **Dynatrace** | Latency percentiles, service flow map, DB spans |
| **OpenShift** | HPA scaling behaviour, pod health |

### Licensing sequencing

Dynatrace is a ~15-day trial; do not burn it during development. Build against
a **free local OTLP backend** (Jaeger, or the OTel collector debug exporter),
and switch the endpoint to Dynatrace only when ready to rehearse and **record
the demo on video**. Splunk Enterprise Free (500MB/day) runs in Docker and is
sufficient throughout.

The recorded video is insurance against venue wifi and against trial expiry.

---

## 9. Demo script

Approximately eight minutes, ending on correctness rather than on a graph.

| # | Step | Shows | Time |
|---|---|---|---|
| 1 | Book a seat normally | Baseline: what "working" looks like | 30s |
| 2 | Push a commit | GH Actions builds → ghcr → Ansible deploys → rollout | 2m |
| 3 | k6 fires 50,000 virtual users | Splunk queue climbing, OpenShift pods 2→10, Dynatrace latency **flat** | 3m |
| 4 | `SELECT seats_taken FROM slots` | Exactly 250, never 251. Zero double-bookings. | 1m |
| 5 | `oc delete pod` mid-load | In-flight requests fail; **no confirmed booking lost or duplicated** | 1m |

Step 4 is the one that matters. Anyone can make a graph go up; demonstrating
that the invariant held under 50,000 concurrent attempts is what distinguishes
this from a toy.

---

## 10. Build phases

Each phase should be independently demonstrable.

1. **Domain core.** Spring Boot + Oracle locally (ARM64 container). Slots,
   bookings, locking, idempotency, expiry sweeper. Tests prove the invariant
   under concurrency. *No cluster involved.*
2. **Queue gate.** Redis, admission tokens, rate-based admission. Load test
   locally with k6.
3. **OpenShift deployment.** Manifests/Kustomize, Secrets, ConfigMaps, Routes,
   HPA. Oracle switched to OCI Autonomous.
4. **CI.** GitHub Actions: test, build x86_64 image, push to Quay.
5. **CD.** Ansible playbook: deploy, smoke test, rollback.
6. **Observability.** OpenTelemetry → local backend first, then Dynatrace.
   Logback → Splunk. Trace correlation verified.
7. **Rehearse and record.** Run the full demo script end to end; record video.

---

## 11. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| Demo emphasis | Breadth over depth | Matches how a JD-driven panel screens |
| Anti-scalper mechanism | Deposit only | Cheapest to build; keeps FCFS, which preserves the traffic spike the demo needs |
| Spike architecture | Virtual waiting room + DB locking | Load shedding in front, integrity behind; the answer senior engineers expect |
| Oracle placement | OCI Autonomous (Always Free) | Sandbox cannot grant `anyuid`; also enterprise-realistic |
| Local Oracle | 23ai Free ARM64 container | Native on Apple Silicon, no emulation |
| Services | Two deployments, not one | Independent scaling is the entire point of the gate |
| Redis durability | No PVC | Queue state disposable; bookings durable in Oracle |
| CI/CD split | GH Actions = CI, Ansible = CD | Separates "is it good?" from "is it live?" while keeping one runner |
| CD tool | **Ansible, replacing Jenkins** (revised 2026-09-03) | Red Hat's own tooling, natural against OpenShift via `kubernetes.core.k8s`; needs no server, so it costs none of the 3-core quota the autoscaling demo requires |
| AAP | Named as the production home, not run here | The sandbox arrived with AAP consuming 1950m of 3000m — it and the 2→10 scale demo cannot coexist. The playbook is the artefact; AAP is where it would run |
| Instrumentation | OpenTelemetry, vendor-neutral | No cluster-admin for OneAgent; backend becomes config |

---

## 12. Known weaknesses

Stated here so they are acknowledged rather than discovered by an interviewer.

- **Payment is mocked.** No gateway integration; status transition only.
- **Admission is rate-based, not per-user-fair.** A client that retries faster
  is admitted sooner.
- **Deposit alone is a weak anti-scalper control.** A determined scalper can
  price the deposit into resale. Stronger options considered and rejected for
  scope: ballot with deposit, and phone-OTP re-verified at the door.
- **Single region, no HA.** Out of scope.

---

## 13. References

- [Rembayung booking platform crashes minutes after launch — Sinar Daily](https://www.sinardaily.my/article/732794/culture/viral/rembayung-booking-platform-crashes-minutes-after-launch-amid-overwhelming-demand)
- [Rembayung hits 3,000 bookings, website crashes — New Straits Times](https://www.nst.com.my/lifestyle/groove/2026/01/1350658/showbiz-khairul-amings-rembayung-restaurant-hits-3000-bookings)
- [Khairul Aming responds to IC-check suggestion — World of Buzz](https://worldofbuzz.com/khairul-aming-responds-to-msian-who-suggests-customers-show-ic-at-rembayung-to-avoid-scalpers/)
- [Oracle Database 23ai Free container images for ARM-based Macs — Oracle](https://blogs.oracle.com/database/announcing-oracle-database-23ai-free-container-images-for-armbased-apple-macbook-computers)
- [Running Oracle Database 23c/23ai Free on OpenShift (SCC workarounds)](https://github.com/m-g-k/Running-Oracle-Database-23c-and-23ai-Free-on-OpenShift)
- [Always Free Autonomous Database limits — Oracle Docs](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
- [Dynatrace Operator security requirements — Dynatrace Docs](https://docs.dynatrace.com/docs/ingest-from/setup-on-k8s/reference/security)
- [Splunk HEC Logback appender](https://github.com/kdrakon/splunk-logback-hec-appender)

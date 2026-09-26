# Chaos Drills and an AI Incident Commander — Design

**Date:** 2026-09-26
**Status:** Draft, awaiting review
**Audience it serves:** a DevOps / SRE interviewer who values AI used with guardrails.
**Builds on:**
- the run agent and its validator (note 12);
- the MCP server (note 13);
- the two-wave rush and the funnel;
- the CD pattern of RBAC applied by hand.

---

## 1. Purpose

Today the demo shows a system under load and an agent that explains a finished rush. This design turns it into a live operations loop:

> break something → the SLOs notice → an incident opens → the AI investigates through MCP and proposes a fix → a **human** approves → the fix is applied → the SLOs are verified to recover → the AI writes the postmortem.

**Success.** On the live site, during a rush, a visitor presses "Squeeze the pool" and, within about three minutes, sees:
- an incident open on its own;
- the agent's diagnosis citing facts;
- a proposed fix they approve with one click;
- both SLOs back to green;
- a postmortem stating time to detect and time to recover.

### Out of scope

- Faults beyond the three below.
- The agent applying anything itself.
- Alertmanager routing. The console evaluates the SLOs itself, and the `PrometheusRule` mirrors them.
- Paging or notifications.

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Who may inject | Key holders, including the shared demo key | The drill is the demo; the guards are one fault at a time and self-reverting |
| Concurrency | One fault at a time. The lock and its expiry live in ConfigMap `chaos-state` | Survives a console restart; never two at once |
| Reverting | Each fault ends by itself within 2 minutes, in the app or by Kubernetes, independent of the console | A crashed console must not leave the system broken |
| Agent powers | Investigate and propose only | AI diagnoses, a human decides |
| Applying fixes | `approve_remediation`, only from a keyed browser request in the console; never offered to the agent's MCP session | The guardrail is structural, not a prompt instruction |
| Permissions | Narrow, named grants, applied by hand | CD cannot grant itself rights (note 07) |
| Storage | ConfigMap `incidents`, newest 12, the same pattern as `run-analyses` | Same RBAC, same lessons (clean writes, resourceVersion) |

---

## 3. Faults

| Id | Name on the page | Mechanism | Ends | Expected effect |
|---|---|---|---|---|
| `kill-booking-pod` | Kill a booking-service pod | The console deletes the newest ready booking-service pod | The ReplicaSet replaces it (about 60 s to ready) | Half the connections; 503s; the survivor's pool saturates |
| `slow-database` | Slow the database | booking-service adds 400 ms inside each booking transaction, holding its connection | booking-service reverts at `until` (inject time + 120 s) by itself | Commit rate falls; queue backs up; p95 rises |
| `squeeze-pool` | Squeeze the pool | booking-service sets Hikari `maximumPoolSize` from 5 to 1 at runtime | booking-service restores 5 at `until` by itself | Fast saturation, 503s |

**booking-service** gains `POST /internal/chaos {fault, seconds}` and `DELETE /internal/chaos`, plus `GET /internal/chaos` for the current state.
- It is reachable only inside the namespace. The existing NetworkPolicy lets the console in and nothing public.
- `seconds` is capped at 120.
- A scheduled check in booking-service restores the defaults once `until` passes, so the revert does not depend on the console.

**The console:**
- `POST /api/chaos {fault}` (keyed):
  - takes the `chaos-state` lock;
  - refuses with 409 if a fault is active, and says which one and until when;
  - applies the fault, then opens a drill incident.
- `GET /api/chaos` returns the active fault or none.
- `DELETE /api/chaos` (keyed) ends a fault early.

---

## 4. SLOs and detection

| SLO | Query | Target |
|---|---|---|
| Booking success | 1 − (booking-service 5xx on `/bookings`) ÷ (all `/bookings`), over 5 m | ≥ 99% |
| Booking latency | p95 of booking-service `/bookings` from its histogram, over 5 m | < 2 s |

- The console evaluates both every 15 s through the existing Thanos tenancy reader.
- **Opening an incident:** a breach sustained for 30 s opens one, unless one is already open. A fault injection opens a drill incident immediately.
- **Resolving it:** both SLOs back within target, and held for 60 s.
- **Mirroring:** a `PrometheusRule` carries the same two conditions as alerts (`BookingSuccessSLO`, `BookingLatencySLO`). CD already applies `PrometheusRule`.
- **No traffic:** with no bookings, the success SLO is "no data" rather than a breach. An idle system never opens incidents.

---

## 5. The incident

Stored as JSON in ConfigMap `incidents`: `{ id, kind: drill|breach, fault?, openedAt, detectedAt, resolvedAt?, status: open|mitigating|resolved|unresolved, timeline[], facts[], diagnoses[], proposals[], postmortem? }`.

### 5.1 The timeline

Events are added by the console as they happen, each `{ at, source: chaos|slo|kubernetes|agent|human, text }`:
- a fault injected or ended;
- an SLO reading crossing its target in either direction;
- pods deleted, created or ready (from Kubernetes events and pod reads);
- autoscaler replica changes;
- each agent diagnosis and proposal;
- each human approval or dismissal, with "a key holder" as the actor;
- verification passed or failed.

### 5.2 The agent as incident commander

- **Every 30 s while the incident is open:** the agent makes up to 3 MCP calls, using the existing read tools plus `get_slo`. It then writes a **diagnosis**, `{ at, cause, confidence: low|medium|high, claims[] }`, with every claim citing fact ids and passing the existing validator.
- **Proposals:** once it names a cause with at least medium confidence, it calls `propose_remediation` with one action from the menu, the reason, and fact ids.

| Action | Effect when approved |
|---|---|
| `restart-booking` | Patch the restart annotation on the booking-service Deployment |
| `scale-booking` (n ≤ 4) | Patch booking-service's scale to n, and its HPA min to n for 10 minutes |
| `raise-hpa-min` (service, n) | Patch the named HPA's minReplicas to n for 10 minutes |
| `end-fault` | Call `DELETE /api/chaos` |

- One open proposal at a time. A newer diagnosis supersedes an unapproved one.
- **Budget:** 3 calls a cycle, 60 s a model call, at most 10 cycles an incident. After that the incident is marked `unresolved` and handed to the human.

### 5.3 Approval

- `POST /api/incidents/{id}/proposals/{n}/approve` requires the console key, and is only served from the console's REST API.
- There is no MCP tool that approves. `propose_remediation` exists; its counterpart does not.
- Approving applies the action through the new grants, records a timeline event, and sets the status to `mitigating`.
- Temporary raises (HPA min) are reverted by the console after 10 minutes and recorded in the timeline.

### 5.4 Verification and the postmortem

- **Verification:** `resolved` when both SLOs hold for 60 s. If they have not recovered 3 minutes after an approved action, a timeline entry records that and the agent re-diagnoses.
- **The postmortem** is written once, on resolve or unresolve:
  - summary;
  - impact (bookings affected, from facts);
  - timeline;
  - root cause;
  - what fixed it;
  - **time to detect** (open to first diagnosis) and **time to recover** (detected to resolved);
  - follow-ups.
- The postmortem uses the report validator. A rules-built fallback is used when the model fails.

---

## 6. MCP tools

| Tool | Access | Purpose |
|---|---|---|
| `get_slo` | public | Both SLOs now and over 15 minutes, with burn rate |
| `list_incidents` | public | Recent incidents |
| `get_incident` | public | One incident in full |
| `inject_fault` | key | Start a fault (same rules as the page) |
| `propose_remediation` | key | File a proposal on an open incident |

There is no approve tool. The MCP page's tool cards and Try-a-tool gain `get_slo`.

---

## 7. RBAC (applied by hand, once)

Added to the console Role:
- `pods`: `delete`, which the console only calls on pods it selected by `app=booking-service`. Kubernetes RBAC cannot restrict by label, so the code enforces that part.
- `deployments/scale`: `get`, `patch`, with `resourceNames` [booking-service, queue-gate].
- `deployments`: `patch`, with `resourceNames` [booking-service] (the restart annotation).
- `horizontalpodautoscalers`: `patch`, with `resourceNames` [booking-service, queue-gate].

The spec and note 13 will state the one-line `oc apply` the owner runs.

---

## 8. Pages

**Simulation page:**
- A **Chaos** card beside the run panel:
  - three fault buttons with a one-line effect and "up to 2 minutes";
  - disabled while a fault is active, with a countdown;
  - a hint to start a rush first;
  - disabled without the key.
- A site-wide red **incident banner** while an incident is open: elapsed time, both SLO readings and the agent's latest line, linking to the incident.
- Chart markers where the fault started and where a fix was applied.

**AI Agent & MCP → new sub-tab "Incidents":**
- the open incident:
  - two SLO gauges;
  - the timeline, as a vertical strip with source tags;
  - the latest diagnosis with fact chips;
  - the proposal card with Approve and Dismiss (key-gated);
  - time to detect and time to recover counters;
- past incidents in list-and-detail form, each opening to its postmortem.

**Overview:** the AI Agent & MCP tile's line adds "…and runs incidents".

---

## 9. Testing

**booking-service:**
- the latency and pool faults apply and revert at `until` without any caller;
- `seconds` is capped;
- the endpoints are internal.

**Console:**
- the one-fault lock, which survives a restart (ConfigMap);
- 409 while a fault is active;
- SLO evaluation on fabricated series: a breach, recovery, no traffic;
- a breach sustained for 30 s opens an incident, and a drill opens one at once;
- timeline events;
- the agent loop with a scripted model: diagnosis validated, then a proposal filed over MCP;
- approve refused without the key, and absent from MCP;
- actions applied through a fake Kubernetes port, and temporary raises reverted;
- verification resolving, and timing out to re-diagnose;
- the postmortem validated, and its fallback.

**Live:** a rush, then "Squeeze the pool". Expect, within about 3 minutes: an incident, a diagnosis citing pool saturation, a proposal, approval, both SLOs green for 60 s, and a postmortem with time to detect and time to recover.

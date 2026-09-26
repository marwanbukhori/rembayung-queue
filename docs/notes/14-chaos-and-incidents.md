# 14 — Chaos drills and the incident commander

**Covers:**
- The three faults, and why each one ends by itself.
- The two booking SLOs, and how an incident opens and closes.
- The agent as incident commander: it diagnoses and proposes, and a person approves.
- The guardrail, and why it is structural rather than a prompt.
- The five new MCP tools.
- The grants the owner applies by hand, and the command.

Spec: `docs/superpowers/specs/2026-09-26-chaos-incident-commander-design.md`.

---

## The loop

> break something → the SLOs notice → an incident opens → the agent investigates through MCP and proposes a fix → a **person** approves → the fix is applied → the SLOs recover and hold → the agent writes the postmortem.

A visitor with the key starts a rush, then presses a fault on the simulation page's **Chaos drill** card. Then:
- a red banner appears on every page;
- the **Incidents** tab of AI Agent & MCP shows the rest.

## The faults

| Fault | What happens | How it ends |
|---|---|---|
| Kill a booking-service pod | The console deletes the newest ready booking-service pod | The ReplicaSet replaces it |
| Slow the database | booking-service holds each booking's connection 400 ms longer | booking-service reverts at its deadline |
| Squeeze the pool | booking-service shrinks Hikari from 5 connections to 1 | booking-service restores 5 at its deadline |

**One fault at a time.** The lock is ConfigMap `chaos-state`:
- It survives a console restart.
- A second fault is refused with 409, naming the one that is running.

**Every fault ends within 2 minutes, without the console.** booking-service's `/internal/chaos` caps `seconds` at 120. A scheduled check inside booking-service puts the defaults back once the deadline passes. A console that crashes mid-drill therefore leaves nothing broken.

## SLOs and incidents

| SLO | Measured | Target |
|---|---|---|
| Booking success | 1 − 5xx ÷ all, on booking-service `/bookings`, over 5 minutes | ≥ 99% |
| Booking latency | p95 of `/bookings`, over 5 minutes | < 2 s |

**Evaluation.** The console evaluates both every 15 s. The `PrometheusRule` carries the same two conditions as alerts, `BookingSuccessSLO` and `BookingLatencySLO`.

**No traffic is "no data", not a breach.** An idle system never opens an incident.

**Shed load counts.** A 503 that booking-service sends to shed load is a 5xx like any other, so it counts against the success SLO. A heavy rush can therefore open an incident on its own, with no fault injected. That is deliberate: a visitor turned away is a visitor who did not book.

**Opening.** A drill opens an incident at once. An unplanned breach opens one after 30 s.

**Resolving.** An incident resolves when both SLOs hold for 60 s.

**A fix that has not worked.** If the SLOs have not recovered 3 minutes after an approved fix:
- the timeline says so;
- the incident goes back to open for the agent to look again.

**Storage.** Incidents live in ConfigMap `incidents`, newest 12, with the same clean-write rule as the run reports (note 12).

## The agent as incident commander

**Every 30 s while the incident is open, the agent:**
- reads the baseline facts: both SLOs, the active fault, the latest timeline;
- asks up to 3 questions through `/mcp`, with the same tools as the run agent plus `get_slo`;
- writes a diagnosis, whose claims must cite fact ids and pass the run agent's validator.

**Proposals.** Once it names a cause with at least medium confidence, it proposes one fix from a menu of four:

| Action | What approving does |
|---|---|
| `restart-booking` | Patches the restart annotation on booking-service |
| `scale-booking` n | Scales booking-service to n (at most 4), and raises its autoscaler minimum to n for 10 minutes |
| `raise-hpa-min` | Raises booking-service's or queue-gate's autoscaler minimum to n for 10 minutes |
| `end-fault` | Ends the running fault now |

**Limits.** Only one proposal is pending at a time. The agent runs at most 10 cycles an incident; after that the incident is marked `unresolved` and left to a person.

**Temporary raises.** They are put back by the console after 10 minutes, and the timeline records it.

**The postmortem** is written once, when the incident closes. It states time to detect (opened → a cause named) and time to recover (detected → resolved). It is checked by the same validator, with a rules-built fallback.

## The guardrail is structural

The agent cannot apply a fix, however it is prompted:
- There is no MCP tool that approves, applies or dismisses. A test lists the 15 tools and asserts that none of those words appears.
- `propose_remediation` only adds a pending proposal. A test asserts that it never touches `Remediation`.
- Approving is `POST /api/incidents/{id}/proposals/{n}/approve`, a console REST call that needs the key. MCP has no route to it.
- The writes themselves are narrow, named grants. The agent's MCP session never holds them; only the console's approval path uses them.

## MCP: five new tools, fifteen in all

| Tool | Access |
|---|---|
| `get_slo` | public |
| `list_incidents` | public |
| `get_incident` | public |
| `inject_fault` | key; one at a time, same rules as the page |
| `propose_remediation` | key; files a pending proposal, nothing more |

## Grants, applied by hand once

CD cannot grant itself rights (note 07), so the owner applies the Role:

```
oc apply -f deploy/base/console/rbac.yaml
```

What it adds to the console Role:
- `pods`: `delete`. RBAC cannot restrict by label, so the code only deletes a pod it selected by `app=booking-service`.
- `deployments/scale`: `get`, `update`, `patch`, on booking-service only (nothing scales queue-gate). fabric8's `scale(n)` reads the subresource and PUTs it back, hence `update`.
- `deployments`: `patch`, on booking-service only (the restart annotation).
- `horizontalpodautoscalers`: `patch`, on booking-service and queue-gate.

Incidents run behind `CONSOLE_INCIDENTS_ENABLED`, which the Deployment sets to `true`.

# Chaos Drills and an AI Incident Commander Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A visitor with the key breaks the system on purpose, and the console runs the incident: SLOs detect it, the agent investigates through MCP and proposes a fix, a human approves, recovery is verified, and a postmortem is written.

**Architecture:**
- **booking-service** gains self-reverting in-app faults.
- **The console** gains:
  - `ChaosService` (a one-fault lock in a ConfigMap);
  - `SloService` (two PromQL SLOs);
  - `IncidentWatcher` (opens, updates and resolves incidents);
  - `IncidentCommander` (the agent loop over MCP that diagnoses and proposes);
  - `Remediation` (applies approved actions through narrow RBAC).
- **The UI** gains a Chaos card, an incident banner and an Incidents sub-tab.

**Tech Stack:** Java 25 / Spring Boot 4.1 / fabric8 6.13 / Jackson 3, MCP Java SDK 2.0.1, Angular 20.

**Spec:** `docs/superpowers/specs/2026-09-26-chaos-incident-commander-design.md`

## Global Constraints

- **Faults:**
  - The three fault ids are `kill-booking-pod`, `slow-database` and `squeeze-pool`.
  - At most one fault at a time.
  - Every fault ends by itself within 120 s, whatever the console does.
- **SLOs:** booking success ≥ 99% over 5 m, and booking p95 < 2 s over 5 m, evaluated every 15 s.
- **Detection:** a breach sustained for 30 s opens an incident. No traffic means no breach.
- **Resolution:** both SLOs held for 60 s resolves it.
- **The agent loop:** every 30 s, at most 3 MCP calls a cycle and 10 cycles an incident. Diagnoses are validated by the existing `Validator`.
- **The guardrail:** there is **no** MCP tool that approves. Approval is `POST /api/incidents/{id}/proposals/{n}/approve`, keyed, REST only.
- **The remediation menu:** `restart-booking`, `scale-booking` (n ≤ 4), `raise-hpa-min` (service, n ≤ 4), `end-fault`. HPA raises revert after 10 minutes.
- **Storage:** ConfigMaps `chaos-state` and `incidents` (newest 12), each written as a clean object with its resourceVersion (the lesson from note 12).

## Review Focus

1. **The console restarting mid-fault or mid-incident.** Expected: the fault still ends by itself, the lock is still held until expiry, and the open incident is picked up from the ConfigMap. Tested in Tasks 2 and 4.
2. **Two keyed clicks at once** (two visitors pressing a fault). Expected: one wins, the other gets 409 naming the active fault. Tested in Task 2 through a 409 on the lock write.
3. **An MCP client trying to approve.** Expected: no such tool, and `propose_remediation` never applies anything. Tested in Task 7.
4. **A fault injected with no rush running** (no traffic). Expected: the drill incident opens, the SLOs say "no data", and the incident resolves when the fault ends rather than hanging. Tested in Task 4.
5. **Approving a proposal on a resolved incident, or twice.** Expected: 409, and nothing applied twice. Tested in Task 6.

---

### Task 1: booking-service faults that revert themselves

**Files:**
- Create: `booking-service/src/main/java/dev/marwan/booking/chaos/ChaosState.java`
- Create: `booking-service/src/main/java/dev/marwan/booking/chaos/ChaosController.java`
- Modify: `BookingService.claimSeat` (the delay hook)
- Test: `booking-service/src/test/java/dev/marwan/booking/chaos/ChaosStateTest.java` (plain unit test with a movable Clock), `ChaosControllerTest.java` (`@WebMvcTest`)

**Interfaces — Produces:**
- `POST /internal/chaos {"fault":"slow-database"|"squeeze-pool","seconds":int}` → `{fault, until}`. `seconds` is capped at 120; an unknown fault gives 400.
- `DELETE /internal/chaos`.
- `GET /internal/chaos` → `{fault|null, until|null}`.
- `ChaosState.delayMillis()` returns 400 while `slow-database` is active, else 0. `ChaosState.revertIfExpired()` runs `@Scheduled(fixedDelay = 2000)`.

**Behaviour:**
- `squeeze-pool` calls `hikari.getHikariConfigMXBean().setMaximumPoolSize(1)` and remembers the previous size.
- The revert restores that size and clears the fault.
- `claimSeat` calls `Thread.sleep(chaos.delayMillis())` after taking the row lock and before commit, so the connection is held longer.

**Tests:**
- A fault set for 120 s is active at +119 s and reverted at +121 s, with the pool size restored, all through `revertIfExpired()`.
- `seconds` 999 is capped to 120.
- An unknown fault gives 400.
- A second POST replaces the first. Only one fault in-app, so the console's lock is the gate.

- [ ] Write the tests, run them (FAIL), implement, run the booking-service unit tests (not the Oracle suite), and commit: "Let booking-service slow its bookings or squeeze its pool on request, and undo it by itself".

### Task 2: The console's fault lock and `/api/chaos`

**Files:**
- Create in `console/.../chaos/`: `ChaosService.java`, `ChaosController.java`, `ClusterWrites.java` (a port: `deletePod(name)`, `scale(deployment, n)`, `restart(deployment)`, `setHpaMin(hpa, n)`), `Fabric8ClusterWrites.java`
- Reuse `AnalysisStore.ConfigMapPort` (move it to a shared `store/ConfigMapPort.java`)
- Test: `ChaosServiceTest.java` (fake port and fake writes), `ChaosControllerTest.java` (`@WebMvcTest`, key rules)

**Interfaces — Produces:**
- `ChaosService.inject(String fault) → ActiveFault(fault, startedAt, until)`. It throws `Busy(active)` when a live lock exists.
- `current() → Optional<ActiveFault>`.
- `end()`.
- `record ActiveFault(String fault, Instant startedAt, Instant until)`.

**Behaviour:**
- `inject` reads ConfigMap `chaos-state`. If `until` is in the future it throws `Busy`. Otherwise it writes the new lock with the resourceVersion it read; a 409 from the API also means `Busy`. Then it applies:
  - `kill-booking-pod`: `ObjectSource.pods("booking-service")`, the newest ready one, `deletePod`, `until` = now + 90 s;
  - the others: `POST http://booking-service:8081/internal/chaos` with seconds 120, `until` = now + 120 s.
- After applying, it calls `IncidentWatcher.openDrill(fault)` (Task 4 supplies it; until then a no-op interface).
- REST:
  - `POST /api/chaos {"fault"}` is keyed (KeyFilter). It returns 201 with the fault, or 409 `{"error":"BUSY","active":…}`.
  - `GET /api/chaos` is public.
  - `DELETE /api/chaos` is keyed and calls booking-service's DELETE.

**Tests:**
- A lock held until the future gives `Busy`.
- An expired lock is replaced.
- A 409 on the lock write gives `Busy` (two clicks at once).
- `kill-booking-pod` deletes exactly one booking-service pod, the newest ready.
- A POST without the key gives 401.

- [ ] Write the tests, run them (FAIL), implement, run the suite, and commit: "Take one fault at a time through a lock that survives restarts, and apply it".

### Task 3: SLOs

**Files:**
- Create: `console/.../slo/SloService.java`, `SloReading.java`
- Modify: `deploy/base/booking-service/prometheusrule.yaml` (or wherever the existing PrometheusRule lives) to add `BookingSuccessSLO` and `BookingLatencySLO`
- Test: `SloServiceTest.java` (lambda `RangeQuery`)

**Interfaces — Produces:**
- `record SloReading(Instant at, Double successRatio, Double p95Seconds, boolean hasTraffic, boolean breached)`.
- `SloService.now() → SloReading`.
- `SloService.history(Duration) → List<SloReading>`.

**Queries** (5-minute rates on booking-service `/bookings`):
- **success:** `1 - sum(rate(...{status=~"5.."}[5m])) / sum(rate(...[5m]))`;
- **p95:** `histogram_quantile(0.95, sum by (le) (rate(..._bucket{uri="/bookings"}[5m])))`;
- **hasTraffic:** the total rate is above 0.

`breached` is `hasTraffic && (success < 0.99 || p95 > 2.0)`.

**Tests:**
- Healthy.
- A success breach.
- A latency breach.
- No traffic: not breached, `hasTraffic` false.
- Prometheus unavailable: the reading says unavailable, and is not a breach.

- [ ] Test (FAIL), implement, suite, commit: "Measure the two booking SLOs from Prometheus".

### Task 4: Incidents — open, timeline, resolve

**Files:**
- Create in `console/.../incident/`: `Incident.java` (record plus `TimelineEvent`, `Diagnosis`, `Proposal`, `Postmortem`), `IncidentStore.java` (ConfigMap `incidents`, newest 12, clean writes), `IncidentWatcher.java` (`@Scheduled(fixedDelay = 15 s)`)
- Test: `IncidentWatcherTest.java` (fake SLO readings, fake store, fake cluster, movable clock)

**Interfaces — Produces:**
- `IncidentWatcher.openDrill(String fault)`.
- `IncidentStore.open() → Optional<Incident>`, `IncidentStore.get(id)`, `IncidentStore.list()`, `IncidentStore.put(Incident)`.
- `Incident.status`: `open`, `mitigating`, `resolved` or `unresolved`.

**Behaviour, per tick:**
- read the SLOs, and append an `slo` event whenever breached changes;
- when there is no open incident and a breach has lasted 30 s, open a `breach` incident;
- append `kubernetes` events: new Warning events for booking-service and queue-gate objects, pod add/remove from reading the pods, and replica changes from the REPLICAS series;
- resolve after both SLOs are healthy, or there is no traffic and no active fault, for 60 s;
- also resolve a drill whose fault has ended with no traffic, so a drill on an idle system does not hang.

**Tests:**
- A drill opens immediately.
- A breach under 30 s does not open an incident; one over 30 s does.
- Resolve after 60 s healthy.
- An idle drill resolves once the fault ends.
- A restart (a new watcher, the same store) continues the open incident.

- [ ] Test (FAIL), implement, suite, commit: "Open, follow and resolve incidents from the SLOs and the cluster".

### Task 5: The incident commander — diagnose and propose over MCP

**Files:**
- Create: `console/.../incident/IncidentCommander.java`, `PostmortemWriter.java`
- Test: `IncidentCommanderTest.java` (the scripted `Model` from `AnalystTest`, a fake `ToolCaller`)

**Interfaces:**
- Consumes: `ToolCaller`, `Model`, `Validator`, `Facts`, `IncidentStore`.
- Produces: `IncidentCommander.cycle(Incident)` → the incident with a new `Diagnosis`, and possibly a `Proposal`. `PostmortemWriter.write(Incident)` → a `Postmortem`, model-written or from rules.

**Behaviour:**
- Every 30 s, for the open incident, up to 10 cycles:
  - the facts are the SLO reading, the active fault, and the last timeline events;
  - up to 3 tool calls through the `ToolCaller` (MCP), with `get_slo` added to the menu;
  - the model returns `{"cause","confidence","claims":[{text,facts}],"proposal":{"action","args","reason","facts"}|null}`;
  - the claims are validated. On failure: one retry, then a diagnosis "could not determine the cause" citing the SLO fact.
- A proposal is accepted only if its action is on the menu, its confidence is medium or high, and there is no open proposal. The accepted proposal is the next `Proposal(n, action, args, reason, facts, status=pending)`.
- On resolve or unresolve, `PostmortemWriter` asks the model for summary, impact, root cause, fix and follow-ups (validated), and falls back to rules otherwise. `timeToDetect` and `timeToRecover` are computed from the timeline, never by the model.

**Tests:**
- A clean diagnosis plus proposal.
- An action not on the menu is dropped.
- Low confidence gives no proposal.
- An invented number: retry, then "could not determine".
- The 10-cycle cap marks the incident unresolved.
- The postmortem's times are computed, and it falls back when the model fails.

- [ ] Test (FAIL), implement, suite, commit: "Let the agent run the incident: diagnose through MCP, propose from a fixed menu, write the postmortem".

### Task 6: Approval and remediation

**Files:**
- Create: `console/.../incident/Remediation.java`, `IncidentsController.java`
- Test: `RemediationTest.java`, `IncidentsControllerTest.java`

**Interfaces — Produces:**
- `GET /api/incidents`, `GET /api/incidents/{id}`, `GET /api/slo` (public).
- `POST /api/incidents/{id}/proposals/{n}/approve` and `/dismiss` (keyed). Each returns 409 if the incident is not open or the proposal is not pending.

**Behaviour:**
- `Remediation.apply(Proposal)` maps each action to `ClusterWrites`:
  - `restart-booking` → `restart("booking-service")`;
  - `scale-booking` n → `scale("booking-service", n)` and `setHpaMin("booking-service", n)`, with a revert scheduled at +10 m;
  - `raise-hpa-min` → `setHpaMin`, with a revert at +10 m;
  - `end-fault` → `ChaosService.end()`.
- Every action appends a `human` event ("approved by a key holder") and an action event.
- Pending reverts are kept in the incident, so a restart still performs them.

**Tests:**
- Each action produces the right writes.
- The 10-minute revert.
- Approve twice, or on a resolved incident, gives 409.
- Approve without the key gives 401.

- [ ] Test (FAIL), implement, suite, commit: "Apply an approved fix through narrow grants, and undo temporary raises".

### Task 7: MCP tools

**Files:**
- Modify: `console/.../mcp/McpTools.java`
- Test: `McpToolsTest.java`

**Tools:**
- `get_slo`, `list_incidents` and `get_incident` (public);
- `inject_fault` (keyed, through `ChaosService`, with Busy as a tool error);
- `propose_remediation` (keyed): files a pending proposal on the open incident. It never applies.

The run agent's and the commander's menu gains `get_slo`.

**Tests:**
- The tool list has 15 names and **no** name containing "approve".
- `propose_remediation` leaves the proposal pending and calls nothing in `Remediation`.
- `inject_fault` without the key is an error.

- [ ] Test (FAIL), implement, suite, commit: "Offer SLOs, incidents, drills and proposals over MCP, and never approval".

### Task 8: RBAC, the UI and the notes

**RBAC:**
- `deploy/base/console/rbac.yaml` adds:
  - `pods: delete`;
  - `deployments/scale: get, patch` with resourceNames [booking-service, queue-gate];
  - `deployments: patch` with resourceNames [booking-service];
  - `horizontalpodautoscalers: patch` with resourceNames [booking-service, queue-gate].
- The owner applies it by hand, with the command in note 14.

**UI:**
- **Simulation page:** a Chaos card with three buttons, a countdown and the key gate.
- **Site-wide:** an incident banner (polling `/api/incidents` every 5 s while one is open).
- **Charts:** markers at the fault start and the approval.
- **AI Agent & MCP:** an "Incidents" sub-tab with:
  - the SLO gauges;
  - a timeline strip with source tags;
  - the latest diagnosis with fact chips;
  - the proposal card with Approve and Dismiss;
  - time-to-detect and time-to-recover counters;
  - past incidents as list and detail, with the postmortem.
- **The Overview tile's** line adds incidents.

**Notes:** `docs/notes/14-chaos-and-incidents.md`, plus a README row. The Security page adds "the agent proposes, a human approves".

**Checks:** build, then a browser check at 1680 and 390 with mocked incidents.

- [ ] Implement, build, check in the browser, commit: "Show drills and incidents: the chaos card, the banner and the incident view".

### Task 9: Deploy and verify live

- Merge, push, and wait for CD.
- The owner runs `oc apply -f deploy/base/console/rbac.yaml` (with the RBAC change).
- A 200-customer rush at 8/s, then `squeeze-pool`. Expect, within about 3 minutes:
  - an incident;
  - a diagnosis citing pool saturation;
  - a proposal;
  - Approve;
  - both SLOs green for 60 s;
  - a postmortem with time to detect and time to recover.
- Check the fault reverted in booking-service (`GET /internal/chaos` from the console's view of it).

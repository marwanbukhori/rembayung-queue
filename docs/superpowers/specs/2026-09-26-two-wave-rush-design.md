# Two-Wave Rush: Seeing the Autoscaler Work — Design

**Date:** 2026-09-26
**Status:** Draft, awaiting review
**Builds on:**
- the customer funnel and five-section reports (`2026-09-26-agent-reports-funnel-design.md`, shipped at c424151);
- the run agent (`2026-09-25-cluster-inspector-and-run-agent-design.md` §8).

**Part of:** the three-part follow-up agreed on 2026-09-26. This is part 2; MCP is part 3.

---

## 1. Purpose

A rush today is one wave. Every customer arrives at once, waits at most 90 s, and the run is over in about two minutes. The autoscalers need about a minute to add a pod. On the last live rush queue-gate reached 10 pods, but only as the run ended. Nobody could see the new pods help.

**Success:**
- A visitor can choose a rush that sends the same wave twice, about three minutes apart.
- They watch the pods scale up between the waves.
- Afterwards, the agent's report compares the waves side by side, and says whether scaling helped:
  - pods at each wave's start;
  - p95;
  - 503s;
  - booked.

### Out of scope

- Changing the autoscalers' targets, bounds or behaviour.
- More than two waves, or waves of different sizes.
- MCP (part 3).

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Shape | Two identical waves, 3 minutes apart | Same load before and after scaling, so the pod count is the only difference |
| Sittings | Each wave gets its own fresh sitting (drop + slot) | A sitting has 250 seats and 250 tickets; wave 1 could sell it out, and wave 2 would then test sold-out answers, not pods |
| Runner | One load Job, two k6 scenarios | One run to watch, one report to read |
| Time limit | Job `activeDeadlineSeconds` 600 (from 300); k6 `maxDuration` 8m for two waves | A two-wave run takes about 5 minutes |
| Choice | "Rush shape" in the run panel: One wave (default) or Two waves | One wave stays the quick demo |

---

## 3. Starting a two-wave rush

**API:** `POST /api/drops/{dropId}/load` takes `{ "vus": N, "waves": 1 | 2 }`. When `waves` is missing, it is 1.

**When `waves` is 2**, `LoadOps`:
1. Creates a second sandbox sitting through `DropOps`, at the same admit rate as `{dropId}`, which the gate reports since part 1.
2. Starts one Job with env:
   - `DROP_ID`, `SLOT_ID`: wave 1, as today;
   - `DROP_ID_2`, `SLOT_ID_2`: wave 2;
   - `WAVES=2`, `WAVE_GAP=3m`.
3. Annotates the Job with:
   - `rembayung.dev/waves: "2"`;
   - `rembayung.dev/wave2-drop: <id>`;
   - `rembayung.dev/wave-gap-seconds: "180"`.

`LoadRun` gains `waves`, `wave2DropId`, and `currentWave` (1, 2, or 0 when between waves). `currentWave` is worked out from `secondsElapsed`: wave 1 runs from 0 to its patience, then there is a gap, and wave 2 runs from the gap onwards.

A second sitting that cannot be created refuses the run with 409, before any Job exists. The first sitting is untouched.

---

## 4. k6 (`drop.js`, both copies)

**Scenarios:** `wave1` (`startTime: 0s`) and, when `WAVES=2`, `wave2` (`startTime: WAVE_GAP`, reading `DROP_ID_2` and `SLOT_ID_2`). Both are `per-vu-iterations` with `vus: VUS` and one iteration each.

**Counters:**
- Every counter and trend from part 1 exists once per wave, suffixed `_w1` and `_w2`, and is chosen by `exec.scenario.name`.
- The summary keeps the part-1 fields as wave-1-plus-wave-2 totals, so the existing funnel still works.
- It adds `waves` and a `perWave` array: `[{wave: 1, joined, admitted, booked, …, p95, max, overloaded}, {wave: 2, …}]`. Latency is per wave.

**Duration:** `maxDuration` becomes 8m when `WAVES=2`.

---

## 5. Facts

**The window** is the whole run, as today. **Each wave has its own window**, from its start to its start + patience + 30 s, so Prometheus numbers are per wave.

**Per wave `n`:**
- The funnel, labelled `Wave n · Arrived`, `Wave n · Joined the queue`, `Wave n · Admitted`, `Wave n · Booked`.
- Outcome facts, with the same `Wave n · ` prefix.
- `Wave n · Latency p95 / max`.
- `Wave n · Overloaded (503)`.
- `Wave n · Peak DB pool in use, <pod>`.
- `Wave n · Most 5xx responses in one minute`.
- **Pods at the start:** `Wave n · Ready pods at start, <hpa>`, the REPLICAS series value at the wave's start.

**The scaling timeline:** one fact per autoscaler that changed during the run, `Scaling, <hpa>`, e.g. `2 → 6 at +1m10s, 6 → 4 at +4m50s`. It is read from the REPLICAS series over the whole run, with offsets from the run's start.

**Budget:** `Pods waiting for CPU during the run`: the pods seen Pending with a quota or insufficient-CPU reason, from the warning events. "none" when none.

One-wave runs keep today's facts unchanged.

---

## 6. Report

- Two-wave runs add a sixth section, `before_after` ("Before and after"), which is required.
- It compares the waves using facts from both. For example: "Wave 1: p95 2,100 ms on 2 queue-gate pods; wave 2: p95 400 ms on 6", and "503s fell from 40 to 0".
- It says whether scaling helped, made no difference, or did not happen before wave 2.
- **The prompt** explains this and repeats the rule against computed numbers. Differences are stated as both values, never as a computed delta or percentage.
- **The validator** requires `before_after` to be non-empty when a `Wave 2 · Arrived` fact exists. Each item there must cite at least one wave-1 and one wave-2 fact.
- **The fallback** writes:
  - one item per compared measure (ready pods, p95, 503s, booked), each citing both waves' facts;
  - a verdict: "Scaling helped" when wave 2 had more ready pods and a lower p95; "Scaling did not happen before wave 2" when the pods were equal; otherwise "No clear difference".
- **Storage:** `Report` gains `beforeAfter`. It is empty for one-wave and older runs.

---

## 7. Pages

### 7.1 Run panel

- A "Rush shape" choice: **One wave**, or **Two waves, 3 min apart**.
- The panel explains: "about 5 minutes; wave 2 arrives after the autoscaler has had time to add pods".
- The start button sends `waves`.

### 7.2 While a two-wave run is going

- **The in-flight banner** says "Wave 1 of 2", then "Waiting for wave 2 — pods scaling", then "Wave 2 of 2".
- **The charts strip** draws a vertical marker at each wave's start in all four charts, labelled W1 and W2. It is positioned from the load run's start time. The markers show while the run is live and for 15 minutes after.
- **The seats and queue card** shows wave 1's sitting until wave 2 starts, then wave 2's, titled "Wave n of 2".
- **The traffic lines** are prefixed `W1` or `W2`.

### 7.3 Reports

- `rb-funnel` draws one funnel per wave, side by side, when `Wave 1 · Arrived` exists. They stack at 390px.
- The report shows "Before and after" first for two-wave runs.
- The run list gains a "Shape" column: "1 wave" or "2 waves".

---

## 8. Testing

**k6:**
- The harness checks the per-wave summary: `perWave` has two entries, and the totals equal their sum.
- A real k6 run with `WAVES=2`, `WAVE_GAP=2s` against the stub gate shows both scenarios counting separately.

**Console:**
- `LoadOps` with `waves: 2`:
  - creates a second sitting;
  - sets the Job env, annotations and a 600 s deadline;
  - refuses with 409 when the second sitting fails, with no Job created.
- `LoadRun.currentWave` from elapsed time.
- `K6Summary` parses `perWave`.
- `Baseline` produces the per-wave facts, the ready pods at each start, and the scaling timeline from a fabricated replicas series.
- The validator requires `before_after`, with both waves cited.
- The fallback's before and after, for each verdict.
- One-wave runs are unchanged.

**UI:**
- Browser checks of the shape choice, the wave banner, the chart markers and the two funnels, with mocked runs, at 1680 and 390.

**Live:**
- A two-wave rush of 200 customers at 8/s. The report's "Before and after" states ready pods and p95 for both waves, and the charts show the markers.

# Two-Wave Rush Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An optional two-wave rush. The same wave runs twice, three minutes apart, on two fresh sittings. Visitors watch the autoscaler react, and the agent's report compares the waves.

**Architecture:**
- **Console:**
  - `LoadOps` gains a `waves` option.
  - For two waves, it creates a second sandbox sitting through `DropOps` and starts one Job whose k6 script runs a `wave2` scenario after a gap.
- **k6:** counts every part-1 counter per wave.
- **The agent:**
  - `Baseline` reads per-wave facts, ready pods at each wave's start, and a scaling timeline.
  - `Report` gains a `beforeAfter` section, which the Validator requires on two-wave runs.
- **The UI:** a shape choice, a wave-aware banner, chart markers, and two funnels.

**Tech Stack:** k6 0.53, Java 25 / Spring Boot 4.1 / fabric8 6.13 / Jackson 3, Angular 20 signals.

**Spec:** `docs/superpowers/specs/2026-09-26-two-wave-rush-design.md`

## Global Constraints

- One wave stays the default, and a one-wave run behaves exactly as before: same Job, same facts, same report.
- `waves` is 1 or 2. Anything else is a 400.
- The wave gap is 180 s (`WAVE_GAP=3m`). The Job's `activeDeadlineSeconds` is 600 for two waves and 300 for one. k6's `maxDuration` is 8m for two waves.
- A second sitting that cannot be created refuses the run with 409, and no Job is created.
- Fact labels for wave n are prefixed exactly `Wave n · `, with a middle dot (U+00B7) and spaces around it.
- The report key from the model is `before_after`, stored as `beforeAfter`. It is required when a `Wave 2 · Arrived` fact exists, and each item there cites at least one `Wave 1 · ` and one `Wave 2 · ` fact.
- There are no computed deltas or percentages in reports. Comparisons state both values.

## Review Focus

1. **A two-wave run whose wave 2 never starts** (the Job hits its deadline, or the pod is killed between waves). Expected: a report on wave 1 alone. `before_after` is not required, because there is no `Wave 2 · Arrived` fact. Tested in Task 3 (Baseline) and Task 4 (Validator).
2. **The autoscaler not scaling before wave 2** (low load, or the CPU budget is full). Expected: the fallback verdict "Scaling did not happen before wave 2", and a "Pods waiting for CPU" fact when that was the cause. Tested in Task 4.
3. **An old one-wave k6 line, or a one-wave run analysed after this change.** Expected: identical facts to today, and no Wave facts. Tested in Task 3.
4. **The page reloaded mid-run.** Expected: the banner and chart markers rebuild from `LoadRun.waves`, `secondsElapsed` and `currentWave`, not from UI memory. Checked in Task 5's browser step.
5. **Starting a second two-wave run while one is live.** Expected: the existing rule refuses it (one live run per drop), and no orphan second sitting is created. Tested in Task 2.

---

### Task 1: k6 runs two waves and counts each separately

**Files:**
- Modify: `console/src/main/resources/k6/drop.js`, `loadtest/drop.js`
- Test: the scratchpad `k6-harness.mjs` (extend it), and a real k6 run against the scratchpad `stub-gate.mjs`

**Interfaces:**
- **Env:** `WAVES` (default 1), `WAVE_GAP` (default `3m`), `DROP_ID_2`, `SLOT_ID_2`.
- **The summary:**
  - gains `waves` (1 or 2) and `perWave`: an array of `{wave, joined, admitted, booked, soldOutAtJoin, gaveUp, refusedAfterAdmission, soldOut, overloaded, faultsAtJoin, faultsAtBooking, p95, max}`;
  - keeps every existing top-level field as the sum over waves, with latency over all requests.

- [ ] **Step 1:** Extend the harness. Feed `handleSummary` metrics with `_w1` and `_w2` suffixes (`joined_w1: count(100)`, `joined_w2: count(100)`, …) and `http_req_duration{scenario:wave1}` values. Assert that `waves === 2`, that `perWave.length === 2`, that `perWave[1].joined === 100`, and that the top-level `joined === 200`. Keep the one-wave assertions from part 1: with no `_w2` metrics, `waves === 1` and `perWave.length === 1`.
- [ ] **Step 2:** Run the harness. Expected: FAIL (`waves` is undefined).
- [ ] **Step 3: Implement.**
  - Build the options from `WAVES`:
    - scenario `wave1` has `exec: 'wave'` and `env: { WAVE: '1', DROP: DROP_ID, SLOT: SLOT_ID }`;
    - when `WAVES === '2'`, scenario `wave2` has `startTime: __ENV.WAVE_GAP || '3m'` and `env: { WAVE: '2', DROP: __ENV.DROP_ID_2, SLOT: __ENV.SLOT_ID_2 }`;
    - `maxDuration` is `'8m'` when there are two waves.
  - Rename `default` to `export function wave()`, reading the drop and slot from `__ENV.DROP` and `__ENV.SLOT`.
  - Create every counter from part 1 twice at init, `name + '_w1'` and `name + '_w2'`, in `const perWave = { '1': {...}, '2': {...} }`. `wave()` picks `perWave[__ENV.WAVE]`.
  - Add `thresholds: { 'http_req_duration{scenario:wave1}': [], 'http_req_duration{scenario:wave2}': [] }` so the per-scenario latency submetrics appear in the summary data.
  - `handleSummary` builds `perWave` from the suffixed counters, adds them into the top-level totals, and sets `waves` to 2 when any `_w2` counter is non-zero or `__ENV.WAVES === '2'`.
- [ ] **Step 4:** Run the harness on both files. Expected: PASS.
- [ ] **Step 5:** Run real k6: `WAVES=2 WAVE_GAP=2s VUS=1 POLL_SECONDS=1 GATE=http://127.0.0.1:18099 DROP_ID=a SLOT_ID=1 DROP_ID_2=b SLOT_ID_2=2 k6 run -q drop.js` against `stub-gate.mjs` with `BOOK_STATUS=201`. Expected: `waves` 2, and `perWave` shows booked 1 in each wave.
- [ ] **Step 6:** Commit: "Let a rush send the same wave twice, counting each wave on its own".

---

### Task 2: LoadOps starts a two-wave run

**Files:**
- Modify: `console/src/main/java/dev/marwan/console/ops/LoadOps.java`, `LoadRun.java`
- Test: `console/src/test/java/dev/marwan/console/ops/LoadOpsTest.java`

**Interfaces:**
- Consumes: Task 1's env names.
- Produces:
  - `record SendLoad(Integer vus, Integer waves)`;
  - `LoadRun` gains `int waves, String wave2DropId, int currentWave`, appended at the end, with the existing factories passing 1, null and 0;
  - Job annotations `rembayung.dev/waves`, `rembayung.dev/wave2-drop` and `rembayung.dev/wave-gap-seconds`;
  - `LoadOps` takes a `Function<Integer, DropOps.Sandbox>` that creates a sitting at an admit rate (wired to `dropOps::createAt`, which is added to `DropOps` as `public Sandbox createAt(int admitRate)`).

- [ ] **Step 1: Write the failing tests** in `LoadOpsTest`:
  - `twoWavesCreateASecondSittingAndPassItToTheJob`: the sitting creator returns `new Sandbox("d-wave2", 77, 8)`. The captured Job has env `WAVES=2`, `DROP_ID_2=d-wave2`, `SLOT_ID_2=77` and `WAVE_GAP=3m`, with `activeDeadlineSeconds` 600 and annotation `rembayung.dev/waves` = `2`.
  - `oneWaveIsUnchanged`: no `WAVES` env, a deadline of 300, and the creator is never called.
  - `wavesOtherThanOneOrTwoAre400`.
  - `aSecondSittingThatFailsRefusesTheRunWith409AndCreatesNoJob`.
  - `aSecondTwoWaveRunWhileOneIsLiveIsRefusedBeforeASittingIsCreated`: the existing live-run refusal is checked before the creator is called.
  - `currentWaveFollowsElapsedTime`: with waves 2 and patience 90, elapsed 30 gives 1, elapsed 150 gives 0, and elapsed 200 gives 2.
- [ ] **Step 2:** Run: `.superpowers/sdd/mvn-test.sh -Dtest=LoadOpsTest`. Expected: compile errors, then FAIL.
- [ ] **Step 3: Implement.**
  - In `start`:
    - validate `waves`;
    - do the live-run check (`replaceFinishedRun` already refuses a live one, so call it first);
    - for two waves, read the first drop's admit rate from the gate (`GET /internal/drops/{id}/state`, `admitRate`) and create the second sitting, mapping a failure to `ResponseStatusException(CONFLICT, "could not create wave 2's sitting: …")`;
    - build the Job with the extra env, annotations and deadline.
  - In `describe`: read the annotations into `LoadRun`, and compute `currentWave` from `secondsElapsed` with `PATIENCE_SECONDS = 90`. Wave 1 covers 0 to 120 (patience plus booking time), the gap runs to 180, and wave 2 starts at 180. When waves is 1, currentWave is 1 while running.
- [ ] **Step 4:** Run the whole suite: `.superpowers/sdd/mvn-test.sh`. Expected: all green.
- [ ] **Step 5:** Commit: "Start a two-wave rush on two fresh sittings, three minutes apart".

---

### Task 3: Per-wave facts, pods at each start, the scaling timeline

**Files:**
- Modify: `agent/K6Summary.java`, `agent/Baseline.java`, `agent/RunWindow.java`, `agent/RunAnalyst.java`
- Test: `K6SummaryTest`, `BaselineTest`, `RunAnalystTest`

**Interfaces:**
- Consumes: Task 1's `perWave` and `waves`, and Task 2's annotations.
- Produces:
  - `K6Summary` gains `int waves` and `List<Wave> perWave`, with `record Wave(int wave, Integer joined, Integer admitted, int booked, Integer soldOutAtJoin, Integer gaveUp, Integer refusedAfterAdmission, Integer soldOut, Integer overloaded, Integer faultsAtJoin, Integer faultsAtBooking, Integer p95, Integer max)`.
  - `RunWindow` gains `int waves` and `int waveGapSeconds`. The existing constructor passes 1 and 0.
  - The fact labels are exactly:
    - `Wave n · Arrived`, `Wave n · Joined the queue`, `Wave n · Admitted`, `Wave n · Booked`;
    - `Wave n · ` plus each part-1 outcome label;
    - `Wave n · Latency p95 / max`;
    - `Wave n · Ready pods at start, <hpa>`;
    - `Scaling, <hpa>`;
    - `Pods waiting for CPU during the run`.

- [ ] **Step 1: Write the failing tests.**
  - `K6SummaryTest`: `perWave` with two entries is parsed, and an old line gives `waves` 1 and an empty `perWave`.
  - `BaselineTest`, with a two-wave k6 line and a two-wave `RunWindow` (gap 180):
    - `Wave 1 · Arrived` = `100` and `Wave 2 · Booked` = `60`;
    - with a replicas series of queue-gate at `[t0: 2, t0+60: 2, t0+75: 6, t0+180: 6, t0+300: 4]`: `Wave 1 · Ready pods at start, queue-gate` = `2`, `Wave 2 · Ready pods at start, queue-gate` = `6`, and `Scaling, queue-gate` = `2 → 6 at +1m15s, 6 → 4 at +5m0s`;
    - a Warning event with reason `FailedCreate` and message containing `exceeded quota` in the window gives `Pods waiting for CPU during the run` containing `exceeded quota`;
    - a one-wave run has no `Wave ` facts and no `Scaling` fact when replicas never change;
    - a two-wave window with a k6 line whose `perWave` has one entry (wave 2 never ran) gives only `Wave 1 · ` facts.
  - `RunAnalystTest`: a Job annotated `rembayung.dev/waves=2` and `rembayung.dev/wave-gap-seconds=180` gives a `RunWindow` with `waves` 2 and a gap of 180.
- [ ] **Step 2:** Run the three test classes. Expected: compile errors, then FAIL.
- [ ] **Step 3: Implement.**
  - **`K6Summary.parse`:** reads `waves` (default 1) and `perWave` into records.
  - **`RunAnalyst.window`:** reads the two annotations.
  - **`Baseline`, after the existing k6 facts, when `perWave.size() == 2`:**
    - For each wave, add the funnel and outcome facts with the `Wave n · ` prefix. Reuse the part-1 `outcome` helper with a prefix parameter.
    - `Wave n · Latency p95 / max`: `p95 + " / " + max + " ms"`.
  - **In `prometheus(...)`:**
    - Read the REPLICAS series once for the whole window.
    - For each HPA, `Scaling, <hpa>` lists each value change as `from → to at +XmYs`, with the offset from `w.start()`. It is omitted when the series never changes.
    - For two waves, `Wave n · Ready pods at start, <hpa>` is the last point at or before `w.start() + (n - 1) * gap`.
  - **In `warnings(...)`:** collect events whose message contains `exceeded quota` or `Insufficient cpu` into `Pods waiting for CPU during the run`, or `none` when there are none.
- [ ] **Step 4:** Run the suite. Expected: all green.
- [ ] **Step 5:** Commit: "Give two-wave runs per-wave facts, the pods at each wave's start, and a scaling timeline".

---

### Task 4: Before and after — report, validator, fallback

**Files:**
- Modify: `agent/Report.java`, `Analyst.java`, `Validator.java`, `Fallback.java`
- Test: `AnalystTest`, `ValidatorTest`, `AnalysisStoreTest`

**Interfaces:**
- Consumes: Task 3's labels.
- Produces:
  - `Report` gains `List<Claim> beforeAfter`, placed after `lookAt` and before the legacy fields;
  - `Report.sections(summary, customers, capacity, errors, lookAt)` passes an empty `beforeAfter`;
  - a new `Report.withBeforeAfter(List<Claim>)` copy method;
  - the model key `before_after`.

- [ ] **Step 1: Write the failing tests.**
  - `ValidatorTest`:
    - facts with `Wave 2 · Arrived` and an empty `beforeAfter` give a problem mentioning `before_after`;
    - a `beforeAfter` item citing only wave-1 facts gives a problem;
    - one citing a wave-1 and a wave-2 fact passes.
  - `AnalystTest`:
    - the model's `before_after` is parsed;
    - REPORT mentions `before_after`.
  - `AnalystTest` fallback tests:
    - wave 2 has more ready pods and a lower p95: the verdict contains `Scaling helped`;
    - the ready pods are equal: `Scaling did not happen before wave 2`;
    - otherwise: `No clear difference`;
    - every fallback `beforeAfter` item passes the validator.
  - `AnalysisStoreTest`: a stored report without `beforeAfter` reads back with an empty list.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Prompt:** "for runs with two waves, before_after: compare wave 1 and wave 2 — ready pods at each start, p95, 503s, booked — stating both values, then say whether scaling helped, made no difference, or did not happen before wave 2; cite at least one fact from each wave".
  - **Parser:** reads `before_after`.
  - **Validator:** the rules from Interfaces.
  - **Fallback:**
    - one item per measure where both waves have a fact: ready pods for each HPA, `Latency p95 / max`, `Overloaded (503)` and `Booked`. Each is "<measure>: wave 1 X, wave 2 Y." citing both.
    - then the verdict item, citing both waves' ready pods and p95 facts.
- [ ] **Step 4:** Run the suite. Expected: all green.
- [ ] **Step 5:** Commit: "Compare the two waves in a required before-and-after section, with a rules-built verdict".

---

### Task 5: The run panel, banner, chart markers, seats card and traffic

**Files:**
- Modify: `console/ui/src/app/run-panel.ts`, `run-banner.ts`, `charts-strip.ts`, `visitor.ts` (the seats card), `traffic-log.ts`, `load.service.ts` and its `LoadRun` type in `state.ts` or wherever `LoadRun` is declared
- Test: `npx ng build`, and Playwright with a mocked `/api/drops/{id}/load`

**Interfaces:**
- Consumes: Task 2's `LoadRun.waves`, `wave2DropId`, `currentWave` and `secondsElapsed`.
- Produces: `LoadService.start(vus, waves)`.

- [ ] **Step 1:** The run panel gets a "Rush shape" radio pair: `One wave` (default) and `Two waves, 3 min apart`. Under it, the line: "About 5 minutes. Wave 2 arrives after the autoscaler has had time to add pods." shown only when Two waves is picked. Start sends `waves`.
- [ ] **Step 2:** The run banner, when `waves === 2`: `currentWave === 1` gives "Wave 1 of 2", `0` gives "Waiting for wave 2 — pods scaling", and `2` gives "Wave 2 of 2".
- [ ] **Step 3:** The charts strip draws a thin vertical line with a `W1` or `W2` label at the run's start time (now minus `secondsElapsed`), and for wave 2 at plus 180 s, when the time is inside the chart window. The lines show while the run is live and for 15 minutes after it ends. They are computed from `LoadRun`, so a reload keeps them.
- [ ] **Step 4:** The seats card is fed `wave2DropId` once `currentWave === 2`, and titled "Wave n of 2". Traffic lines are prefixed `W1` or `W2` from `currentWave` at the moment they are logged.
- [ ] **Step 5:** Build. Then in the browser, mock a load run at `secondsElapsed` 30, 150 and 200 with `waves` 2. Assert the banner text for each, two markers on the replicas chart at 200, and that a reload at 150 still shows "Waiting for wave 2". At 390 there is no sideways scroll.
- [ ] **Step 6:** Commit: "Offer a two-wave rush and show which wave is running, on the banner, the charts and the seats".

---

### Task 6: Reports show two funnels, before and after, and the shape

**Files:**
- Modify: `console/ui/src/app/funnel.ts`, `analysis-report.ts`, `analysis.ts`, `agent-page.ts`, and `agent/AnalysesController.java` (Summary gains `int waves`)
- Test: `AnalysesControllerTest` (waves in the list), `npx ng build`, Playwright with mocks

- [ ] **Step 1:** `AnalysesControllerTest`: a run whose facts include `Wave 2 · Arrived` lists with `waves` 2, and others with 1. Run it (FAIL), implement (`facts.stream().anyMatch(label startsWith "Wave 2 · ")`), and run again (PASS).
- [ ] **Step 2:** `funnel.ts` takes an optional `prefix` input (`''`, `'Wave 1 · '` or `'Wave 2 · '`). `analysis-report.ts` renders two `rb-funnel`s side by side (a grid of 2 columns, 1 under 700px) with the headings "Wave 1" and "Wave 2" when `Wave 2 · Arrived` exists, and the single funnel otherwise.
- [ ] **Step 3:** The report groups put "Before and after" first when `beforeAfter` is non-empty.
- [ ] **Step 4:** The agent page's run list gains a "Shape" column: "1 wave" or "2 waves".
- [ ] **Step 5:** Build. Then in the browser, with a mocked two-wave analysis and a one-wave one: the two funnels, "Before and after" first, and the shape column. At 390 the funnels stack.
- [ ] **Step 6:** Commit: "Draw each wave's funnel and lead two-wave reports with the before-and-after".

---

### Task 7: Deploy and verify live

- [ ] **Step 1:** Run the whole console suite and the gate suite. Merge to main, push, and wait for `ci` and `CD` to succeed.
- [ ] **Step 2:** Start a sandbox at `admitRate` 8, then `POST /api/drops/{id}/load` with `{"vus":200,"waves":2}`. Poll the load run and watch `currentWave` go 1 → 0 → 2.
- [ ] **Step 3:** After the Job ends, the analysis has `Wave 1 · ` and `Wave 2 · ` facts, a `Scaling, queue-gate` fact, and a non-empty `beforeAfter` whose items cite both waves.
- [ ] **Step 4:** Open the live simulation page during a second run and see the banner and markers. Open the AI Agent page and see the two funnels and the before-and-after.

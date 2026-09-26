# Agent Reports: Funnel, Sections and Run List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After every rush the agent explains where every customer went.
- The report has five sections, and each item cites the funnel facts it rests on.
- The page draws the funnel.
- The AI Agent page lists every analysed run.

**Architecture:**
- k6 counts each customer's funnel steps and final outcome, and prints them in its `K6_SUMMARY` line.
- The queue-gate exposes its admit rate.
- `Baseline` turns those counts into funnel facts.
- `Report` grows from three lists to five sections, keeping the old two as legacy fields.
- The UI gains a run list, a funnel chart and section-aware rendering.

**Tech Stack:** k6 0.53 JavaScript, Java 25 / Spring Boot 4.1 / Jackson 3 (console, queue-gate), Angular 20 signals.

**Spec:** `docs/superpowers/specs/2026-09-26-agent-reports-funnel-design.md`

## Global Constraints

- Report keys from the model: `summary`, `customers`, `capacity`, `errors`, `look_at`. At most 3 items per section.
- Validation is unchanged: fact ids exist, and every number appears in a cited fact. It adds one rule: when funnel facts exist, `summary` and `customers` must each have at least one item.
- The funnel is drawn from facts, never from model text.
- Old stored reports (`wentWell`, `caught`, `lookAt`) must still be read and shown.
- Old `K6_SUMMARY` lines, without the new fields, must still parse. The funnel facts are then replaced by one "customer outcomes unavailable" fact.
- All times are shown in GMT+8 via `malaysiaTime`.
- No new model tools.

## Review Focus

1. **Outcomes that do not add up to Arrived** (a customer counted twice, or never). Expected: impossible by construction. Task 1's harness asserts that the sum equals the VUs.
2. **A run from before this change** (old k6 line, or old three-list report) opened in the list. Expected: it renders with what it has; no blank page and no error. Tested in Tasks 3 and 4, and checked in the browser in Task 6.
3. **A funnel with zeros** (0 booked, or everyone sold out at join). Expected: the bars render at their true widths, including 0, and the labels still read. Checked in Task 6 with a mocked zero-booked run.
4. **The gate not returning `admitRate`** (older gate during a rolling deploy). Expected: an "Admit rate: unavailable" fact, not a crash. Tested in Task 3.
5. **The model omitting `summary` or `customers`.** Expected: a validation problem, one retry, then the fallback, which has both. Tested in Task 4.

---

### Task 1: k6 counts funnel steps and outcomes

**Files:**
- Modify: `console/src/main/resources/k6/drop.js`, `loadtest/drop.js`
- Test: `/tmp/k6-harness.mjs` (scratch; not committed), then a real local k6 run

**Interfaces:**
- Produces, as new JSON fields of the `K6_SUMMARY` line:
  - funnel steps: `joined`, `admitted`
  - outcomes: `soldOutAtJoin`, `gaveUp`, `refusedAfterAdmission`, `soldOut`, `overloaded`, `faults`
  - queue wait (integers): `queueWaitP50`, `queueWaitP95`, `queueWaitMax`
  - constants: `partySize`, `patienceSeconds`
- Existing fields are unchanged.

- [ ] **Step 1: Write the harness that checks the summary maths.** It evaluates `handleSummary` with a fabricated `data` object and asserts every field.

```js
// /tmp/k6-harness.mjs  — run with: node /tmp/k6-harness.mjs <path-to-drop.js>
import fs from 'node:fs';
const src = fs.readFileSync(process.argv[2], 'utf8');
const body = src.slice(src.indexOf('export function handleSummary')).replace('export function', 'return function');
const handleSummary = new Function('__ENV', body)({ VUS: '200', POLL_SECONDS: '90' });
const count = (n) => ({ values: { count: n } });
const data = {
  metrics: {
    iterations: count(200), joined: count(195), admitted: count(90), bookings_created: count(88),
    bookings_rejected: count(112), outcome_sold_out_at_join: count(5), outcome_gave_up: count(105),
    outcome_refused_after_admission: count(0), outcome_sold_out: count(0), outcome_overloaded: count(2),
    outcome_faults: count(0),
    queue_wait: { values: { med: 44.2, 'p(95)': 85.9, max: 89.1 } },
    http_req_duration: { values: { med: 8, 'p(95)': 2011, max: 13753 } },
  },
  root_group: { checks: [{ name: 'booking resolved cleanly', passes: 193, fails: 2 }] },
  state: { testRunDurationMs: 95000 },
};
const line = handleSummary(data).stdout.split('\n').find((l) => l.startsWith('K6_SUMMARY '));
const s = JSON.parse(line.slice('K6_SUMMARY '.length));
const expect = { joined: 195, admitted: 90, booked: 88, soldOutAtJoin: 5, gaveUp: 105, soldOut: 0,
  refusedAfterAdmission: 0, overloaded: 2, faults: 0, queueWaitP50: 44, queueWaitP95: 86, queueWaitMax: 89,
  partySize: 2, patienceSeconds: 90, vus: 200 };
let ok = true;
for (const [k, v] of Object.entries(expect)) { if (s[k] !== v) { ok = false; console.log('FAIL', k, s[k], '!=', v); } }
const outcomes = s.booked + s.soldOutAtJoin + s.gaveUp + s.refusedAfterAdmission + s.soldOut + s.overloaded + s.faults;
if (outcomes !== s.vus) { ok = false; console.log('FAIL outcomes sum', outcomes, '!=', s.vus); }
console.log(ok ? 'PASS' : 'FAILED'); process.exit(ok ? 0 : 1);
```

- [ ] **Step 2: Run it against the current script.**
  Run: `node /tmp/k6-harness.mjs console/src/main/resources/k6/drop.js`
  Expected: `FAIL joined undefined != 195` and more, then `FAILED`.

- [ ] **Step 3: Count one step at a time and exactly one outcome per customer.** Apply this to both `drop.js` files. Keep the existing counters and checks. `PARTY_SIZE` replaces the literal `2` in the booking body.

```js
import { Counter, Trend } from 'k6/metrics';
const joined = new Counter('joined');
const admitted = new Counter('admitted');
const outcome = {
  soldOutAtJoin: new Counter('outcome_sold_out_at_join'),
  gaveUp: new Counter('outcome_gave_up'),
  refusedAfterAdmission: new Counter('outcome_refused_after_admission'),
  soldOut: new Counter('outcome_sold_out'),
  overloaded: new Counter('outcome_overloaded'),
  faults: new Counter('outcome_faults'),
};
const queueWait = new Trend('queue_wait');   // seconds from joining to admission
const PARTY_SIZE = 2;
const PATIENCE_SECONDS = Number(__ENV.POLL_SECONDS || 90);

// in default():
//   join 409  -> outcome.soldOutAtJoin.add(1); return;
//   join other non-200 -> outcome.faults.add(1); return;
//   join 200  -> joined.add(1); remember const joinedAt = Date.now();
//   poll loop: when admitted === true -> admitted.add(1); queueWait.add((Date.now() - joinedAt) / 1000); isAdmitted = true; break
//   (the loop runs PATIENCE_SECONDS times; a 404 also ends it without admission)
//   booking 201 -> bookingsCreated.add(1)
//   booking 403 -> (isAdmitted ? outcome.refusedAfterAdmission : outcome.gaveUp).add(1)
//   booking 409 -> outcome.soldOut.add(1)
//   booking 503 -> outcome.overloaded.add(1)
//   otherwise   -> outcome.faults.add(1)
//   every non-201 booking still adds bookingsRejected (existing behaviour)
```

  In `handleSummary`, add to `summary`:

```js
    joined: count('joined'),
    admitted: count('admitted'),
    soldOutAtJoin: count('outcome_sold_out_at_join'),
    gaveUp: count('outcome_gave_up'),
    refusedAfterAdmission: count('outcome_refused_after_admission'),
    soldOut: count('outcome_sold_out'),
    overloaded: count('outcome_overloaded'),
    faults: count('outcome_faults'),
    queueWaitP50: Math.round(wait('med')),
    queueWaitP95: Math.round(wait('p(95)')),
    queueWaitMax: Math.round(wait('max')),
    partySize: PARTY_SIZE,
    patienceSeconds: PATIENCE_SECONDS,
```

  with this helper beside `trend`: `const wait = (key) => (m.queue_wait ? Number(m.queue_wait.values[key] || 0) : 0);`

- [ ] **Step 4: Run the harness on both files.**
  Run: `node /tmp/k6-harness.mjs console/src/main/resources/k6/drop.js && node /tmp/k6-harness.mjs loadtest/drop.js`
  Expected: `PASS` twice.

- [ ] **Step 5: Prove the script runs under real k6.**
  Run: `VUS=2 GATE=http://127.0.0.1:9 POLL_SECONDS=0 k6 run -q console/src/main/resources/k6/drop.js 2>&1 | grep K6_SUMMARY`
  Expected: one `K6_SUMMARY` line with `"faults":2` (both joins fail against a closed port) and `"joined":0`.

- [ ] **Step 6: Commit.**
  `git add console/src/main/resources/k6/drop.js loadtest/drop.js && git commit -m "Count each customer's path through the queue and how their booking ended"`

---

### Task 2: The gate reports its admit rate

**Files:**
- Modify: `queue-gate/src/main/java/dev/marwan/gate/web/InternalController.java` (the `DropState` record and `of`)
- Modify: `console/src/main/java/dev/marwan/console/state/DropState.java`, `DemoState.java`, `DemoStateProvider.java`
- Test: the existing gate test that GETs `/internal/drops/{id}/state` (find it with `grep -rln "state" queue-gate/src/test/java | xargs grep -l internal`); `console/src/test/java/dev/marwan/console/state/DemoStateTest.java`

**Interfaces:**
- Produces the gate's JSON field `admitRate` (int).
- Produces the console's `DropState.admitRate()` as `Integer`, null when absent.
- Produces `DemoState.admitRate()` as `Integer`. The canonical constructor gains it last. An 11-argument constructor stays and passes null, so existing callers compile unchanged.

- [ ] **Step 1: Write the failing tests.**
  - Gate: in the state test, add `.andExpect(jsonPath("$.admitRate").value(<the rate the test created the drop with>))`.
  - Console, in `DemoStateTest`: a `DemoStateProvider` bound to `MockRestServiceServer` answers the gate with `{"dropId":"d","slotId":1,"ticketsIssued":3,"admitted":2,"waiting":1,"ticketCap":250,"admitRate":8}`, and `currentFor("d").admitRate()` is `8`. A body without `admitRate` gives `null`.
- [ ] **Step 2: Run them.**
  Run: `cd queue-gate && ./mvnw -q test -Dtest='*Internal*'` and `.superpowers/sdd/mvn-test.sh -Dtest=DemoStateTest`.
  Expected: FAIL. There is no `admitRate` in the gate JSON, and `admitRate()` does not exist in the console.
- [ ] **Step 3: Implement.**
  - Gate: add `int admitRate` as the last component of `DropState`, and pass `drop.admitRate()` in `of`.
  - Console: add `Integer admitRate` last to `DropState`, and add `Integer admitRate` last to `DemoState` with:

```java
    public DemoState(boolean available, String detail, String dropId, long slotId, int capacity, int seatsTaken,
                     int remaining, int oversold, long ticketsIssued, long admitted, long waiting) {
        this(available, detail, dropId, slotId, capacity, seatsTaken, remaining, oversold,
                ticketsIssued, admitted, waiting, null);
    }
```

  In `DemoStateProvider`, where the `DemoState` is built from `drop`, pass `drop.admitRate()`.
- [ ] **Step 4: Run both test suites.** Expected: PASS, and the console suite is still all green.
- [ ] **Step 5: Commit.** `git commit -am "Report each drop's admit rate from the gate through to the console"`

---

### Task 3: Funnel facts

**Files:**
- Modify: `console/src/main/java/dev/marwan/console/agent/K6Summary.java` and `Baseline.java`
- Test: `K6SummaryTest.java`, `BaselineTest.java`

**Interfaces:**
- Consumes: Task 1's fields and Task 2's `DemoState.admitRate()`.
- Produces:
  - `K6Summary` gains `Integer joined, admitted, soldOutAtJoin, gaveUp, refusedAfterAdmission, soldOut, overloaded, faults, queueWaitP50, queueWaitP95, queueWaitMax, partySize, patienceSeconds`. Each is null when the field is absent.
  - `boolean hasOutcomes()`, true when `joined`, `admitted` and `gaveUp` are all non-null.
  - Fact labels, used exactly as written by later tasks:
    - funnel steps: `Arrived`, `Joined the queue`, `Admitted`, `Booked`
    - `Seats taken by this run`
    - outcomes: `Gave up waiting (403)`, `Sold out at the queue (409)`, `Sold out at booking (409)`, `Admitted but refused (403)`, `Overloaded (503)`, `Other faults`
    - `Queue wait p50 / p95 / max`, `Party size`, `Queue patience`, `Admit rate`
    - `Tickets issued`, `Admitted by the gate`, `Still waiting`, `Seats taken / capacity`
    - `Customer outcomes`, for the unavailable case

- [ ] **Step 1: Write the failing tests.**
  - `K6SummaryTest`:
    - a line with the new fields populates them, and `hasOutcomes()` is true;
    - the existing old-line test additionally asserts `hasOutcomes()` is false and `gaveUp()` is null.
  - `BaselineTest`: the k6 log in `cluster()` becomes a new-format line with `"vus":200,"joined":195,"admitted":90,"booked":88,"soldOutAtJoin":5,"gaveUp":105,"refusedAfterAdmission":0,"soldOut":0,"overloaded":2,"faults":0,"queueWaitP50":44,"queueWaitP95":86,"queueWaitMax":89,"partySize":2,"patienceSeconds":90`, plus the existing fields. The `DemoState` stub passes `admitRate` 1. Assertions:
    - `Arrived` = `200`, `Joined the queue` = `195`, `Admitted` = `90`, `Booked` = `88`, `Seats taken by this run` = `176`
    - `Gave up waiting (403)` = `105`, `Sold out at the queue (409)` = `5`, `Overloaded (503)` = `2`
    - no fact labelled `Sold out at booking (409)` (it is zero, so omitted)
    - `Admit rate` = `1 per second`, `Queue patience` = `90 s`, `Party size` = `2`, `Queue wait p50 / p95 / max` = `44 / 86 / 89 s`
    - `Still waiting` = `0`, since the stub's `waiting` is 0
  - New test `anOldK6LineGivesOneOutcomesUnavailableFact`: the old-format line, then `Customer outcomes` starts with `unavailable`, and there is no `Arrived` fact.
  - New test `aGateWithoutAnAdmitRateSaysSo`: `admitRate` null gives `Admit rate` = `unavailable: the gate did not report it`.
- [ ] **Step 2: Run them.**
  Run: `.superpowers/sdd/mvn-test.sh -Dtest='K6SummaryTest,BaselineTest'`
  Expected: compile errors (no such accessors), then FAIL.
- [ ] **Step 3: Implement.**
  - `K6Summary.parse` reads each new field with `n.hasNonNull(f) ? n.get(f).asInt() : null`.
  - In `Baseline.k6(...)`, after the existing four facts:

```java
        if (!s.hasOutcomes()) {
            facts.add("k6", "Customer outcomes", "unavailable: this run's k6 script predates outcome counting");
            return;
        }
        facts.add("k6", "Party size", String.valueOf(s.partySize()));
        facts.add("k6", "Queue patience", s.patienceSeconds() + " s");
        facts.add("k6", "Arrived", String.valueOf(s.vus()));
        facts.add("k6", "Joined the queue", String.valueOf(s.joined()));
        facts.add("k6", "Admitted", String.valueOf(s.admitted()));
        facts.add("k6", "Booked", String.valueOf(s.booked()));
        facts.add("k6", "Seats taken by this run", String.valueOf(s.booked() * s.partySize()));
        outcome(facts, "Gave up waiting (403)", s.gaveUp());
        outcome(facts, "Sold out at the queue (409)", s.soldOutAtJoin());
        outcome(facts, "Sold out at booking (409)", s.soldOut());
        outcome(facts, "Admitted but refused (403)", s.refusedAfterAdmission());
        outcome(facts, "Overloaded (503)", s.overloaded());
        outcome(facts, "Other faults", s.faults());
        facts.add("k6", "Queue wait p50 / p95 / max",
                s.queueWaitP50() + " / " + s.queueWaitP95() + " / " + s.queueWaitMax() + " s");
```

  with `private static void outcome(Facts f, String label, Integer n) { if (n != null && n > 0) f.add("k6", label, String.valueOf(n)); }`.

  - In `oversold(...)`, when the state is available, also add:
    - `Admit rate`: `s.admitRate() == null ? "unavailable: the gate did not report it" : s.admitRate() + " per second"`
    - `Tickets issued`: `ticketsIssued`
    - `Admitted by the gate`: `admitted`
    - `Still waiting`: `waiting`
    - `Seats taken / capacity`: `seatsTaken + " / " + capacity`
- [ ] **Step 4: Run the suite.** Run: `.superpowers/sdd/mvn-test.sh`. Expected: all green.
- [ ] **Step 5: Commit.** `git commit -am "Turn each run's customer counts into funnel facts the report can cite"`

---

### Task 4: Five-section reports, validation and fallback

**Files:**
- Modify: `agent/Report.java`, `Analyst.java`, `Validator.java`, `Fallback.java`
- Test: `AnalystTest.java`, `ValidatorTest.java`, `AnalysisStoreTest.java`

**Interfaces:**
- Produces `record Report(List<Claim> summary, List<Claim> customers, List<Claim> capacity, List<Claim> errors, List<Claim> lookAt, List<Claim> wentWell, List<Claim> caught)`:
  - The last two are legacy.
  - A compact constructor turns nulls into `List.of()`.
  - `all()` streams all seven.
  - It adds `static Report sections(List<Claim> summary, List<Claim> customers, List<Claim> capacity, List<Claim> errors, List<Claim> lookAt)`.
- Produces the `Validator.problems` rule: if any fact labelled `Arrived` exists, empty `summary` or `customers` each add a problem naming the section.

- [ ] **Step 1: Write the failing tests.**
  - `AnalystTest.GOOD_REPORT` becomes five keys: `summary` citing F6, `customers` citing F2, `capacity` citing F7, `errors` citing F5, and `look_at` empty.
  - Add `aReportWithoutTheCustomersSectionIsRetriedThenFallsBackWhenFunnelFactsExist`: the baseline gains an `Arrived` fact, and the model answers twice without `customers`. Then `source` is `fallback`, and `problems` mentions `customers`.
  - `theFallbackReportCitesOnlyRealFactsAndPassesValidation` adds funnel facts to its baseline, then asserts:
    - `summary` has one item citing the `Booked` and `Arrived` fact ids;
    - `customers` has one item per outcome fact;
    - the validator finds no problems.
  - Add `aGaveUpMajorityGetsALookAtAboutTheAdmitRate`.
  - `AnalysisStoreTest`: add `aReportStoredBeforeSectionsIsStillRead`. It puts a raw JSON entry with `"report":{"wentWell":[{"text":"Fine.","facts":["F1"]}],"caught":[],"lookAt":[]}` into the fake port's data, and `store.get(key).orElseThrow().report().wentWell()` has 1 item.
- [ ] **Step 2: Run them.**
  Run: `.superpowers/sdd/mvn-test.sh -Dtest='AnalystTest,ValidatorTest,AnalysisStoreTest'`
  Expected: compile errors, then FAIL.
- [ ] **Step 3: Implement.**
  - **`Report`:** as in Interfaces.
  - **`Analyst.REPORT` prompt:** the new keys and rules. "summary: 1–2 items, the headline; customers: where the customers went, one item per drop-off with its reason; capacity: pool and replicas; errors: 503s, faults, timeouts and warnings, or say none; look_at: what to try next. At most 3 items each."
  - **`Analyst.report(node)`:** reads `summary`, `customers`, `capacity`, `errors` and `look_at` into `Report.sections(...)`. A node that has none of the five keys returns null.
  - **`Analyst.SYSTEM`:** adds the sentence "A queue drained at N per second admits about N × patience customers; customers not admitted in time book anyway and are refused with 403."
  - **`Validator`:** the rule from Interfaces.
  - **`Fallback`:** builds the five sections from labels:
    - **summary:** "B of A customers booked S seats; oversold O.", citing `Booked`, `Arrived`, `Seats taken by this run` and `Seats oversold`. When there are no funnel facts, it uses the `Bookings` line as today.
    - **customers:** one item per outcome fact, e.g. "105 gave up waiting (403).", citing it. It adds "Admit rate: …" citing the `Admit rate` fact when present.
    - **capacity:** the pool line (as today), plus each `Peak replicas` fact.
    - **errors:** the existing 5xx, pool-timeout, restart, warning and unavailable facts, or "No errors, pool timeouts, restarts or warnings were recorded." citing F1.
    - **lookAt:** when gaveUp > joined / 2, "Most customers gave up waiting: raise the admit rate or the patience.", citing `Gave up waiting (403)` and `Joined the queue`. When any peak pool fact equals the `DB pool size` fact, "The pool saturated: look at connection hold times.", citing both.
- [ ] **Step 4: Run the suite.** Run: `.superpowers/sdd/mvn-test.sh`. Expected: all green.
- [ ] **Step 5: Commit.** `git commit -am "Write reports in five sections, require the customer story, and read older reports"`

---

### Task 5: The run list carries the headline numbers

**Files:**
- Modify: `agent/AnalysesController.java`
- Test: `AnalysesControllerTest.java`

**Interfaces:**
- Produces `Summary(String key, String job, String dropId, Instant start, Instant end, Instant analysedAt, String source, String model, String note, int claims, Integer customers, Integer booked, Integer seats, Integer oversold)`.
- The four new fields are parsed from the facts labelled `Arrived`, `Booked`, `Seats taken by this run` and `Seats oversold`, and are null when absent or not numeric.

- [ ] **Step 1: Write the failing test.** `listsCarryTheHeadlineNumbers`: an analysis whose facts include those four labels with values `200`, `88`, `176` and `0`. `GET /api/analyses` then has `$[0].customers` 200, `$[0].seats` 176 and `$[0].oversold` 0.
- [ ] **Step 2: Run it.** Expected: FAIL (no such JSON path).
- [ ] **Step 3: Implement** with a helper `static Integer number(List<Fact> facts, String label)` that returns `Integer.valueOf(value)` if the value is all digits, else null.
- [ ] **Step 4: Run the suite.** Expected: all green.
- [ ] **Step 5: Commit.** `git commit -am "Give each listed run its customers, bookings, seats and oversold count"`

---

### Task 6: UI — run list, funnel, sections

**Files:**
- Create: `console/ui/src/app/funnel.ts`
- Modify: `analysis.ts`, `analysis-report.ts`, `agent-page.ts`
- Test: `npx ng build`, then Playwright with mocked `/api/analyses` and `/api/analyses/{key}`

**Interfaces:**
- Consumes: Task 5's `Summary` fields; Task 4's report JSON keys `summary`, `customers`, `capacity`, `errors`, `lookAt`, `wentWell` and `caught`; Task 3's fact labels.
- Produces `<rb-funnel [facts]="AgentFact[]" />`. It renders nothing when `Arrived` is absent.

- [ ] **Step 1: `analysis.ts`.**
  - `AgentReport` gains optional `summary`, `customers`, `capacity` and `errors`.
  - `wentWell` and `caught` become optional.
  - `AnalysisSummary` gains `customers`, `booked`, `seats` and `oversold`, each `number | null`.
- [ ] **Step 2: `funnel.ts`.**
  - It finds the facts `Arrived`, `Joined the queue`, `Admitted` and `Booked`.
  - Each renders as a row: label, count, and a bar whose width is `count / arrived * 100%`, with `min-width: 2px` for 0 so the bar still shows.
  - Between rows it lists the outcome facts that explain that drop-off:
    - Arrived → Joined: `Sold out at the queue (409)` and `Other faults`
    - Joined → Admitted: `Gave up waiting (403)`
    - Admitted → Booked: `Admitted but refused (403)`, `Sold out at booking (409)`, `Overloaded (503)`
  - Each explanation reads as text, e.g. "105 gave up waiting (403)".
  - Under Booked, it shows `Seats taken by this run` and `Seats taken / capacity`.
  - Colours: the bar is `var(--ink)`, and drop-offs use `var(--chip-warn-fg)`. Nothing is conveyed by colour alone.
- [ ] **Step 3: `analysis-report.ts`.**
  - It renders `<rb-funnel [facts]="a.facts" />` first.
  - `groups()` becomes these sections, each shown only when non-empty:
    - Summary
    - Where the customers went
    - Capacity and scaling
    - Errors
    - Look at
    - Went well (legacy)
    - Caught (legacy)
- [ ] **Step 4: `agent-page.ts`.**
  - After the lede, a "Runs" card holds a table. Its columns: when (`malaysiaTime(end, false)` and GMT+8), customers, booked, seats, oversold, and a source pill.
  - Rows are buttons, and the selected one is highlighted.
  - Selecting a row loads `/api/analyses/{key}` into `latest`, and sets `?run=<key>` with `history.replaceState`.
  - On init, `?run=` picks that row if present, otherwise the first.
  - "Latest real report" becomes "Run report".
  - The design sections stay below, under an h2 "How it works".
  - At 390px the table becomes stacked rows (CSS grid) with no sideways scroll.
- [ ] **Step 5: Build.** Run: `cd console/ui && npx ng build`. Expected: bundle generation complete, with no errors.
- [ ] **Step 6: Browser check at 1680 and 390.** Mock three runs:
  - a full funnel run: 200 / 195 / 90 / 88;
  - a zero-booked run: 60 / 0 / 0 / 0, with `Sold out at the queue (409)` = 60;
  - an old three-list run with no funnel facts.

  Assert:
  - the list has 3 rows;
  - clicking row 2 sets `?run=` and shows bars with the Booked width at 0 and its label visible;
  - row 3 shows "Went well" and no funnel;
  - a reload with `?run=<row 3 key>` selects row 3;
  - `scrollWidth === clientWidth` at 390.
- [ ] **Step 7: Commit.** `git add console/ui/src/app && git commit -m "List every analysed run and draw each one's customer funnel above its five sections"`

---

### Task 7: Deploy and verify live

- [ ] **Step 1:** Run the whole suite (`.superpowers/sdd/mvn-test.sh`) and the gate tests (`cd queue-gate && ./mvnw -q test`). Merge to main, push, and wait for `ci` and `CD` to succeed.
- [ ] **Step 2:** Start a sandbox at `admitRate` 1 and a 200-customer rush with the demo key, as in the earlier live check.
- [ ] **Step 3:** Within about 2 minutes of the run ending, `GET /api/analyses` lists it with `customers` 200. Its report's outcome facts sum to 200. `customers` has at least one item citing `Gave up waiting (403)`.
- [ ] **Step 4:** Open the AI Agent page on the live URL. The run is in the list, the funnel draws, and the report explains the drop-off.

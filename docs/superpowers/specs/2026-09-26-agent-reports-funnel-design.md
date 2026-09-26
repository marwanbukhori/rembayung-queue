# Agent Reports: Customer Funnel, Sections and Run List — Design

**Date:** 2026-09-26
**Status:** Draft, awaiting review
**Builds on:** the run agent (`2026-09-25-cluster-inspector-and-run-agent-design.md` §8, shipped at 2d57db4) and the AI Agent page (`2026-09-25-site-presentation-design.md` §6).
**Part of:** a three-part follow-up, agreed on 2026-09-26: (1) this design, then (2) a longer simulation that shows autoscaling, then (3) MCP. Each part gets its own spec.

---

## 1. Purpose

A visitor ran 60 customers and got 120 seats, then ran 200 customers and got 186 seats, and could not tell why. The numbers are right:
- every simulated customer books a party of 2;
- at 1 admission/s with 90 seconds of patience, only about 93 of 200 customers are admitted before giving up;
- those who gave up book unadmitted and are refused with a 403.

The agent should be the one that says this. Today it cannot: its facts hold no queue accounting, and its report has three thin lists. A visitor also sees only the latest report.

**Success:**
- After any rush, the report explains where every customer went, in numbers that add up to the customers who arrived, each resting on a fact.
- The page draws that as a funnel.
- Every analysed run can be opened from a list.

### Out of scope

- Changing the simulation itself (part 2).
- MCP (part 3).
- New tools for the model. The five from §8.3 stay as they are.

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Where outcomes come from | k6 counts each customer's outcome; the queue-gate reports its admit rate | Only the client knows why a customer did not book; the admit rate explains the 403s |
| Report shape | Five fixed sections, written by the model, each item citing facts | Structure makes reports comparable and less bland; the number check still applies |
| Funnel | Drawn by the page from facts, not from the model | A chart must always be right |
| Run list | Every stored run on the AI Agent page, newest first, `?run=<key>` | Shows the agent's work across runs, not one report |
| Old reports | Still shown, with their three original lists | The store keeps up to 12 runs from before this change |

---

## 3. Data

### 3.1 k6 summary (`drop.js`, both copies)

`handleSummary` adds these to the one `K6_SUMMARY` line.

**Outcome counters.** Each customer ends in exactly one:

| Field | Meaning |
|---|---|
| `booked` | booking answered 201 (already present) |
| `soldOutAtJoin` | joining the queue answered 409 |
| `gaveUp` | never admitted within `patienceSeconds`, then refused with 403 |
| `refusedAfterAdmission` | admitted, but the booking answered 403 |
| `soldOut` | booking answered 409 |
| `overloaded` | booking answered 503 |
| `faults` | any other status of 500 or above, or no response |

**Queue wait:**
- `queueWaitP50`, `queueWaitP95` and `queueWaitMax`: seconds from joining to admission, over admitted customers only.

**Constants the report may cite:**
- `partySize` (2) and `patienceSeconds` (90).
- The script reads both from its own configuration, so a later change to either is reported, not assumed.

**How the script counts:**
- One k6 Counter per outcome.
- One Trend for the queue wait.
- The `default` function records exactly one outcome per customer, including the early return on a 409 at join.

The existing fields (`booked`, `rejected`, `notClean`, latency, duration) stay, so older consoles and the current parser keep working.

### 3.2 Queue-gate

`InternalController.DropState` gains `admitRate`, taken from `DropRecord.admitRate()`. The console's `state.DropState` gains `Integer admitRate`, which is null when talking to an older gate.

### 3.3 Baseline facts

New facts, in this order, after the existing k6 facts:

**Configuration, from k6:**
- `Party size`
- `Queue patience`

**Configuration, from the gate:**
- `Admit rate`: "N per second", or unavailable.

**The funnel, from k6.** One fact per step, so each step can be cited:
- `Arrived`: VUs
- `Joined the queue`: arrived minus soldOutAtJoin
- `Admitted`: joined minus gaveUp
- `Booked`: booked
- `Seats taken by this run`: booked × partySize

**Where the others went, from k6.** One fact per non-zero outcome:
- `Gave up waiting (403)`
- `Sold out at the queue (409)`
- `Sold out at booking (409)`
- `Admitted but refused (403)`
- `Overloaded (503)`
- `Other faults`

**Queue wait, from k6:**
- `Queue wait p50 / p95 / max`

**At the end, from the gate and booking-service:**
- `Tickets issued`, `Admitted by the gate`, `Still waiting`: the gate's view.
- `Seats taken / capacity`: booking-service's view, across all runs on the drop.

When the k6 summary lacks the new fields (an older script, or a pod that is gone), the funnel facts are left out, and one fact says "customer outcomes unavailable: …". The report then explains what it can.

---

## 4. The report

### 4.1 Sections

The model answers with one JSON object whose keys are:

| Key | Section | Content |
|---|---|---|
| `summary` | Summary | 1–2 items: the headline outcome |
| `customers` | Where the customers went | The funnel in words, and the reason for each drop-off |
| `capacity` | Capacity and scaling | Pool use per pod; replicas before and after; whether scaling began inside the run |
| `errors` | Errors | 503s, 5xx, pool timeouts, warnings; "none" when none |
| `look_at` | Look at | What to change or try next |

- Each item is `{ "text", "facts": [ids] }`, with at most 3 items per section.
- Validation is unchanged: fact ids exist, and every number appears in a cited fact.
- Empty placeholder items are dropped (as shipped).
- A report must have at least one item in `summary` and one in `customers` when funnel facts exist. Otherwise that is a validation problem, and the model gets its one retry.

The system prompt adds, as facts it can cite rather than knowledge it may state:
- the party size and the patience;
- that `gaveUp` means refused for waiting too long;
- that a queue drained at N per second admits about N × patience customers.

### 4.2 Stored shape

`Report` becomes five lists: `summary`, `customers`, `capacity`, `errors`, `lookAt`.
- A stored report from before this change, with `wentWell`, `caught` and `lookAt`, is read into those, with `wentWell` and `caught` kept as a legacy pair.
- The UI shows whichever is present.

### 4.3 Fallback

The rules-built report gets the same five sections:
- **Summary:** "N of M customers booked S seats; oversold O."
- **Customers:** one item per funnel drop-off fact.
- **Capacity:** peak pool per pod, and peak replicas.
- **Errors:** the existing error facts, or one "none recorded" item.
- **Look at:** when `gaveUp` is over half the customers who joined, "raise the admit rate or the patience". When the pool saturated, "look at the pool". Otherwise nothing.

Every item cites its facts and passes the validator (tested).

---

## 5. Pages

### 5.1 AI Agent page

Top to bottom:
1. Title, the Live or In progress badge, and a one-paragraph lede.
2. **Runs:** one row per stored run, newest first, from `GET /api/analyses`. Each row shows:
   - when it ran (GMT+8)
   - customers
   - booked and seats
   - oversold
   - a model or fallback pill

   The summary gains `customers`, `booked`, `seats` and `oversold`, read from the facts. The selected row is highlighted, and the list shows at most 12.
3. **The selected run's report,** the latest by default, or `?run=<key>`: the funnel, then the five sections, then the trail and all facts.
4. **How it works:** the loop, the tools, the model, and the hand-written example, as now.

With no runs, the page shows the design and example, as now.

### 5.2 The funnel

- Horizontal bars for Arrived → Joined → Admitted → Booked.
- Each bar has its count and a width proportional to Arrived.
- Between bars, the drop-off with its reason and count, e.g. "107 gave up waiting (403) at 1/s".
- Under Booked: "186 seats of 250 taken".
- It is drawn from the funnel facts, and hidden when they are absent.
- Colours come from the existing tokens. Labels are text, never colour alone.
- At 390px it stacks to full-width bars.

### 5.3 Inspector

The Analysis tab uses the same report component, funnel included.

---

## 6. Testing

- **k6:**
  - The new `handleSummary`, run against a stubbed Response in the existing node harness: each outcome counted once per customer.
  - A real local k6 run proves the line prints.
- **Queue-gate:** `DropState` includes `admitRate`, in the existing controller test.
- **Console:**
  - `K6Summary` parses the new fields, and an old line without them.
  - `Baseline` produces the funnel facts, with numbers that add up: Arrived = Joined + soldOutAtJoin, and so on.
  - Funnel facts are absent with "outcomes unavailable" when the summary is old.
  - The admit rate is unavailable when the gate omits it.
  - The five-section report is parsed and validated, including the rule that `summary` and `customers` are required.
  - An old three-section report is read and served.
  - The fallback's five sections pass the validator.
  - The list summary carries customers, booked, seats and oversold.
- **UI:** a browser check at 1680 and 390 with a mocked list and report. The funnel's widths and labels are right, a row click opens that run, and `?run=` restores it.
- **Live:** after deploy, a 200-customer rush at 1/s. The report says about 93 booked and about 107 gave up, and the counts add up to 200.

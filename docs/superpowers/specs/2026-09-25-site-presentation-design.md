# Site Presentation — Design

**Date:** 2026-09-25
**Status:** Draft, awaiting review
**Builds on:** [`2026-09-25-cluster-inspector-and-run-agent-design.md`](2026-09-25-cluster-inspector-and-run-agent-design.md) (steps 1–3 shipped at 06170aa)

---

## 1. Purpose

The hosted console is the thing a hiring manager opens before the interview.
Today it undersells what is behind it: the Overview is long and its live
numbers read "—" until data arrives; a visitor without the console key cannot
start a rush, so the simulation page shows only a notice; the simulation page
stacks a short left column beside a very tall right one, so the object graph
sits below the fold and is small; and CI/CD, one of the strongest parts of the
project, has no page of its own.

This design makes the site read in one pass: a short Overview that says what
was built and shows it running, one obvious action (run a rush), and a page
each for the platform, the pipeline and the AI agent.

**Success:** someone opening the link with no context understands what was
built from the Overview in under a minute, can start a rush in one click (with
or without having been sent the key), watches it land on one screen without
scrolling to find the graph, and can see the pipeline's real runs and logs.

### Out of scope

- Building the AI agent (spec step 4). Its page describes the design and is
  marked "In progress".
- The Cluster and Build notes pages' content, apart from receiving the
  "What each tool does" section.
- Live GitHub data of any kind: the CI/CD page is a captured example (§5).

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Visitors without the key | A **Get the demo key** button takes them to the key URL | The demo must work for whoever opens it. Accepted cost: anyone can start rushes; the existing guards (one run per drop, CPU quota, expiring runs) are the limit |
| Headline | "A virtual queue and booking service, deployed on OpenShift and load-tested live" | Straightforward about what was built |
| Overview | Headline, the live system diagram, a tile grid | Short; one obvious action |
| AI Agent tile | Opens a design page marked "In progress" | Honest, and the design is itself worth showing |
| CI/CD page | Pipeline diagram, then one real CI run and one real CD run shown the way GitHub shows them: steps with durations, each expanding to its log and a short explanation; three "what it guards against" cards | Shows how the pipeline works with the real thing, not a description of it |
| Run data | A copy of the last real runs, captured once with `gh` and stored as data in the console; no live GitHub calls | It is an example of how the pipeline works, not a status page; nothing to rate-limit, no credential |
| Simulation layout | Full-width bands; the inspector is a panel over the graph's right edge (companion layout A) | Nothing waits at the bottom of a tall column; the graph gets the full width |
| Long lists | Latest 5, more on scroll | Events and logs were pushing the page down |
| Time zone | Malaysia time (GMT+8, `Asia/Kuala_Lumpur`) everywhere | The audience and the 21:00 drop are in Malaysia |

---

## 3. Site structure and entry

### 3.1 Navigation

The top bar and the Overview tiles use the same order:
**Overview · Run a simulation · Cluster · CI/CD · AI Agent · Build notes**.
Two new surfaces join the app's surface signal: `cicd` and `agent`.

### 3.2 The demo key

- `GET /api/demo-key` returns `{"key": "<value>"}` only while
  `CONSOLE_SHARE_KEY=true` is set on the console; otherwise 404. It is a GET, so
  public under `KeyFilter`.
- Without a stored key, the header shows **Get the demo key** beside the
  existing key badge. It fetches `/api/demo-key` and navigates to the current
  URL with `key=` added, which stores the key as today and reloads keyed.
- The simulation page's "read-only" notice becomes the same button.
- Turning the setting off hides the button and makes the key private again,
  with no code change.

### 3.3 Header alignment

The header's inner width follows the page: 1680px on the simulation page,
1120px elsewhere, so the logo and nav line up with the content.

---

## 4. Overview

1. **Hero:** the headline from §2 and the existing body paragraph.
2. **The live system diagram**, as now (Anyone → Route → console / queue-gate →
   redis / booking-service → Oracle → Oversold). Its values read "reading…" until
   the first poll answers, never "—"; the empty space below the diagram goes.
3. **Tile grid:**
   - **Run a simulation**, a large red tile spanning two columns.
   - **Cluster**, **CI/CD**, **AI Agent** (tagged "In progress"), **Build notes**,
     equal secondary tiles with a one-line description each.
4. Removed from the Overview: "Commit to pod" (moves to CI/CD) and "What each
   tool does" (moves to the top of Build notes).

---

## 5. CI/CD page

A captured example, not a status page: it shows how the pipeline works using
one real run of each workflow, copied once and stored in the console.

### 5.1 Pipeline diagram

The existing "How a commit reaches a pod" diagram, moved from the Overview.

### 5.2 One real CI run, one real CD run

Each is shown the way GitHub's run page shows it:

- a job header: name, result, when it ran (GMT+8) and total duration, e.g.
  **deploy · succeeded in 3m 16s**;
- every step as a row: a status tick, the step name, its duration;
- clicking a step expands its log, with GitHub's line numbers, in a dark
  monospace block: the lines as captured, long ones trimmed to the useful part;
- under each step's log, two or three sentences on what the step does and why,
  drawn from notes 06 and 07;
- a link to the run on GitHub.

The runs: the most recent successful `ci` run and its `CD` run at the time of
capture. A third, collapsed by default: **the 2026-09-25 rollback**, CD of
95ba6af, first attempt, where booking-service did not become ready in 303s and
CD rolled back to 1a3e6e1 with the site up.

Data: `cicd-runs.ts` in the console UI, one entry per run:
`{ workflow, job, runId, attempt, url, startedAt, result, duration, steps: [{ name, result, duration, log: string[], explain }] }`,
generated by a small script from `gh run view <id> --log` and `--json jobs`,
then trimmed by hand where a log is long. Refreshing the example is re-running
the script.

### 5.3 What it guards against

Three cards, each linking to its section of the build notes: **automatic
rollback** (pointing at the rollback run above), **drift check** (the cluster
must match git), and **what CD may not do** (RBAC is applied by hand; the
pipeline cannot grant itself permissions).

---

## 6. AI Agent page

Marked **In progress** at the top.

1. What it does: after every rush, gather the facts, investigate with up to
   five read-only tools, write a report whose every number is checked against
   the facts, fall back to a plain facts report if the model fails.
2. The loop as a small diagram: facts → investigate (≤5 tools) → report →
   validate → store.
3. The model: Qwen3 8B, served free inside the Developer Sandbox
   (`sandbox-shared-models`), verified reachable from this namespace.
4. **An example report**, clearly labelled as written by hand from the real
   2026-09-25 rush: 4 of 200 bookings unclean (503s); one booking-service pod's
   pool saturated while the other was idle; the saturation traced to metrics
   scrapes competing with bookings for the pool; fixed since. Each claim shows
   the fact it rests on, as the real agent's will.

---

## 7. Run a simulation (layout A)

Top to bottom, each band full width:

1. Breadcrumb, then the **"A rush is in flight"** banner (moved from above the
   seat map).
2. **Band 1:** Run a rush · Seats and queue · Live traffic (latest 5, more on
   scroll).
3. **Band 2:** the four charts in one row.
4. **Band 3:** the object graph at full width, larger. Clicking an object opens
   the inspector as a panel over the graph's right third, with a close button;
   closing gives the graph its full width back.

Below 1280px the bands stack and the inspector is a drawer, as now.

### 7.1 Lists

Inspector Events and Logs and the Live traffic list show the latest 5 and load
5 more each time the reader scrolls to the end of the list (an
`IntersectionObserver` sentinel), up to what the server returned.

### 7.2 Time

One formatter, `malaysiaTime(iso, withSeconds)`, used by every time on the
site: events, logs, traffic, chart axes and tooltips, and the captured runs'
dates. It uses `Intl.DateTimeFormat` with
`timeZone: 'Asia/Kuala_Lumpur'`, and the page says "GMT+8" once per section
that shows times.

---

## 8. Build order

Three plans, each shipping on its own:

1. **Entry, Overview and simulation page:** §3, §4, §7.
2. **CI/CD page:** §5, including the run capture script.
3. **AI Agent page:** §6.

---

## 9. Testing

- **Backend (JUnit):** `/api/demo-key` returns the key only with the setting on
  and 404 otherwise. The CI/CD page has no backend.
- **UI:** no test harness exists; each plan verifies in a browser at 1680, 1366
  and 390px: no sideways scroll, the graph at full width, the inspector panel
  opening and closing, lists loading more on scroll, times in GMT+8, and the key
  button taking a keyless visitor to a keyed page.
- **Live:** after each deploy, the pages checked on the public URL.

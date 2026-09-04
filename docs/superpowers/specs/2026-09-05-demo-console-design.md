# Demo Console — Design (Phase 7)

**Date:** 2026-09-05
**Status:** Draft, awaiting review — **supersedes the first draft of this file**
**Phase:** 7 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Previous phase:** [`2026-09-04-observability-design.md`](2026-09-04-observability-design.md)

---

## 1. Purpose

Six phases produced a system whose most interesting property — 250 seats never
oversold under a synchronised burst — is invisible. It lives in `curl`, SQL
prompts and log greps.

This phase builds a **deployed console that a stranger can drive**. Not watch:
drive. The target user is a hiring manager, opening a link three or four days
before an interview, with no cluster account, running a real drop against real
Oracle and watching the invariant hold.

The model is an internal developer platform: one front door, where every
operation currently trapped in a shell script or a SQL snippet is a named, safe,
repeatable action.

**The first draft of this spec gated all controls behind OpenShift OAuth.** That
was wrong for this goal — it gates on *cluster* identity, which the intended user
will never have. Rewritten around the real requirement.

---

## 2. What the requirement actually forces

An outsider driving real operations changes three things from the first draft.

**Authentication cannot be OpenShift OAuth.** It has to be a credential you can
hand someone.

**Operations cannot share global state.** Today the gate has exactly one drop: a
single `queue:ticket` counter in Redis, and a window in `DropProperties` read
from environment variables at startup. Two visitors would fight over the same
counter, and changing the window needs a pod restart. **This phase therefore
changes the gate**, not just the frontend.

**Load has to originate somewhere the visitor can trigger.** The first draft said
"run k6 from your laptop", which is correct engineering and useless to a stranger.

---

## 3. Per-visitor sandboxes: the idea the rest depends on

Rather than protecting a single shared demo with warnings and confirmations,
**give each visitor their own.**

A session gets a **drop** — an identifier, its own slot row, its own ticket
counter, its own window:

```
drop  d-7f3a91
  slot        1042        seeded on demand, 250 seats, its own row
  redis keys  queue:d-7f3a91:ticket
              admit:d-7f3a91:<token>
  window      opensAt / closesAt / admitRate, held in Redis
```

Everything follows from this:

- **"Reset" stops being destructive.** It means *a new drop*, not
  `DELETE FROM bookings`. Nobody truncates anything.
- **Visitors cannot interfere with each other**, or with the canonical slot 1.
- **The invariant is proven per-visitor.** Their drop, their 250 seats, their
  oversold gauge reading zero.
- **Confirmation dialogs mostly disappear**, because the blast radius does.

### What it costs in the gate

| Change | Why |
|---|---|
| Key namespacing: `queue:{dropId}:ticket`, `admit:{dropId}:{token}` | One counter per drop instead of one globally |
| Drop windows move from `DropProperties` to a Redis record | A new drop must be creatable without restarting a pod |
| `POST /queue` takes a drop id; `DropProperties` becomes the default drop's seed values | Requests must say which drop they mean |
| Slot seeding endpoint on booking-service | A drop needs a slot row of its own |
| TTL on drop records and their slots | Sandboxes must expire, or the database fills with abandoned demos |

This is contained — it is namespacing plus moving config into data — but it is
real work in the service that currently has the project's cleanest code. It also
**improves** the design: a booking system with exactly one possible drop was
always a demo artefact rather than a model of the domain.

---

## 4. Authentication

**Issued access keys**, not OpenShift OAuth.

You mint a key per recipient — `hiring-manager-acme`, `recruiter-b` — and give it
out. The console accepts it as a header or a URL fragment, stores nothing about
the person, and every operator action is logged against the key that performed
it.

Why this over the alternatives:

| Option | Verdict |
|---|---|
| OpenShift OAuth | **Rejected.** Gates on cluster identity the visitor cannot have — the flaw in the first draft. |
| One shared password | Workable, but a single revocation revokes everyone and the audit log cannot tell them apart. |
| **Per-recipient keys** | **Chosen.** Revoke one without disturbing others; the log says who ran what; issuing one is free. |
| No auth | Rejected. Even sandboxed, this triggers deploys and consumes cluster resources. |

Keys live in a Kubernetes Secret as a simple map, so adding or revoking one is a
`oc patch` and a pod restart — no user store, no password hashing, no session
database. Expiry is a date in the value; the console refuses a key past it.

**Three tiers, because not every operation deserves the same trust:**

```
public      no key     read-only: live state, docs, deploy history
visitor     a key      own sandbox: create a drop, run load, book, reset
operator    your key   global: deploy, roll back, scale, edit the canonical slot
```

A hiring manager gets a **visitor** key. It cannot touch slot 1, cannot deploy,
cannot scale. It can do everything interesting inside its own sandbox.

---

## 5. Constraints are content, not failure

The governing principle, and the one that separates this from a status page:
**when the system hits a limit, the console explains the limit.** It does not
hide it, queue silently, or degrade quietly.

A visitor whose load run sits `Pending` because the namespace is out of CPU has
just been shown a real quota, a real scheduler decision, and a real operational
trade — which is worth more than a green tick. The same is true of a connection
pool exhausting, an HPA refusing to scale past its cap, or a rollout stalling.

So the console carries a permanent **constraints panel**, live:

```
  namespace budget      900m / 3000m requested       ████░░░░░░░░
  your load job         Pending — insufficient cpu
                        booking-service 400m · queue-gate 200m · console 200m
                        free 2100m, this job wants 200m ✓ scheduling

  connection pool       4 / 20 active     (4 replicas x 5, Oracle caps ~20)
  HPA booking-service   2/4 replicas      cpu 12% of 60% target
  HPA queue-gate        2/10 replicas     cpu 8% of 60% target
```

When something cannot run, the console says which limit stopped it and what is
consuming the budget — not "something went wrong".

### Load generation

Load runs **in the cluster**, as a Kubernetes Job, because a visitor cannot run
k6 on their laptop.

**The default is 200 virtual users, and higher is offered rather than forbidden.**
200 is the measured ceiling of usefulness, not a resource compromise — Phase 3's
ladder found the sandbox router sheds connections above it:

```
offered   arrived   failed
   200       200       0%
  1000       662      75%
  3000       818      92%
```

A visitor choosing 1000 will see roughly a third of their traffic never arrive.
**That is a finding worth showing**, and the console labels it as edge shedding
rather than letting it read as application failure. Hiding the option would hide
the lesson.

**What is genuinely lost, stated on the page:** in-cluster load skips the public
Route, so it does not exercise the ingress path. A visitor sees the queue, the
admission rate and the invariant; they do not see edge behaviour unless they pick
a number large enough to provoke it.

**Bounds exist for recoverability, not to hide limits:** a hard
`activeDeadlineSeconds` so no Job can run forever, and one running Job per drop
so a single visitor cannot spam. There is no global cap on how many visitors may
run at once — if the namespace fills, the console shows the queue and the reason,
which is the more honest outcome.

## 5a. Dynamic, and easy for someone who has never seen it

The console is used by a stranger, once, unaided, with no briefing. That is a
harder audience than an operator who already knows the system, and it drives
real requirements rather than styling preferences:

- **It updates itself.** Numbers move without a refresh button. A one-second
  drop is invisible if you have to click to see it.
- **It explains as it goes.** Each control carries one line saying what it will
  do and why it matters — "admit 200/s: more than the database can absorb, watch
  the pool exhaust" — so a visitor who reads nothing else still understands what
  they just watched.
- **It has an obvious first move.** A visitor landing on the page should not have
  to work out where to start. One primary action: *Start a drop*.
- **Nothing is a dead end.** Every failure state names the next thing to try.
  A `Pending` job, a sold-out slot, an expired drop — each says what happened and
  offers the button that resolves it.
- **It reads as a system, not a form.** Seats, queue, pods, quota and the
  invariant on one screen, changing together, so cause and effect are visible.

None of this is visual polish, which is explicitly deferred. It is the difference
between a stranger understanding the system in two minutes and closing the tab.

---

## 6. What the console does

### Public — no key

Live state of the canonical drop, pod health, `oversold = 0`, deploy history read
from Deployment annotations, links out to OpenShift, Dynatrace and Splunk (with a
plain note that the last two are trials and may have expired), and the
documentation — nine notes, seven specs, five plans — rendered from Markdown
baked into the image.

### Visitor — an issued key

```
  Your drop                                    d-7f3a91
  [ Create a fresh drop ]  [ Open it ▸ ]  [ Send 200 customers ▸ ]

  slot 1042 · yours
  seats     ████████████░░░   198 / 250
  queue     ████░░░░░░░░░░     42 waiting
  admitted                     56
  oversold                      0     ← the claim, on your own data

  admission rate  [ 1/s ]  [ 8/s ]  [ 200/s ]   ← watch this break things
```

That last control is the demo. At 200/s the connection pool exhausts and the
service starts refusing with `503` and `Retry-After` — **and the invariant still
holds at zero.** A visitor discovering that themselves is worth more than any
paragraph in a README.

### Operator — your key

Deploy a tag, roll back, scale, open the canonical drop, reset slot 1, run the
keepalive job.

**Deploy and rollback are worth exposing** despite being outward-facing, because
Phase 5 proved the safety net works unattended: a bad tag times out, rolls back
each service to its own previous tag, re-verifies, and exits non-zero, with the
site serving `200` throughout. "Deploy something broken and watch it heal" is the
strongest thing this project can show. It stays operator-tier because it affects
everyone, not because it is dangerous.

---

## 7. Security

- **Enumerated operations only.** No command box, no script path, no SQL from the
  browser. A console that runs arbitrary `oc` is a remote shell with a login form.
- **Visitor keys are scoped by drop id**, checked server-side on every request.
  A visitor naming someone else's drop is refused; ownership is not a UI concern.
- **The ServiceAccount cannot read Secrets** — the same boundary `rembayung-cd`
  already proved with `oc auth can-i get secrets` → `no`.
- **SQL is parameterised and enumerated**, and visitor-tier SQL is confined to
  rows belonging to that visitor's slot.
- **Rate limits guard against abuse, not against use.** They are set well above
  anything a person clicking buttons will reach — a leaked key should cost quota,
  not correctness — and when one is hit the console says so plainly instead of
  failing opaquely.
- **Every action is a structured JSON log line** carrying key id, operation and
  outcome, shipped to Splunk with everything else. The audit trail is itself a
  demonstration.
- **Sandboxes are unlimited and instant.** A visitor may create a drop, run it,
  discard it and start again as often as they like; nothing rations sessions.
  Idle drops are swept 30 minutes after their last request, and their slot rows
  with them, reusing `ExpirySweeper`'s scheduling pattern now that it actually
  works. Sweeping on idleness rather than a fixed 24 hours is what makes
  unlimited retries affordable on a finite database.

---

## 8. Delivery order

The interview is **2026-09-11**. Each step is independently useful and the order
is chosen so that stopping early leaves something coherent.

1. **Gate: per-drop namespacing.** Redis keys, drop records, slot seeding. No UI.
   Verifiable with `curl`, and the riskiest change — first, while there is time
   to get it wrong.
2. **Console skeleton + public read-only page.** Live state, pod health, the
   invariant.
3. **Documentation rendering.** Cheap, high value, no new risk.
4. **Access keys and the visitor tier**: create a drop, open it, book.
5. **Load generation, the admission-rate control, and the constraints panel.**
   *This is the step that makes it a demo rather than a dashboard* — and the
   constraints panel ships with it, because a load run that cannot schedule must
   explain itself the first time it happens, not later.
6. **Operator tier**: deploy, roll back, scale.
7. **Deploy history, links out, polish.**

**Steps 1–3 meet the minimum.** **Steps 1–5 meet the actual goal** — a hiring
manager running a real drop unaided. Six and seven are for you, and can slip.

---

## 9. Known weaknesses

- **In-cluster load bypasses the public Route**, so it does not exercise the
  ingress path or the 600–800 connection ceiling. Stated on the page, not hidden.
- **A leaked visitor key consumes cluster quota.** Rate limits bound the damage;
  revocation is a `oc patch`.
- **The gate change touches the project's cleanest service.** Phase 2's tests are
  the guard, and the per-drop work must not weaken them.
- **Sandboxes accumulate rows** until swept. A 24-hour TTL on an Always Free
  database with finite storage is a real limit, not a formality.
- **The console is a single point of demo failure.** The system would still work
  with it down, but the demonstration would not.
- **~200m of a 3000m budget**, plus up to 200m per running load Job. Affordable
  now; the concurrency cap is what keeps it so.
- **Anything a visitor breaks inside their sandbox, they can see.** That is the
  intent, but it does mean a visitor's first impression may be of a system under
  deliberate stress rather than one at rest.

---

## 10. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| Auth | Per-recipient issued keys | OpenShift OAuth gates on cluster identity the visitor cannot have; one shared password cannot be revoked or attributed individually |
| Isolation | Per-visitor sandbox drops | Turns "reset" from a destructive global action into creating a new object; removes visitor-vs-visitor interference and most confirmation dialogs |
| Gate change | Namespace Redis keys, move drop windows into Redis | One global counter and restart-only config cannot serve concurrent visitors |
| Load generation | In-cluster k6 Job, capped at 200 VUs | A stranger cannot run k6 on their laptop. 200 is the measured ceiling above which the router sheds, so the cap costs nothing real |
| Load tradeoff | Accepted and disclosed on the page | In-cluster load skips the ingress path; hiding that would misrepresent what the number means |
| Deploy / rollback | Exposed, operator tier | Phase 5 proved unattended rollback works; "break it and watch it heal" is the strongest demonstration available |
| Operations model | Enumerated named actions | Arbitrary command execution behind a login is a remote shell |
| Sandbox lifetime | 24-hour sweep | Abandoned demos must not fill a finite Always Free database |
| Delivery order | Riskiest gate change first, controls last | Stopping early leaves a coherent artefact rather than half a control panel |

# Demo Console — Design (Phase 7)

**Date:** 2026-09-05
**Status:** Draft, awaiting review
**Phase:** 7 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Previous phase:** [`2026-09-04-observability-design.md`](2026-09-04-observability-design.md)

---

## 1. Purpose

Six phases have produced a system whose most interesting property — 250 seats
never oversold under a synchronised burst — is **invisible**. It lives in `curl`,
SQL prompts and log greps. A hiring manager will not clone a repository.

This phase builds a **deployed, authenticated console** that makes the system
demonstrable: every operation currently held in a shell script or a SQL snippet
becomes a button, the traffic becomes something you can watch, and the
documentation becomes something you can read in place.

The model is an **internal developer platform**: one front door onto a system,
where the things an operator needs to do are named, safe and repeatable rather
than remembered.

**The honest framing for an interview:** this is not a product feature. It is a
control plane for a demo, and the interesting content is what it controls —
admission rate, rollback, a database invariant — not the console itself.

---

## 2. The constraint that shapes everything

**Every button is a privileged action on a live cluster.** Reset the database,
open the drop, roll back a deploy, scale a Deployment. Exposed carelessly, that
is a public endpoint that truncates tables and restarts production.

So the design is built around three rules, in priority order:

1. **Authenticated before anything mutating.** No exceptions, no "it's only a
   demo" endpoints.
2. **Enumerated operations, never arbitrary commands.** The console exposes a
   fixed list of named actions. It never accepts a command, a script path, or
   SQL from the browser. A console that can run arbitrary `oc` is a remote shell
   with a login form.
3. **The console's own permissions are the ceiling.** It holds a ServiceAccount
   scoped to this namespace with no access to Secrets — the same boundary CD
   already proved.

---

## 3. Authentication

**OpenShift OAuth, via `oauth-proxy` as a sidecar.**

The console pods run two containers: the application, listening only on
localhost, and `openshift/oauth-proxy`, which terminates the Route and forwards
only authenticated requests.

Why this rather than a password:

- **Nothing to store.** No password hash, no session secret, no rotation. The
  project's single stored credential stays `OPENSHIFT_TOKEN`.
- **It is the same identity that owns the cluster.** Access to the console and
  access to the namespace are the same grant, so there is no second thing to
  revoke.
- **It is the platform's own mechanism**, which is a better answer than a
  bespoke login when the question is "how did you secure it".

The cost is real and worth stating: **a hiring manager cannot open it without a
cluster account.** That directly contradicts the goal of handing someone a link.

**Resolution: two surfaces, one application.**

| Surface | Auth | Content |
|---|---|---|
| **Public** `/` | none | Read-only. Live seats, queue depth, oversold, pod health, recent deploys, rendered documentation. No write endpoint exists on this path. |
| **Operator** `/ops` | OAuth | Every action. Reset, open drop, deploy, roll back, scale, run a load profile. |

A visitor gets the demo without an account. You get the controls. The split is
enforced by the proxy at the Route, not by a check inside the application, so a
missed `if` cannot expose a button.

---

## 4. Architecture

```
                     Route: console
                          │
                    ┌─────┴─────┐
                    │oauth-proxy│  /ops/*  → requires OpenShift login
                    │  sidecar  │  /       → passes through
                    └─────┬─────┘
                          │ localhost only
                  ┌───────┴────────┐
                  │  console-api   │  Spring Boot
                  └───┬────┬───┬───┘
                      │    │   │
        ┌─────────────┘    │   └──────────────┐
        │                  │                  │
  Kubernetes API      booking-service    docs on disk
  (fabric8, SA)       /internal/state    (baked into image)
  scale, rollout,     queue-gate
  pod status          /internal/state
```

**Read path.** The console never queries Oracle or Redis directly. It calls
internal state endpoints on the two existing services, which are backed by
`SlotStateProvider` and `QueueStateProvider` from Phase 6. That rule was written
into Phase 6's spec specifically so this phase could not fork the computation of
the project's central number.

**This phase adds those internal endpoints** — Phase 6 built the providers and
deliberately stopped short of exposing them.

**Write path.** Enumerated operations only, each mapping to one Kubernetes API
call or one parameterised SQL statement. No shell.

---

## 5. What the console actually does

### Public surface

**Live state**, polled every two seconds:

```
  slot 1 · 2026-10-01 19:00
  seats     ████████████░░░  202 / 250
  queue     ░░░░░░░░░░░░░░     0 waiting
  oversold                     0        ← the whole project, in one number
  pods      booking 2/2   gate 2/2   redis 1/1
```

**Deploy history** — image tag, commit, when, and whether it rolled back. Read
from the Deployments' own annotations, so it cannot disagree with reality.

**Documentation**, rendered from Markdown baked into the image: the nine notes,
the seven specs, the five plans. This is the part most likely to be read by
someone evaluating you, and it costs almost nothing.

**Links out** to OpenShift's console, the Dynatrace tenant, and the Splunk stack,
with a plain note that the last two are trials and may have expired.

### Operator surface

Each is one named operation, not a command box:

| Operation | Implementation | Danger |
|---|---|---|
| Open the drop | patch `queue-gate-config`, roll out | low |
| Reset the queue | `FLUSHALL` via the gate's own admin path | low |
| Reset seats | one parameterised `UPDATE booking.slots SET seats_taken = 0` | **destructive** |
| Pre-scale the gate | scale Deployment to 10 | quota-bound |
| Scale back | scale to 2 | low |
| Deploy a tag | trigger the CD workflow | outward-facing |
| Roll back | trigger CD with a previous tag | outward-facing |
| Run a load profile | see §6 | see §6 |

Destructive operations require a typed confirmation of the slot id. Not a modal
with an OK button — the operator has to name the thing they are about to wipe.

---

## 6. The load test, and why it does not run in the cluster

Tempting: a "run a load test" button that starts k6 in a pod.

**Rejected, on the same evidence that removed AAP and Jenkins.** The namespace
budget is 3000m; the autoscaling demonstration needs roughly 1900m; the console
itself will take ~200m. A load generator inside that budget competes for CPU
with the system it is measuring, and the numbers it produces are then about the
contention, not the system.

There is a second reason that matters more. The measured edge ceiling is 600–800
concurrent connections at the sandbox router. Load originating *inside* the
cluster bypasses that path entirely, so an in-cluster run would not exercise the
thing the load test exists to exercise.

**Instead:** the console shows the exact `k6` command for a chosen profile, ready
to copy, and then *watches* — the live view updates as the burst lands. Load
originates from a laptop, which is where load belongs.

This is a better demo anyway. "I'll run this from my machine and we watch it
together" beats a spinner.

---

## 7. Security

Beyond §2 and §3:

- **The ServiceAccount cannot read Secrets.** Same boundary as `rembayung-cd`,
  verified there with `oc auth can-i get secrets` → `no`.
- **SQL is parameterised and enumerated.** The console holds a handful of named
  statements. There is no path from an HTTP request to arbitrary SQL.
- **The database credential is a Secret mounted into the console pod**, never
  sent to the browser, never logged.
- **The public surface has no write endpoints at all** — not disabled ones,
  absent ones. Nothing to reach even if the proxy were misconfigured.
- **Every operator action is logged** as a structured JSON line carrying the
  authenticated user, the operation, and the outcome. Those go to Splunk with
  everything else, which makes the audit trail a demonstration in itself.

---

## 8. Scope, honestly

The interview is **2026-09-11**. This is six days of work compressed against a
system that already exists and must not break.

**Delivered in order, each independently useful:**

1. **Internal state endpoints** on both services, plus the public read API. No UI
   yet — verifiable with `curl`.
2. **Public read-only page.** Live state, pod health, the invariant. This alone
   satisfies "a hiring manager can see it".
3. **Documentation rendering.** Cheap, high value, no new risk.
4. **`oauth-proxy` and the operator shell**, with two safe operations: open the
   drop, reset the queue.
5. **Destructive and outward-facing operations** — seat reset, scale, deploy,
   roll back.
6. **Deploy history and links out.**

**Steps 1–3 are the minimum that meets the goal.** If time runs short, stopping
after 3 leaves a coherent, safe, deployed artefact rather than a half-built
control panel. That is the point of the ordering.

**Deliberately not in this phase:** custom theming, mobile layout, real-time
push (polling is adequate for a one-second burst), multi-user roles, and any
form of stored user account.

---

## 9. Known weaknesses

- **The console is a single point of demo failure.** If it breaks, the system
  still works but the demonstration does not. It is deliberately kept simple and
  read-mostly for that reason.
- **The public page shows a quiet system most of the time.** Outside a drop,
  seats and queue sit still. Documentation and deploy history carry the page
  between bursts; that is a real limitation, not a solved problem.
- **`oauth-proxy` ties the operator surface to a sandbox account** that expires
  with the sandbox, around 2026-10-02.
- **Polling every two seconds from many viewers costs requests.** The read path
  is cached at the console for one second so viewer count cannot amplify load
  onto the services.
- **It adds ~200m of a 3000m budget**, which is affordable now and would not be
  if the spike demonstration grew.

---

## 10. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| Auth mechanism | `oauth-proxy` sidecar, OpenShift identity | Nothing to store or rotate; the platform's own mechanism; access to the console and the namespace become one grant |
| Public vs operator | Two surfaces on one app, split at the Route | A visitor needs no account; write endpoints do not exist on the public path, so a missed check cannot expose them |
| Operations model | Enumerated named actions | A console that runs arbitrary commands is a remote shell with a login form |
| Read path | Via the services' state providers | Phase 6 built one computation of seats and oversold precisely so this phase could not fork it |
| Load generation | Laptop, not the cluster | 3000m budget, 1900m demo; and in-cluster load bypasses the measured 600–800 edge ceiling, so it would not test the real path |
| SQL | Parameterised, enumerated | No path from HTTP to arbitrary SQL |
| Destructive actions | Typed confirmation of the slot id | An OK button is not a decision |
| Real-time updates | Polling, cached 1s | A drop lasts one second; push is complexity without benefit |
| Delivery order | Read-only first, controls last | Stopping early leaves something coherent rather than half a control panel |

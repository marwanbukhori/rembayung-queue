# Continuous Delivery — Design (Phase 5)

**Date:** 2026-09-04
**Status:** Draft, awaiting review
**Phase:** 5 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Previous phase:** [`2026-09-03-continuous-integration-design.md`](2026-09-03-continuous-integration-design.md)

---

## 1. Purpose

Phase 4 gave the project a pipeline that tests every push and publishes an image
tagged with the commit that produced it. Phase 5 gets that image onto the
cluster: an Ansible playbook that sets the image tag, applies the manifests,
waits for the rollout, smoke-tests the public Route, and **rolls back to the
previous tag if the smoke test fails**.

That last clause is the phase. Anything can `oc apply`. Deciding it went wrong
and undoing it without human intervention is what makes it delivery rather than
deployment.

Demo step 2 — *push a commit, watch it reach production* — becomes runnable at
the end of this phase.

---

## 2. Why Ansible, and not Jenkins

The parent spec originally specified Jenkins. That was revised on 2026-09-03 and
the parent's decision log records it. Restated here because it is the phase's
defining choice:

**The quota forbids it.** The sandbox arrived with an Ansible Automation
Platform stack consuming **1950m of the 3000m** CPU quota, which had to be
deleted before the autoscaling demonstration could run at all. In-cluster
Jenkins (~500m) has the same problem in smaller form: the demo needs 1900m and
there is 3000m.

**Its rationale evaporated.** Jenkins was chosen because CD needed a host with
network reach into the cluster. A GitHub Actions runner authenticated with an
`oc` token has exactly that, so the server was solving a problem that no longer
exists.

**Ansible fits OpenShift better.** `kubernetes.core.k8s` is Red Hat's own module
for this. It is declarative and idempotent in the same way the manifests are,
and — the part that matters — **the playbook is the same artefact wherever it
runs**: a laptop, a CI runner, or an AAP Job Template with a managed inventory
and credentials.

The honest framing, and the answer to give if asked:

> *"Deployment is an Ansible playbook. In production it would run as an AAP Job
> Template. I ran it from CI because the sandbox quota would not fit both AAP
> and the autoscaling demonstration — and the demonstration was the point."*

Sizing a constraint and choosing beats collecting tools.

---

## 3. What Phase 4 leaves for this phase to consume

| Thing | Value |
|---|---|
| Images | `ghcr.io/marwanbukhori/{booking-service,queue-gate}` |
| Tag format | **full 40-character commit SHA** (`${{ github.sha }}`) |
| Published on | pushes to `main`, and `workflow_dispatch` |
| Manifests | `deploy/base` + `deploy/overlays/sandbox`, applied with `oc apply -k` |
| Cluster | `api.rm3.7wse.p1.openshiftapps.com`, project `marwanbukhori-dev` |
| Public Route | `queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com` |

**Note the tag mismatch, because it will bite otherwise.** CI publishes the full
SHA; `deploy/scripts/build-push.sh` uses the 7-character short form; the overlay
currently pins whichever was last set by hand. The playbook must take the tag as
an explicit parameter and never derive it — deriving it is how a deploy quietly
targets an image that does not exist.

**Phase 4 is complete.** Publishing was blocked by a package-permission problem
(user-owned packages with no repository link, plus an Actions-access row left at
the default `Read` role); both are fixed, and run `33831363387` published both
images and verified them `linux/amd64`. So this phase has a real supply of
CI-built tags to consume.

---

## 4. Architecture

```
   push to main
        │
        ▼
   ci.yml  ──── tests ──── build ──── push ghcr:<sha>
        │
        │ workflow_run: completed && success
        ▼
   cd.yml
        │
        ▼
   ansible-playbook deploy.yml -e image_tag=<sha>
        │
        ├── 1. oc login with a service-account token
        ├── 2. set the image tag in the overlay
        ├── 3. apply the manifests            kubernetes.core.k8s
        ├── 4. wait for both rollouts
        ├── 5. smoke test the public Route
        └── 6. on failure → roll back to the previous tag, re-verify, fail loudly
```

### Two workflows, not one

CI answers *"is this commit good?"*; CD answers *"is it live?"*. Keeping them
apart is what makes a rollback possible **without a rebuild** — the deploy
workflow can be re-run against any previously published tag, which is precisely
what you want at 21:05 on a demo night.

It also means a deployment failure does not mark the build red, which would be a
lie about the code.

---

## 5. The playbook

```
deploy/ansible/
├── deploy.yml            the play
├── inventory.ini         localhost, connection=local
└── roles/
    └── rembayung/
        ├── tasks/main.yml
        └── defaults/main.yml
```

A role rather than a flat playbook — not for reuse, but because a role is the
unit an AAP Job Template targets, so the structure matches the production story
the design tells.

### Variables

| Variable | Source | Notes |
|---|---|---|
| `image_tag` | **required, no default** | Fails fast if absent. Never derived. |
| `namespace` | `marwanbukhori-dev` | Overridable for a renewed sandbox |
| `route_host` | looked up from the cluster | Not hardcoded — it changes on renewal |
| `rollback_tag` | discovered before applying | Read from the running Deployment |
| `smoke_retries` | 10, 6s apart | Route admission is not instant |

`rollback_tag` is **read from the live Deployment, not from git**. What is
running is the truth; what the repository last recorded may not be.

**And it is one value per Deployment, not one per cluster.** Checked while
writing this: `booking-service` is on `66393c1` and `queue-gate` on `270288f` —
they drifted apart because Phase 3 deployed them separately by hand. A playbook
that captured a single `rollback_tag` would roll one service back to the other's
tag, which is a worse outcome than the failure it was reverting. So the discovery
step reads both, and the rollback restores each to its own.

CI publishes both services at the same commit SHA, so after the first CD run the
two converge and stay converged. The per-Deployment shape still matters, because
the first rollback the playbook ever performs is the one that happens from
today's drifted state.

### Idempotency, and where it stops

`kubernetes.core.k8s` is declarative, so re-running with the same tag is a
no-op — which is the property that makes a retry safe.

The smoke test is **not** idempotent: it books a seat, and seats are finite.
Design consequence: **the smoke test must not consume inventory.** It exercises
`POST /queue` and `GET /queue/{token}` — proving the gate, Redis and the Route —
and asserts that `booking-service` is Ready via its readiness probe, which
already includes the database check. It does not book.

That is a deliberate weakening, and worth stating: it means a deploy can pass
its smoke test while booking is broken. The mitigation is that readiness already
covers datasource health, so the failure mode it misses is narrow — a bug in
booking logic rather than in wiring. Consuming a seat on every deploy would be
worse.

---

## 6. Rollback

The part that makes this delivery rather than a fancy `oc apply`.

```
before applying:   rollback_tag ← image tag currently on the Deployment
apply new tag
wait for rollout   (timeout 300s)
smoke test         (10 attempts, 6s apart)

on failure of either:
   set image tag ← rollback_tag
   apply
   wait for rollout
   smoke test again
   fail the play loudly, naming both tags
```

Three properties worth being explicit about:

**Rollback is a redeploy, not `oc rollout undo`.** `undo` reverts to the previous
ReplicaSet, which is state the cluster happens to hold and which a pruned
history can lose. Re-applying a known tag is explicit, reproducible from the
repository, and works even on a cluster that has forgotten.

**A failed rollback must fail loudly**, not silently leave a half-deployed
system. If the rollback's own smoke test fails, the play ends non-zero with both
tags named, because at that point a human is needed.

**Rollback needs an immutable tag to name.** That is why Phase 3 chose commit
SHAs over `latest` — the decision was made two phases early precisely for this.

---

## 7. Authentication

The playbook needs cluster credentials. Three options, in the order they were
considered:

| Option | Verdict |
|---|---|
| The developer's `oc` token | **Rejected.** Sandbox tokens are short-lived — one expired mid-session during Phase 3 — and it ties deployment to one person. |
| A ServiceAccount token with a Role scoped to the namespace | **Chosen.** Long-lived, least-privilege, revocable, tied to no human. |
| `cluster-admin` | Rejected. The playbook needs to patch Deployments in one namespace, nothing more. |

The ServiceAccount gets a Role permitting `get`, `list`, `patch` and `apply` on
Deployments, Services, Routes, ConfigMaps and HPAs in `marwanbukhori-dev` — and
explicitly **not** Secrets. The wallet and database credentials are created once
by a human and never touched by CD.

Its token goes into a GitHub repository secret. **That is the only secret this
project stores**, and worth contrasting with the registry: ghcr.io needed none
because `GITHUB_TOKEN` covers it.

**The sandbox expires**, roughly 2026-10-01. On renewal the cluster URL, the
namespace and the ServiceAccount all change, so the secret and the overlay both
need updating. That belongs in the runbook, not in a comment nobody reads.

---

## 8. Testing a deployment pipeline

The awkward part of this phase: the deliverable is a side effect on a live
cluster, and there is exactly one cluster.

| Layer | How |
|---|---|
| Playbook syntax | `ansible-playbook --syntax-check` |
| Task logic without a cluster | `--check` against the real cluster (server-side dry run) |
| A real deploy | Deploy the tag that is already running — a genuine no-op that still exercises every task |
| Rollback | **Deliberately deploy a broken tag** and confirm it reverts |

That last row is the only honest way to test a rollback, and it is worth doing
once, on purpose, before it is needed unrehearsed. A tag that does not exist in
the registry gives `ImagePullBackOff`, the rollout wait times out, and the
rollback path runs — a clean, reversible failure injection.

---

## 9. Out of scope

- **Promotion between environments.** The sandbox grants one namespace; a
  staging tier would be fiction.
- **Approval gates.** A single-maintainer demo has nobody to approve.
- **Blue/green or canary.** Two replicas behind one Service, and the interesting
  concurrency problem is in the database, not the rollout strategy.
- **Deploying the database.** Oracle is managed and external, by design.
- **Secret rotation.** Created once by a human; out of scope, and named as a
  weakness rather than pretended away.

---

## 10. Known weaknesses

- **The smoke test does not book.** It cannot, without consuming finite
  inventory. So a deploy can pass while booking is broken; readiness covering
  datasource health narrows but does not close that gap.
- **One environment, deployed straight to.** No staging means the first place a
  bad deploy is seen is the only place that matters. Rollback is the mitigation,
  which is why it is built rather than assumed.
- **The ServiceAccount token is long-lived.** Revocable and namespace-scoped, but
  it does not expire. A short-lived OIDC-federated credential is the better
  answer and is out of scope for a sandbox.
- **Rollback assumes the previous image still exists.** Registry retention could
  in principle remove it. ghcr.io does not expire images by default, so this is
  theoretical here.
- **The playbook is not run by AAP.** It is written to be, and is not, for the
  quota reason in §2. The story is honest but the artefact is untested in that
  environment.

---

## 11. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| CD tool | Ansible, replacing Jenkins | Red Hat tooling native to OpenShift; needs no server, so costs none of the 3-core quota the demo requires |
| AAP | Named as the production home, not run | AAP consumed 1950m of 3000m; it and the scale demo cannot coexist |
| Trigger | `workflow_run` on CI success | Keeps "is it good?" separate from "is it live?", so rollback needs no rebuild |
| Structure | A role, not a flat playbook | A role is the unit an AAP Job Template targets |
| `image_tag` | Required, never derived | CI publishes full SHAs and local builds short ones; deriving is how a deploy targets a nonexistent image |
| `rollback_tag` | Read from the live Deployment | What is running is the truth; the repository may disagree |
| Rollback method | Re-apply a known tag | Explicit and reproducible, unlike `oc rollout undo` which depends on retained ReplicaSets |
| Smoke test | Queue path only, no booking | Booking consumes finite inventory; readiness already covers datasource health |
| Credentials | Namespace-scoped ServiceAccount, no Secrets access | Least privilege; the wallet is human-managed and never touched by CD |
| Rollback testing | Deploy a deliberately broken tag | The only honest way to prove a rollback works before it is needed |

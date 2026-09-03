# Continuous Integration — Design (Phase 4)

**Date:** 2026-09-03
**Status:** Approved, ready for implementation planning
**Phase:** 4 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Previous phase:** [`2026-09-03-openshift-deployment-design.md`](2026-09-03-openshift-deployment-design.md)

---

## 1. Purpose

Phase 3 put both services on OpenShift, but every image was built and pushed by
hand from a laptop. Phase 4 makes that automatic and reproducible: a push runs
the full test suite against real Oracle and real Redis, builds both images, and
publishes them to `ghcr.io` tagged with the commit that produced them.

Phase 5 then deploys them with Ansible. The split is deliberate — CI answers
*"is this commit good?"*, CD answers *"is it live?"* — and keeping them apart is
what makes a rollback possible without a rebuild.

---

## 2. The Jenkins decision, revised

The parent spec specified Jenkins for CD, reasoning that CD needs a server with
network reach into the cluster. **That has been revised: Ansible replaces
Jenkins**, and the parent spec's decision log records it.

Three reasons, in order of weight:

**The quota makes AAP impossible here.** The sandbox arrived with an Ansible
Automation Platform stack consuming **1950m of the 3000m** CPU quota. The
autoscaling demonstration needs 1900m. AAP and the demo cannot coexist, and the
demo is the centrepiece. The same arithmetic rules out in-cluster Jenkins
(~500m), which would leave too little headroom to scale to ten replicas.

**The original rationale evaporates.** Jenkins was chosen because CD needed
somewhere with cluster reach. A GitHub Actions runner authenticated with an `oc`
token has exactly that, so the server is unnecessary.

**Ansible is the better fit against OpenShift.** `kubernetes.core.k8s` is Red
Hat's own tooling for this, it is declarative and idempotent in the same way the
manifests are, and the resulting playbook is the *same artefact* whether it runs
from a laptop, from CI, or as an AAP Job Template.

That last point is the honest interview answer:

> *"Deployment is an Ansible playbook. In production it would run as an AAP Job
> Template with an inventory and credentials managed centrally; I ran it from CI
> because the sandbox quota would not fit both AAP and the autoscaling
> demonstration I wanted to show."*

Sizing a constraint and making a defensible call is a better answer than having
the tool running.

---

## 3. What CI must actually prove

Not "the code compiles". The suite is 73 tests across two modules, and the ones
that matter exercise real infrastructure:

| Suite | Tests | Needs |
|---|---|---|
| `booking-service` | 35 | Oracle 23ai via Testcontainers |
| `queue-gate` | 38 | Redis 7 via Testcontainers, WireMock |

Among them, `ConcurrencyInvariantTest` runs **400 threads against one slot row**
and asserts `seats_taken` never exceeds capacity. **That test running in CI is
the point of CI here** — it is the project's central claim, and a pipeline that
skipped it would be decorative.

So CI runs the real suites against real containers. No mocked database, no
`-DskipTests` shortcut on the path that gates a merge.

---

## 4. Architecture

```
  git push / pull request
           │
           ▼
  ┌─────────────────────────────────────────┐
  │  ci.yml            ubuntu-latest        │
  │                                          │
  │  1. checkout, JDK 25, cache ~/.m2        │
  │  2. mvn verify  booking-service          │  Oracle in Docker
  │  3. mvn verify  queue-gate               │  Redis in Docker
  │  4. build both images  (natively amd64)  │
  │  5. push to ghcr.io:<sha>                │
  └───────────────────┬─────────────────────┘
                      │ on success, main only
                      ▼
              deploy.yml  ← Phase 5, Ansible
```

### The architecture problem disappears here

Phase 3 hit a real obstacle: the development machine is arm64, the cluster is
x86_64, and building an amd64 image on the Mac meant running an x86 JVM under
QEMU, where the Maven wrapper's tar extraction fails with `ENOSYS`. The fix was
to build the JAR natively and copy it into an amd64 base image.

**GitHub Actions runners are x86_64**, so in CI there is no emulation at all —
`docker build` produces amd64 layers natively. The workaround is still correct
and still used (it is faster, and it keeps the Dockerfiles identical in both
places), but the constraint that forced it is absent here.

This is worth stating plainly because it is the parent spec's stated benefit of
building in CI, now confirmed rather than assumed.

---

## 5. Workflow design

### Triggers

| Event | Runs | Publishes images |
|---|---|---|
| Pull request | tests only | no |
| Push to `main` | tests + build + push | yes |
| Push to any other branch | tests only | no |
| `workflow_dispatch` | tests + build + push | yes |

Publishing only from `main` and manual runs keeps the registry free of images
nothing will ever deploy. `workflow_dispatch` exists so a demo can be rehearsed
without a commit.

### Jobs

**One job, not two.** A split (`test` then `build`) would need the second job to
re-checkout and re-resolve dependencies, and there is nothing to parallelise —
the build depends on the tests passing. One job keeps the wall clock down and
the log readable.

The two modules do run their suites sequentially in that job. They could run in
parallel as a matrix, but each starts its own database container and the runner
has finite memory; sequential is more predictable and the difference is a couple
of minutes.

### Authentication

`ghcr.io` accepts the workflow's built-in `GITHUB_TOKEN` with:

```yaml
permissions:
  contents: read
  packages: write
```

**No registry secret is required**, which was the deciding factor in choosing
`ghcr.io` over Quay.io in Phase 3. Nothing to rotate, nothing to leak, nothing
that expires at an inconvenient moment.

### Caching

`actions/setup-java` with `cache: maven` restores `~/.m2/repository` keyed on the
POMs. First run resolves everything (slow); later runs skip it.

Container images are **not** cached. Testcontainers pulls Oracle (~1.5GB) and
Redis on every run. Docker layer caching for these is possible but adds
complexity for a saving that mostly disappears against the pull being warm on
GitHub's infrastructure.

---

## 6. Risks, and what to do about them

Stated plainly because two of these could make the pipeline unusable rather than
merely slow.

**Oracle Testcontainers on a hosted runner.** `gvenzl/oracle-free:23-slim-faststart`
takes 60–90 seconds to become healthy and wants ~2GB of memory. Standard
`ubuntu-latest` runners have 4 vCPU and 16GB, which should be sufficient, but
this is the single biggest unknown in the phase and is proven first.

*If it fails:* raise the Testcontainers startup timeout before anything else —
the container is likely slower, not broken. Only if that fails does the fallback
question arise, and it is a real decision rather than a detail: running
`booking-service`'s suite against a service container instead, or accepting a
longer timeout. **Substituting H2 is not an option** — `SELECT ... FOR UPDATE`
semantics are the thing under test.

**Testcontainers reuse must be off in CI.** Locally, `withReuse(true)` plus
`~/.testcontainers.properties` keeps Oracle alive between runs and makes the
suite take 7 seconds instead of 90. On a fresh runner there is nothing to reuse,
and reuse leaves containers behind — which is meaningless on an ephemeral VM but
noisy. Reuse is opt-in on both sides and CI simply does not opt in, so this
needs no code change. It is recorded because someone will wonder why CI is
slower than their laptop.

**Wall clock.** Expect roughly 8–12 minutes: dependency resolution 1–2, Oracle
startup 1–2, `booking-service` suite 2–3, `queue-gate` suite 1–2, image build
and push 2–3. That is acceptable for a portfolio piece and would be worth
optimising in a real project.

**Concurrency.** Two pushes in quick succession would race to publish the same
tag. A `concurrency` group keyed on the ref, with `cancel-in-progress` for
non-`main` branches, prevents wasted runners without cancelling a `main` build
midway.

---

## 7. What CI does not do

- **It does not deploy.** That is Phase 5, by design. A CI job that deploys
  cannot roll back without rebuilding.
- **It does not tag `latest`.** Git short SHA only — Phase 5's rollback depends
  on immutable tags, which is why the decision was made in Phase 3 rather than
  here.
- **It does not run k6.** The load test needs a cluster and a seeded slot;
  running it on every push would be slow and would need a live environment.
- **It does not scan images or check licences.** Genuinely valuable, and out of
  scope for a demonstration.
- **It does not sign images.** Sigstore/cosign would be the right answer for a
  real supply chain; noted as future work rather than built.

---

## 8. Known weaknesses

- **No branch protection.** Nothing enforces that CI passed before a merge; the
  workflow reports status but the repository does not require it. One setting,
  and worth turning on before showing the repo.
- **Images are tagged, not digest-pinned.** A tag is mutable, so in principle a
  pushed tag could be overwritten. Digest pinning is stronger and was deferred in
  Phase 3 because it complicates the rollback story. CI could emit both.
- **The suite is the gate, and it needs real Oracle.** That makes CI slower and
  more fragile than a mocked pipeline would be. It is the right trade for this
  project — the invariant is the product — but it is a trade.
- **No test result publishing.** Failures must be read from the log rather than
  a summary. A JUnit reporter action would fix it cheaply.
- **Secrets exist but are not exercised.** CI never touches the Oracle wallet or
  the database credentials; the tests use throwaway containers. Good for safety,
  but it means the wallet path is only ever proven at deploy time.

---

## 9. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| CD tool | **Ansible, replacing Jenkins** | Red Hat tooling natural against OpenShift; no server, so no quota cost; the same playbook runs from CI or as an AAP Job Template |
| AAP | Named, not run | AAP consumed 1950m of 3000m; it and the 2→10 scale demo cannot coexist |
| Registry auth | Built-in `GITHUB_TOKEN` | No secret to store, rotate or leak — the reason `ghcr.io` was chosen in Phase 3 |
| Job structure | One job | The build depends on the tests; splitting adds a re-checkout for nothing |
| Module suites | Sequential | Each starts its own database container; predictable beats marginally faster |
| Publish on | `main` and manual only | Keeps the registry free of images nothing will deploy |
| Real Oracle in CI | Yes | `ConcurrencyInvariantTest` is the project's central claim; a pipeline skipping it would be decorative |
| Testcontainers reuse | Off in CI | Nothing to reuse on an ephemeral runner; opt-in on both sides, so no code change |
| Image tags | Git short SHA | Set in Phase 3; rollback needs immutable tags |

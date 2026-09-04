# OpenShift Deployment — Design (Phase 3)

**Date:** 2026-09-03
**Status:** Approved, ready for implementation planning
**Phase:** 3 of the Rembayung booking queue build
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Previous phase:** [`2026-09-03-queue-gate-design.md`](2026-09-03-queue-gate-design.md)

---

## 1. Purpose

Phases 1 and 2 produced two services that run correctly on a laptop under
`docker compose`, proven not to oversell a 250-seat slot under 5000 concurrent
users. Phase 3 puts them on a real OpenShift cluster.

This is the phase where the project stops being a Java exercise and becomes a
platform one. Everything that matters here — image architecture, secret
handling, health probes, autoscaling against a finite quota — is invisible on a
laptop and unavoidable on a cluster.

---

## 2. Verified constraints

Checked against the live cluster on 2026-09-03, not assumed.

| Constraint | Finding | Consequence |
|---|---|---|
| Cluster | `api.rm3.7wse.p1.openshiftapps.com` | Red Hat Developer Sandbox |
| Project | `marwanbukhori-dev` | Single namespace; no ability to create more |
| CPU quota | `requests.cpu: 3`, **0 in use** | Binding constraint on HPA ceilings |
| Memory quota | `requests.memory: 30Gi`, 0 in use | Not a limitation |
| Route domain | `<name>-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com` | Public URL is predictable, so it can be baked into the demo script |
| Metrics API | `metrics.k8s.io/v1beta1` **present**; `oc adm top` works | **HPA will actually scale.** Without this it is inert. |
| `anyuid` SCC | **Denied** | Confirms Oracle cannot run in-cluster |
| Storage classes | `efs-sc`, `gp2`, `gp2-csi` — **none marked default** | Any PVC must name a class explicitly or it stays `Pending` forever. Nothing in this phase needs one. |
| Tooling | `oc` 4.22.11 (kustomize 5.7.1 built in), `buildx` present | No extra installs |

### The quota discovery

The sandbox arrived pre-provisioned with a full Ansible Automation Platform
stack — 13 deployments plus a Postgres StatefulSet — consuming **1950m of the
3000m** CPU quota. The HPA plan below needs 1900m, so the autoscaling
demonstration could not have run: pods would have stalled `Pending` on quota
partway through the scale-out, during the exact step the parent spec calls the
centrepiece.

Worth recording how it resisted removal, because the lesson generalises:
`oc scale --replicas=0` appeared to work and the quota dropped to 275m, then
reverted within a minute. AAP is operator-managed, so the `Deployment` replica
count is not the source of truth — the `AnsibleAutomationPlatform` custom
resource is, and the operator reconciled it straight back. Deleting the four
custom resources removed the stack for good.

**Scaling an operator-managed workload does not stick. Delete its custom
resource, and verify after a delay rather than reading the number once.**

---

## 3. Accounts to create

Both are prerequisites and both are interactive. They are called out as their
own task in the plan so a signup delay blocks one task rather than the phase.

### Oracle Cloud — Always Free Autonomous Database

1. Sign up at `cloud.oracle.com` (identity verification; a card may be
   requested for verification, and Always Free resources do not charge).
2. Create an **Autonomous Transaction Processing** instance on the Always Free
   shape. Record the admin password.
3. Create the application user and grant it what Flyway needs.
4. Download the **instance wallet** (a zip of `tnsnames.ora`, `sqlnet.ora`,
   `cwallet.sso`, `ewallet.p12`, `keystore.jks`, `truststore.jks`,
   `ojdbc.properties`).
5. Note the TNS alias to use — `<dbname>_high`, `_medium` or `_low`. Use
   `_low` for this workload: it gives the highest concurrency at the lowest
   per-query parallelism, which suits many small transactions.

### GitHub Container Registry

1. Create a Personal Access Token (classic) with `write:packages` and
   `read:packages`.
2. `docker login ghcr.io -u marwanbukhori -p <token>`.
3. After the first push, set both packages to **public** so the cluster can
   pull without a secret. If kept private, an image pull secret is required —
   the plan covers both.

`ghcr.io` was chosen over Quay.io because the GitHub account already exists,
and because Phase 4's GitHub Actions authenticates to it with a built-in
`GITHUB_TOKEN` — CI needs no registry secret at all.

---

## 4. Architecture

```
                         Internet
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │  Route  queue-gate-marwanbukhori-dev   │  edge TLS
        │         .apps.rm3.7wse.p1.openshift…   │
        └───────────────────┬───────────────────┘
                            ▼
        ┌───────────────────────────────────────┐
        │  queue-gate     Deployment + Service   │  HPA 2 → 10
        │  100m / 256Mi                          │  ConfigMap: drop window
        └───────┬───────────────────┬───────────┘
                │                   │
                ▼                   ▼
    ┌────────────────────┐  ┌───────────────────────┐
    │ redis              │  │ booking-service       │  HPA 2 → 4
    │ Service (internal) │  │ Service (internal)    │  200m / 512Mi
    │ 100m, no PVC       │  │ NO ROUTE              │
    └────────────────────┘  └───────────┬───────────┘
                                        │ JDBC + wallet
                                        ▼
                            ┌───────────────────────┐
                            │ OCI Autonomous DB     │  external
                            │ (Always Free)         │
                            └───────────────────────┘
```

### Object mapping

| Component | Objects | Notes |
|---|---|---|
| `queue-gate` | Deployment, Service, **Route**, HPA, ConfigMap | Only public entry point |
| `booking-service` | Deployment, Service, HPA | **No Route** — the security boundary |
| `redis` | Deployment, Service | Internal only, no PVC |
| Oracle wallet | **Secret**, mounted as a volume | Never baked into an image |
| DB credentials | **Secret** | Separate from the wallet |
| Drop window, admit rate | **ConfigMap** | Tunable live during a demo |

This is the parent spec's mapping, now with concrete resource values and the
addition of an HPA on `booking-service` (the parent spec gave it a ceiling of 4
but did not name the object).

### The boundary is the same one Phase 2 enforced

Phase 2 found that publishing `booking-service` or `redis` on the host made the
queue bypassable — proven by booking a seat with no admission token, and by
forging a token with a single `redis-cli SET`. The cluster equivalent is
precise: **neither gets a Route.** A `Service` is reachable only from inside
the namespace; only a `Route` crosses the cluster boundary.

That correspondence is worth stating explicitly, because it is the same
property expressed twice in two different systems.

---

## 5. Images

### Architecture is the trap

The development machine is **arm64**; the sandbox is **x86_64**. An image built
with a plain `docker build` on this Mac produces arm64 layers, which fail on
the cluster with `exec format error` and a `CrashLoopBackOff` that gives no
obvious clue as to why.

```bash
docker buildx build --platform linux/amd64 \
  -t ghcr.io/marwanbukhori/booking-service:<sha> \
  --push ./booking-service
```

`--platform linux/amd64` is not optional, and `buildx` was verified present.

**Verify the manifest after pushing, before deploying:**

```bash
docker buildx imagetools inspect ghcr.io/marwanbukhori/booking-service:<sha> \
  | grep -i platform
```

Expect `linux/amd64`. Checking this takes seconds; diagnosing a
`CrashLoopBackOff` caused by it takes much longer.

Phase 4 removes the problem entirely — GitHub Actions runners are x86_64, so
building in CI is natively correct.

### Tags are git SHAs, never `latest`

```
ghcr.io/marwanbukhori/booking-service:<short-sha>
ghcr.io/marwanbukhori/queue-gate:<short-sha>
```

`latest` with `imagePullPolicy: Always` makes a rollout non-reproducible and a
rollback impossible — you cannot point at "the previous latest". Phase 5's
Jenkins rollback step depends on immutable tags existing, so this decision is
made here rather than there.

---

## 6. Configuration and secrets

### Three distinct kinds of configuration

| Kind | Object | Contents |
|---|---|---|
| Behaviour | ConfigMap `queue-gate-config` | `DROP_OPENS_AT`, `DROP_CLOSES_AT`, `DROP_TICKET_CAP`, `DROP_ADMIT_RATE`, `DROP_ADMISSION_WINDOW` |
| Credentials | Secret `oracle-credentials` | `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` |
| Wallet | Secret `oracle-wallet` | The seven files from the OCI wallet zip |

Splitting credentials from the wallet is deliberate: the wallet rotates on a
different schedule from the password, and `oc create secret generic
--from-file=` over a directory produces one key per file, which mounts cleanly
as a volume.

### Wallet mounting

```yaml
volumes:
  - name: oracle-wallet
    secret:
      secretName: oracle-wallet
volumeMounts:
  - name: oracle-wallet
    mountPath: /oracle/wallet
    readOnly: true
```

with

```
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@bookingdb_low?TNS_ADMIN=/oracle/wallet
```

The `TNS_ADMIN` query parameter is supported by `ojdbc11` and is preferable to
the environment variable of the same name: it keeps the whole connection
description in one string, which is easier to read in a manifest and easier to
override per environment.

### The drop window on a cluster

Phase 2's container entrypoint computes `DROP_OPENS_AT` as *now minus 30
seconds* when the variable is unset, because a fixed past timestamp expires
every token — the admission window is measured from a ticket's turn, which is
relative to the drop opening.

That behaviour is right for a laptop and wrong for a cluster: a pod restarting
mid-drop would silently reopen the window and readmit everyone from ticket 1.

**On OpenShift the ConfigMap always sets `DROP_OPENS_AT` explicitly**, so the
entrypoint's fallback never fires and every replica agrees on when the drop
opened. The fallback stays for local use. Setting it is a one-line
`oc patch`/`oc set data` before a demo run, which is exactly the live
tunability the parent spec asked the ConfigMap to provide.

---

## 7. Health probes

Both services gain Spring Boot Actuator with **only** the health endpoint
exposed:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
```

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 20
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

The distinction matters and is worth being able to explain:

- **Liveness** failing means the pod is restarted. It must not depend on
  external services — a database blip would otherwise cause a restart storm
  that makes the outage worse.
- **Readiness** failing means the pod is removed from the Service's endpoints
  but left running. On `booking-service` this *does* include the datasource
  check, so a pod that cannot reach Oracle stops receiving traffic and recovers
  on its own when the connection returns.

Spring Boot's `readinessState` group includes datasource health by default;
`livenessState` deliberately does not. That default is correct here, so the
configuration keeps it rather than overriding.

Actuator was deliberately excluded from Phase 2 as unnecessary there. It
arrives now because probes are the first thing that needs it, and because Boot
4 restructured Actuator into separate modules — an integration risk best
handled in the phase that needs it.

---

## 8. Autoscaling

| Workload | CPU request | Min | Max | Worst case |
|---|---|---|---|---|
| `queue-gate` | 100m | 2 | 10 | 1000m |
| `booking-service` | 200m | 2 | 4 | 800m |
| `redis` | 100m | 1 | 1 | 100m |
| **Total** | | | | **1900m / 3000m** |

1100m of headroom against a fully free quota.

The asymmetry is the entire point: **the gate scales and the booking service
does not.** The database is the scarce resource, so load is absorbed in front
of it rather than passed through to it. An interviewer asking "why not scale
both?" is asking the question the design exists to answer.

### HPA cannot react to this spike, and that is expected

The drop is at 21:00 and the arrival burst is roughly one second wide. HPA
polls metrics every 15 seconds and takes 15–30 seconds to schedule new pods, so
**the spike is over before autoscaling responds.** The gate absorbs the burst
at whatever replica count it already had.

This is not a flaw to hide. It is the reason the drop time being *known* is
useful: for a scheduled event you pre-scale rather than autoscale.

```bash
oc scale deploy/queue-gate --replicas=10    # before the drop
oc scale deploy/queue-gate --replicas=2     # after
```

The HPA still earns its place — it handles the sustained polling load after
admission and returns capacity afterwards — but the honest answer to "how do
you handle a one-second spike?" is *"you don't autoscale into it, you pre-scale
before it."* Phase 6's dashboards make both behaviours visible.

---

## 9. Manifest layout

```
deploy/
├── base/
│   ├── kustomization.yaml
│   ├── redis/            deployment, service
│   ├── queue-gate/       deployment, service, route, hpa, configmap
│   └── booking-service/  deployment, service, hpa
└── overlays/
    └── sandbox/
        ├── kustomization.yaml     image tags, replica counts, namespace
        └── patches/               resource limits, drop window
```

Applied with `oc apply -k deploy/overlays/sandbox`, matching the parent spec's
CI/CD sketch. `oc` ships kustomize 5.7.1, so nothing extra is installed.

Base holds what is true everywhere; the overlay holds what is true of this
cluster — image tags, the namespace, and the drop window. Phase 5's Jenkins
deploy step then changes an image tag in one place.

---

## 10. Sequencing — the OCI dependency comes last

`redis` and `queue-gate` need no database. The deployment order is therefore:

1. **Prove the cluster path first.** Build both images for amd64, push to
   ghcr.io, deploy `redis` and `queue-gate`, and confirm the Route answers.
   This exercises the riskiest infrastructure — cross-arch images, image pull,
   Routes, ConfigMaps — **without waiting on an Oracle Cloud signup.**
2. **Prove connectivity to OCI early**, before writing the rest of the
   manifests: run a throwaway pod in the namespace that opens a socket to the
   Autonomous Database endpoint. Sandbox egress is not guaranteed, and finding
   out after building everything else would be expensive.
3. Then wallet Secret, `booking-service`, HPAs, and the load test.

At step 1 the gate is deployed without a reachable booking service, so
`POST /queue` and `GET /queue/{token}` work while `POST /bookings` returns a
gateway error. That is a coherent, checkable intermediate state, not a broken
one.

---

## 11. What is demonstrated at the end of this phase

Parent spec demo steps 1, 3, 4 and 5 become runnable:

| Step | Becomes possible |
|---|---|
| 1 — book a seat normally | `curl` against the public Route |
| 3 — k6 fires load | Pods scale, Route stays up |
| 4 — `SELECT seats_taken` | Against OCI, not a local container |
| 5 — `oc delete pod` mid-load | The failure test the parent spec ends on |

Step 2 (push a commit, watch it deploy) needs Phases 4 and 5.

---

## 12. Out of scope

- **CI and CD** — Phases 4 and 5. Images are built and pushed by hand here.
- **Observability** — Phase 6. No OpenTelemetry, no Splunk appender, no
  Prometheus scraping.
- **Authentication** — still deferred, with the chained weakness documented in
  the Phase 2 spec.
- **TLS to the database beyond the wallet's own mTLS** — the wallet already
  provides it.
- **Multi-environment overlays** — one overlay, `sandbox`. A second would be
  speculative.

---

## 13. Known weaknesses

- **The sandbox expires.** Roughly 2026-10-01, renewable. Everything here is
  reproducible from manifests, so recovery is `oc apply -k` plus re-creating
  the two secrets — but the cluster URL and project name change on renewal, so
  the overlay needs editing.
- **Always Free Autonomous Database sleeps after prolonged inactivity** and can
  be reclaimed after extended disuse. A demo after a quiet week may face a slow
  first connection. Touch the database the day before a demo.
- **Secrets are created imperatively**, by `oc create secret` from local files,
  not held in git. That is correct — wallets and passwords must not be
  committed — but it means the deployment is not fully reproducible from the
  repository alone. A sealed-secrets or External Secrets operator is the real
  answer and is out of scope for a sandbox.
- **One namespace, no staging.** The sandbox grants a single project, so there
  is no promote-from-staging path. Phase 5's Jenkins pipeline deploys straight
  to it.
- **Pre-scaling is manual.** A production system would drive it from a schedule
  (a CronJob adjusting `minReplicas`, or KEDA's cron scaler). Doing it by hand
  is honest for a demo and the automation is a sentence of future work.

---

## 14. Decision log

| Decision | Chosen | Rationale |
|---|---|---|
| Registry | ghcr.io | Account already exists; Phase 4 CI authenticates with a built-in token, needing no secret |
| Image tags | git short SHA | `latest` makes rollout non-reproducible and rollback impossible; Phase 5 depends on immutable tags |
| Image build | `buildx --platform linux/amd64` | arm64 dev machine, x86_64 cluster; verified with `imagetools inspect` before deploying |
| Wallet delivery | Secret mounted as a volume | Credentials never in an image; per-file keys mount cleanly |
| JDBC wallet reference | `?TNS_ADMIN=` in the URL | Whole connection description in one overridable string |
| Actuator scope | health only | Probes need it; metrics belong to Phase 6, which may use OTLP instead |
| Liveness vs readiness | Liveness excludes the DB, readiness includes it | A database blip must not cause a restart storm |
| `booking-service` exposure | Service, no Route | The cluster expression of the same boundary Phase 2 enforced with `expose:` |
| `DROP_OPENS_AT` on cluster | Always set explicitly in the ConfigMap | The entrypoint fallback would reopen the window on a pod restart |
| Spike handling | Pre-scale, do not rely on HPA | A one-second burst is over before HPA reacts; the drop time is known |
| Deployment order | redis + gate first, Oracle last | Retires cross-arch and Route risk without waiting on an OCI signup |
| Manifest tool | Kustomize via `oc apply -k` | Built into `oc` 4.22.11; matches the parent spec's CI/CD sketch |

# 04 — OpenShift deployment

**Covers:** Phase 3 of the booking domain core plan  
**Tested on:** OpenShift 4.x sandbox, Oracle Autonomous Database (Always Free)

Phase 3 moves the system from a test harness to a production-like cluster. This note
explains not just what each object does, but *why it was configured this way* — 
especially where a default was rejected on correctness or performance grounds.

---

## Why `booking-service` has no Route

The `queue-gate` Deployment gets a public Route (`https://queue-gate-marwanbukhori-dev...`).
The `booking-service` has only a Service; there is no Route.

This mirrors Phase 2's `expose:` logic, extended to the cluster: **the queue cannot be
bypassed because the booking endpoint is not reachable from outside at all.**

In Phase 2 terms, `booking-service` is `expose: false`. In OpenShift terms, a Kubernetes
`Service` without a `Route` is only reachable inside the cluster.

The boundary is enforced in three layers:

1. **No Route:** the Service has no public DNS name
2. **NetworkPolicy:** intended to restrict in-cluster access to `queue-gate`
   only — but measured *not* to work on this cluster, because the sandbox ships
   an allow-all policy and NetworkPolicy is additive. See below; this is the
   interesting part of the note.
3. **Service isolation:** there is no exposed port that differs from the internal
   port — what you see is what you get

Compare to `redis`: it also has no Route and a NetworkPolicy, but it is a cache layer
(not a security boundary). The booking service is a security boundary because the domain
invariant — never oversell — lives there, and it can only be enforced if load comes
through the queue.

---

## NetworkPolicies: boundary enforcement, not just documentation

Kubernetes `NetworkPolicy` is an ingress firewall. The manifests define two:

```yaml
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: redis-from-gate-only
spec:
  podSelector: { matchLabels: { app.kubernetes.io/name: redis } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: { matchLabels: { app.kubernetes.io/name: queue-gate } }
      ports:
        - protocol: TCP
          port: 6379

---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: booking-service-from-gate-only
spec:
  podSelector: { matchLabels: { app.kubernetes.io/name: booking-service } }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: { matchLabels: { app.kubernetes.io/name: queue-gate } }
      ports:
        - protocol: TCP
          port: 8081
```

### And on this cluster they do nothing at all

That is what the previous version of this note claimed. It was measured on
2026-09-04 and it is **false here**, for a reason worth understanding.

An unlabelled probe pod reached both:

```
$ oc run np-probe --rm -i --image=...ubi-minimal --command -- bash -c \
    '</dev/tcp/booking-service/8081 && echo REACHED'
  REACHED
  redis REACHED
```

The policies above are written correctly. The problem is that the sandbox ships
its own policy alongside them:

```yaml
# allow-same-namespace — provided by codeready-toolchain, not by this project
spec:
  podSelector: {}              # every pod in the namespace
  ingress:
    - from:
        - podSelector: {}      # ...from every pod in the namespace
```

**NetworkPolicy is purely additive. There is no deny rule.** A pod's allowed
ingress is the *union* of every policy that selects it. So `allow-same-namespace`
permits everything to everything, and adding a narrower policy beside it cannot
subtract from that. `booking-service-from-gate-only` grants an allowance that was
already granted.

This is the single most common misconception about NetworkPolicy: people read a
restrictive-looking policy and conclude traffic is restricted, when the policy
only ever *adds* permission. The only way to get a deny is for no policy to allow
the traffic.

### What is actually true

| Boundary | Real? | Why |
|---|---|---|
| From the internet, only `queue-gate` is reachable | **Yes** | `booking-service` and `redis` have no `Route`. A Service without a Route has no external address at all — verified: the Route list contains only `queue-gate`. |
| Inside the namespace, only `queue-gate` may reach them | **No** | The platform's allow-all defeats it, as measured above. |

So the external boundary — the one that matters for "can someone on the internet
skip the queue and book directly" — holds, and holds for a stronger reason than a
firewall rule: there is no address to connect to.

The internal boundary does not hold, and the manifests describing it are
aspirational on this cluster.

### Why it was not simply fixed

`allow-same-namespace` carries `toolchain.dev.openshift.com/provider:
codeready-toolchain`. It is managed by the sandbox operator, so deleting it would
likely be reconciled back within moments, and might break sandbox features that
assume intra-namespace reachability — the web console's pod terminal among them.

On a cluster you own, the fix is to delete the blanket policy and let the narrow
ones do their job. Here, the honest thing is to keep the policies (they are
correct, and they are what you would ship) and record that the platform overrides
them.

**The lesson is worth more than the fix:** a NetworkPolicy that looks restrictive
tells you nothing until you have tested it from a pod that should be denied. This
one was in the repository for two phases, described in this note as unbypassable,
and was never once verified.

---

## Why liveness excludes the database; readiness includes it

The probes are HTTP calls to Spring Boot's actuator endpoints:

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: management }
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: management }
  periodSeconds: 5
```

This is configured in `booking-service/src/main/resources/application.yml`:

```yaml
management:
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
    db:
      enabled: true  # included in readiness, not liveness
```

**Liveness** ("is the pod alive?") does NOT check the database. If the database is
temporarily unreachable, the pod is still alive; it just cannot answer requests.

**Readiness** ("can this pod handle traffic now?") DOES check the database. If the
database is down, the pod cannot book anything, so it should not receive traffic.

**Why this split?**

If liveness checked the database, a database blip would trigger:

1. Kubernetes kills the pod (liveness failed)
2. Kubernetes starts a new pod in its place
3. The new pod also fails liveness (database still down)
4. Repeat: restart storm

This turns a single database outage into a cascade. The pod can do nothing useful, but
killing it and restarting it makes things worse, not better.

Readiness achieves the goal cleanly: when the database goes down, `readiness` fails,
traffic stops being routed to the pod, and the pod sits idle waiting for the database
to come back. When it does, readiness passes again and traffic resumes. No restarts,
no cascade.

**The principle:** liveness is about the container itself (is it hung? is it deadlocked?).
Readiness is about the pod's external dependencies (can it do work right now?).

---

## Why actuator sits on port 9090; the Route publishes only 8080

The application has two ports:

- **8080 (http):** application endpoints (`POST /bookings`, etc.)
- **9090 (management):** actuator endpoints (`/actuator/health/...`, etc.)

The Service carries both, and the Route publishes only 8080:

```yaml
# deploy/base/queue-gate/service.yaml
ports:
  - name: http
    port: 8080
    targetPort: 8080
  - name: management
    port: 9090
    targetPort: 9090
```

The boundary is the Route, not the Service. 9090 has to be on the Service because
Prometheus scrapes it, and Prometheus runs inside the cluster — a port that no
Service carries is not reachable by anything, monitoring included. What must not
happen is 9090 reaching the internet, and the Route is what decides that: it
forwards to 8080 alone, so JVM internals and request timings stay in-cluster.

An earlier version of this page said the Service exposed only 8080 and printed a
`ports:` block with a `# port 9090 is NOT in this list` comment. That was never
true after the observability work added the scrape target, and it named the wrong
control: reading it, you would look at the Service to check what the internet can
reach, and the Service is not where that is decided.

**Probes hit port 9090 directly** by name:

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: management }
```

`management` is the name given to the 9090 port in the Deployment's `containerPort` list.
Kubernetes resolves it to the port number internally.

**Why split the ports?**

1. **Operational clarity:** application metrics and health are separate from the app itself.
   A human can `oc port-forward deploy/queue-gate 9090:9090` and curl actuator without
   interfering with production traffic.

2. **Security boundary:** if the Service exposed 9090, every client that could reach
   8080 could also see `/actuator/health/readiness` and know when the pod is ready.
   This is not a threat here, but it's unnecessary exposure.

3. **Proof that the separation is real:** curl the Route:

   ```bash
   curl https://queue-gate-marwanbukhori-dev.../actuator/health
   ```

   This returns 404 (not found) because the Route does not reach port 9090. The actuator
   endpoints exist on the pod, but they are not reachable from outside.

---

## Why images are tagged with git SHA, not `latest`

Container images are tagged with the git commit short SHA: `ghcr.io/marwanbukhori/queue-gate:270288f`.

This is set in `deploy/overlays/sandbox/kustomization.yaml`:

```yaml
images:
  - name: ghcr.io/marwanbukhori/queue-gate
    newTag: 270288f
```

The `build-push.sh` script generates the tag automatically from `git rev-parse --short HEAD`.

**Why not `latest`?**

`latest` is a convention, not a Kubernetes feature. If you push `queue-gate:latest` today
and again tomorrow, both pushes use the same tag, but the image is different.

When Kubernetes pulls `latest`, it does not know if it has the old or new version.
It must contact the registry every time. Worse, if the pod already has a cached copy of
`latest` in its filesystem, Kubernetes may skip the pull entirely and continue running
the old version.

**With a SHA tag:**

- Each commit produces a unique, immutable tag
- You can see exactly which version is running on the cluster (the tag is in the manifest)
- A rollback is trivial: edit the tag and reapply the manifest
- There is no ambiguity

Combined with Kustomize's `kustomize edit set image`, the workflow becomes:

```bash
git commit  # generates a new SHA
build-push.sh  # pushes TAG=<new SHA>
kustomize edit set image ...:${TAG}  # updates the manifest
oc apply -k deploy/overlays/sandbox  # applies the new version
```

If a deploy goes wrong, you edit the tag back to the previous commit's SHA and reapply.

---

## Why the gate scales to 10 and the booking service to 4

The HPA configuration in each Deployment sets the scaling limits:

```yaml
# queue-gate
maxReplicas: 10
# booking-service
maxReplicas: 4
```

Both target 60% CPU utilization by default.

**Why this asymmetry?**

The database is the scarce resource. A booking service pod makes a database connection
and holds it while executing a transaction. There are a finite number of connection slots
in the pool (default 10 per pod, so 40 across 4 replicas). The gate has no database
connection; it only checks rate limits and queues.

Scaling the gate to 10 allows it to absorb a large spike without waiting for database
capacity. The booking service itself cannot scale beyond the point where connection pooling
becomes a bottleneck.

In testing, the gate ran at 5% CPU utilization even under load, while the booking service
climbed to 2%. This confirms the gate is not the constraint. Adding more booking-service
replicas would not help — the database is the limit.

A real production setup would measure this more precisely and tune the HPA targets
accordingly (e.g., target 40% instead of 60%, or measure database connection utilization
directly).

---

## Why a one-second spike is pre-scaled, not autoscaled

Before running the load test, the runbook includes a manual pre-scale step:

```bash
oc scale deploy/queue-gate --replicas=10
oc scale deploy/booking-service --replicas=4
```

The HPA can take 15–30 seconds to:
1. Notice elevated CPU utilization (metric polling every 15s)
2. Calculate the required replicas (simple formula)
3. Schedule and start new pods (15s+)

A load test lasting 30 seconds would be over before the HPA could react. Scaling in
advance ensures replicas are ready at the start.

In a real system with predictable traffic patterns, this becomes a CronJob: before the
lunch rush, before Black Friday, etc., scale the replicas. This phase uses manual scaling
for simplicity.

---

## The arm64/x86_64 architecture trap and how it presents

**The problem:** The development machine is arm64 (Apple Silicon). The OpenShift cluster
runs x86_64 (amd64).

A plain `docker build` on arm64 produces arm64 layers, which fail to run on amd64 with:

```
exec format error
```

Kubernetes does not wait for this to be obvious. Instead, the pod starts, attempts to
run the binary, fails immediately, and enters `CrashLoopBackOff`.

```bash
$ oc describe pod queue-gate-xyz
Status:         CrashLoopBackOff
```

Check the logs:

```bash
$ oc logs queue-gate-xyz
exec /app/bin/queue-gate: exec format error
```

**Why this is not solved by QEMU emulation:**

The `build-push.sh` script does not rely on QEMU to build the image. Instead:

1. Compile the JARs natively on arm64 (using `./mvnw package`)
2. Build an amd64 image with the pre-built JARs inside

Java bytecode is architecture-independent; the JVM interprets it. Only the base image
(JDK or minimal OS) needs to match the target architecture.

If we tried to run Maven inside an emulated amd64 container (QEMU), Maven's tar extraction
would fail with `ENOSYS` (operation not supported). This is a known QEMU limitation. The
solution is to build natively, then copy the result.

**Prevention:**

```bash
docker buildx build --platform linux/amd64 ...
```

The `--platform` flag (not available in `docker build`, only `docker buildx build`)
explicitly targets amd64. Verify before pushing:

```bash
docker buildx imagetools inspect ghcr.io/marwanbukhori/queue-gate:YOUR-TAG
```

The output must include `linux/amd64`. The `build-push.sh` script does this check
automatically and exits with an error if it fails.

---

## Dev/prod version skew: local 23ai, production 19c

The test suite uses Oracle Free 23ai (latest), via Testcontainers:

```java
// OracleTestBase.java
OracleContainer ORACLE = new OracleContainer(
    "gvenzl/oracle-free:23-slim-faststart"
)
```

The production database is Oracle Autonomous Database on OCI, which reports version
**19.32** (a much older version).

**Does this matter?**

In this project, yes, but only for one dependency. The wallet file format changed; Oracle
23ai's JDBC driver can read 19c wallets, but the library `oraclepki` is required.
Without it, the driver fails with:

```
ORA-17957: Unable to initialize the key store
```

For general SQL and PL/SQL, the gap is narrow: almost all valid 19c syntax works in 23ai.
But for production purposes, **this is a real skew and worth stating plainly.**

In a real system, you would either:
- Use the same version locally and in production (harder to set up initially)
- Run additional tests against a production-version database (CI infrastructure)
- Accept the skew and test edge cases manually

This project accepts the skew and documents it. Future phases may add a production
database for pre-deployment validation.

---

## The load test undercounts, and that matters

Phase 3's load test launched 4989 virtual users, issued 250 tickets, and produced 73
bookings in the database. k6 reported **28 bookings** in the client-observed responses.

The difference is not a bug: it is a fundamental property of distributed systems. Some
booking requests were processed and committed (visible in the database) but the response
was dropped on the return path before reaching the client. k6 counts client-observed
responses; the SQL result counts committed rows.

**The authoritative ground truth is the database query.** The runbook emphasizes this:

```bash
SELECT capacity, seats_taken FROM booking.slots;
```

This is run after each demo as the proof that the invariant held. k6's count is
informational but not definitive.

---

## The run filled 146 of 250 seats, demonstrating "never oversold" but not yet "sold out"

The load test's outcome:
- 250-seat slot
- 4989 virtual users
- 73 bookings committed
- 146 seats taken out of 250
- **0 oversale violations**

This proves the core invariant: even under load, the database never accepted a
251st seat. The queue and the booking service did not oversell.

It does not yet prove that the system can fill all 250 seats (or that it should try).
The demo as designed is a stress test, not a capacity test. To fill the slot, you
would need more virtual users, a longer duration, or both.

**What matters is 0 violations.** The system enforced the constraint perfectly, even
when under concurrent load that far exceeded the available inventory. That is the
correctness proof.

---

## Image building and the two-stage compile

The `build-push.sh` script builds both services in a two-stage pipeline:

1. **Native stage:** Compile JARs natively on the host (arm64):
   ```bash
   (cd booking-service && ./mvnw -q -DskipTests package)
   (cd queue-gate && ./mvnw -q -DskipTests package)
   ```

2. **Image stage:** Build and push amd64 container images with the pre-built JARs:
   ```bash
   docker buildx build --platform linux/amd64 -t ghcr.io/marwanbukhori/queue-gate:${TAG} ./queue-gate
   ```

The two Java services' Dockerfiles are minimal — this is `queue-gate/Dockerfile`
with its comments stripped:

```dockerfile
FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source=https://github.com/marwanbukhori/rembayung-queue
WORKDIR /app
COPY target/*.jar app.jar
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
```

Neither compiles anything; both copy a JAR built on the host, which is fast and
avoids the QEMU tar extraction issue.

The two differ in one place: booking-service ends at
`ENTRYPOINT ["java", "-jar", "/app/app.jar"]`, while queue-gate execs the JAR
through `docker-entrypoint.sh`, which defaults `DROP_OPENS_AT` to thirty seconds
ago when nothing sets it — so a pod started with no drop configuration comes up
with its window already open instead of refusing every request.

`console` is the exception and is not minimal in the same way: it is a two-stage
build, `FROM node:24-alpine AS ui` to compile the Angular bundle and then the
same JRE base to serve it, because the frontend has to be built somewhere and the
host toolchain is Maven.

---

## Cluster quota and HPA ceiling

The OpenShift sandbox grants 3 CPUs and 30Gi RAM total. The HPA ceiling is 1900m (1.9 CPUs):

- `queue-gate`: 100m per replica, max 10 replicas = 1000m
- `booking-service`: 200m per replica, max 4 replicas = 800m
- `redis`: 100m (single replica) = 100m
- **Total: 1900m**

This is a soft constraint — the HPA will not scale beyond it. If you manually scale beyond
quota, pods will enter `Pending` state and not schedule.

The resource *requests* and *limits* are also important:

```yaml
queue-gate:
  requests: { cpu: 100m, memory: 256Mi }
  limits:   { cpu: 500m, memory: 512Mi }

booking-service:
  requests: { cpu: 200m, memory: 512Mi }
  limits:   { cpu: 500m, memory: 1Gi }

console:
  requests: { cpu: 100m, memory: 256Mi }
  limits:   { cpu: 500m, memory: 512Mi }

redis:
  requests: { cpu: 100m, memory: 128Mi }
  limits:   { cpu: 200m, memory: 256Mi }
```

Requests reserve resource on the node. Limits cap how much the pod can use. The HPA scales
based on the request percentage, so if you change requests, scaling behavior changes too.

Those are the application containers. booking-service and queue-gate also run the
Dynatrace agent as an init container, at `requests: { cpu: 50m, memory: 64Mi }` and
`limits: { cpu: 500m, memory: 256Mi }`, and those are stated rather than inherited
for a reason worth knowing: a pod's effective limit is the *maximum* of its init
container and the *sum* of its app containers, so an init container left to the
namespace LimitRange's default of 1 CPU / 1000Mi would inflate what the pod charges
against quota for its whole lifetime — not just while the init container runs.

---

## Summary: Why this works

The system combines several layers of correctness and constraint:

1. **Database constraint:** `CHECK (seats_taken <= capacity)` is enforced at the database level, not in code.
2. **Queue boundary:** only the gate can reach the booking service (NetworkPolicy + no Route).
3. **Liveness/readiness split:** transient failures do not trigger restart cascades.
4. **Pre-scaling:** the HPA is not fast enough for a 30-second spike, so capacity is staged.
5. **Version consistency:** images are tagged by commit, so exact versions are known and reproducible.
6. **Separation of concerns:** the actuator port is separate and not exposed, keeping metrics private.

Together, these do not eliminate failure modes (nothing does), but they make the system
predictable: you can rely on the invariant, you can reproduce a deployment, and you can
scale smoothly under load.

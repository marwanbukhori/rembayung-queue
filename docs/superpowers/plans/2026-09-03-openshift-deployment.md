# OpenShift Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run both services on the OpenShift sandbox against a managed Oracle Autonomous Database, with health probes, autoscaling, and a public Route — so demo steps 1, 3, 4 and 5 become runnable against a real cluster.

**Architecture:** Two Deployments plus Redis, described in Kustomize with one `sandbox` overlay. `queue-gate` is the only object with a Route; `booking-service` and `redis` are Services only, which is the cluster expression of the boundary Phase 2 enforced with `expose:`. Images are built for `linux/amd64` from an arm64 Mac and pushed to ghcr.io with git-SHA tags. The Oracle wallet arrives as a Secret mounted as a volume. Deployment is sequenced so the OCI dependency comes last.

**Tech Stack:** OpenShift 4 (`oc` 4.22.11 with kustomize 5.7.1), Docker buildx, ghcr.io, Spring Boot 4.1.1 Actuator, Oracle Autonomous Database (Always Free), k6

**Spec:** `docs/superpowers/specs/2026-09-03-openshift-deployment-design.md`
**Parent spec:** `docs/superpowers/specs/2026-09-02-rembayung-booking-queue-design.md`

## Global Constraints

- **Cluster:** `api.rm3.7wse.p1.openshiftapps.com`, project **`marwanbukhori-dev`**. Verify with `oc project -q` before any `oc apply`.
- **Quota:** `requests.cpu: 3`, `requests.memory: 30Gi`. The HPA ceilings below total **1900m**; do not exceed the quota.
- **Route domain:** `<name>-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com`.
- **Images must be `linux/amd64`.** The dev machine is arm64. A plain `docker build` produces arm64 layers that fail on the cluster with `exec format error` and a `CrashLoopBackOff` giving no obvious cause. Always `buildx --platform linux/amd64`, and always verify with `imagetools inspect` before deploying.
- **Image tags are git short SHAs, never `latest`.** Phase 5's rollback depends on immutable tags.
- **Registry:** `ghcr.io/marwanbukhori/booking-service` and `ghcr.io/marwanbukhori/queue-gate`.
- **`booking-service` and `redis` get no Route.** Ever. That is the security boundary.
- **Never commit a wallet, a password, or a token.** Secrets are created imperatively from local files.
- **Commit messages must contain no AI attribution.** No `Co-Authored-By`, no `Claude-Session:`, no `claude.ai` URL, no "Generated with", no assistant mention. Hard standing rule of the repository owner; violated once already, requiring a history rewrite. After each commit run `git log -1 --format=%B` and confirm the body is only your subject line and any explanatory prose you wrote.
- Every Maven command must first `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` and `export PATH="$JAVA_HOME/bin:$PATH"`.
- Phase 1 (31 tests) and Phase 2 (34 tests) must keep passing. If a change requires editing an existing test, the change is wrong.

### Spring Boot 4 packaging note

Eight packaging changes have already surfaced in this project from Boot 3
assumptions. Two that matter here, both verified against the resolved jars:

- Health probe classes (`LivenessStateHealthIndicator`, `ReadinessStateHealthIndicator`, `AvailabilityProbesAutoConfiguration`) live in artifact **`spring-boot-health`**, package `org.springframework.boot.health.*` — **not** in `spring-boot-actuator`.
- **`spring-boot-starter-actuator` pulls `spring-boot-health` in transitively**, so the starter alone is enough. Do not add `spring-boot-health` separately.

If anything else fails to resolve, find the jar that actually contains the class before guessing at artifact names:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
for j in $(tr ':' '\n' < /tmp/cp.txt); do
  unzip -l "$j" 2>/dev/null | grep -q 'TheMissingClass.class' && echo "$j"
done
```

---

## File Structure

```
rembayung-queue/
├── booking-service/
│   ├── pom.xml                          + spring-boot-starter-actuator
│   └── src/
│       ├── main/resources/application.yml    + management block
│       └── test/java/dev/marwan/booking/
│           └── HealthProbeTest.java          new
├── queue-gate/
│   ├── pom.xml                          + spring-boot-starter-actuator
│   ├── Dockerfile                       (unchanged)
│   └── src/
│       ├── main/resources/application.yml    + management block
│       └── test/java/dev/marwan/gate/
│           └── HealthProbeTest.java          new
└── deploy/
    ├── base/
    │   ├── kustomization.yaml
    │   ├── redis/
    │   │   ├── deployment.yaml
    │   │   └── service.yaml
    │   ├── queue-gate/
    │   │   ├── deployment.yaml
    │   │   ├── service.yaml
    │   │   ├── route.yaml
    │   │   ├── configmap.yaml
    │   │   └── hpa.yaml
    │   └── booking-service/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       └── hpa.yaml
    ├── overlays/sandbox/
    │   └── kustomization.yaml           image tags, namespace
    ├── scripts/
    │   ├── build-push.sh                buildx both images, verify amd64
    │   ├── create-secrets.sh            wallet + credentials
    │   └── check-egress.sh              prove the cluster can reach OCI
    └── README.md                        runbook
```

`deploy/` is a sibling of the two service directories, matching the fact that it describes both and belongs to neither.

---

## Task 1: Health probes

Adds Actuator to both services with only the health endpoint exposed, on a **separate management port** so `/actuator` is never reachable through the public Route.

**Files:**
- Modify: `booking-service/pom.xml`
- Modify: `booking-service/src/main/resources/application.yml`
- Modify: `queue-gate/pom.xml`
- Modify: `queue-gate/src/main/resources/application.yml`
- Test: `booking-service/src/test/java/dev/marwan/booking/HealthProbeTest.java`
- Test: `queue-gate/src/test/java/dev/marwan/gate/HealthProbeTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET /actuator/health/liveness` and `GET /actuator/health/readiness` on port **9090** in both services. Task 3 and Task 5 wire these into `livenessProbe` and `readinessProbe`.

- [ ] **Step 1: Add the dependency to both poms**

In **both** `booking-service/pom.xml` and `queue-gate/pom.xml`, add inside `<dependencies>`:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
```

No `<version>` — BOM-managed. This transitively provides `spring-boot-health`; do not add that separately.

- [ ] **Step 2: Write the failing tests**

`booking-service/src/test/java/dev/marwan/booking/HealthProbeTest.java`:

```java
package dev.marwan.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class HealthProbeTest extends OracleTestBase {

    @Autowired private MockMvc mvc;

    @Test
    void livenessIsUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessIsUpWhenTheDatabaseIsReachable() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void detailsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
```

`queue-gate/src/test/java/dev/marwan/gate/HealthProbeTest.java`:

```java
package dev.marwan.gate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class HealthProbeTest extends RedisTestBase {

    @Autowired private MockMvc mvc;

    @Test
    void livenessIsUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessIsUpWhenRedisIsReachable() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void detailsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
```

MockMvc dispatches to the application context directly rather than over a socket, so it reaches the actuator endpoints regardless of the management port. The port matters at runtime, and Task 3 verifies it there.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd booking-service && mvn test -Dtest=HealthProbeTest
cd ../queue-gate    && mvn test -Dtest=HealthProbeTest
```

Expected: FAIL — 404, because no actuator endpoints are mapped yet.

- [ ] **Step 4: Add the management config to both services**

Append to `booking-service/src/main/resources/application.yml`:

```yaml
management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
```

Append the identical block to `queue-gate/src/main/resources/application.yml`.

`management.server.port: 9090` moves actuator off the application port, so the Route — which targets 8080 only — cannot reach it. `show-details: never` means the endpoint returns `{"status":"UP"}` and nothing about individual components.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd booking-service && mvn test -Dtest=HealthProbeTest   # 3 tests + 1 inherited
cd ../queue-gate    && mvn test -Dtest=HealthProbeTest   # 3 tests + 1 inherited
```

- [ ] **Step 6: Run both full suites**

```bash
cd booking-service && mvn verify   # expect 35 (31 + 3 new + 1 inherited)
cd ../queue-gate    && mvn verify   # expect 38 (34 + 3 new + 1 inherited)
```

No existing test may change or fail.

- [ ] **Step 7: Commit**

```bash
git add booking-service queue-gate
git commit -m "Add health probes on a separate management port"
```

---

## Task 2: Cross-architecture images in the registry

**Files:**
- Create: `deploy/scripts/build-push.sh`

**Interfaces:**
- Consumes: the Dockerfiles from Phase 2.
- Produces: `ghcr.io/marwanbukhori/booking-service:<sha>` and `ghcr.io/marwanbukhori/queue-gate:<sha>`, both `linux/amd64`. Task 3 and Task 5 reference these tags.

- [ ] **Step 1: Create the GitHub token (manual, one time)**

On github.com → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token, with scopes **`write:packages`** and **`read:packages`**.

```bash
echo <token> | docker login ghcr.io -u marwanbukhori --password-stdin
```

Expect `Login Succeeded`. **Do not commit the token.**

- [ ] **Step 2: Write the build script**

`deploy/scripts/build-push.sh`:

```bash
#!/usr/bin/env bash
# Build both service images for linux/amd64 and push them to ghcr.io.
#
# The development machine is arm64 and the cluster is x86_64. A plain
# `docker build` produces arm64 layers that fail on the cluster with
# `exec format error` and a CrashLoopBackOff that names no cause, so the
# platform flag is not optional and the result is verified before use.
set -euo pipefail

REGISTRY="${REGISTRY:-ghcr.io/marwanbukhori}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"

for svc in booking-service queue-gate; do
  image="${REGISTRY}/${svc}:${TAG}"
  echo "building ${image}"
  docker buildx build \
    --platform linux/amd64 \
    -t "${image}" \
    --push \
    "./${svc}"

  echo "verifying architecture of ${image}"
  arch="$(docker buildx imagetools inspect "${image}" \
            --format '{{range .Manifest.Manifests}}{{.Platform.OS}}/{{.Platform.Architecture}} {{end}}' \
          2>/dev/null || docker buildx imagetools inspect "${image}" | grep -i 'Platform' | head -1)"
  case "${arch}" in
    *linux/amd64*) echo "  ok: ${arch}" ;;
    *) echo "  FAIL: expected linux/amd64, got '${arch}'" >&2; exit 1 ;;
  esac
done

echo
echo "pushed at tag ${TAG}"
echo "set it in the overlay with:"
echo "  cd deploy/overlays/sandbox && kustomize edit set image \\"
echo "    ${REGISTRY}/booking-service=${REGISTRY}/booking-service:${TAG} \\"
echo "    ${REGISTRY}/queue-gate=${REGISTRY}/queue-gate:${TAG}"
```

```bash
chmod +x deploy/scripts/build-push.sh
```

- [ ] **Step 3: Run it**

```bash
./deploy/scripts/build-push.sh
```

Expected: both images build, push, and report `ok: linux/amd64`. The first build downloads Maven dependencies and is slow; later builds reuse layers.

If the architecture check fails, **stop** — everything downstream would `CrashLoopBackOff` for a reason that is invisible in the pod logs.

- [ ] **Step 4: Make the packages public**

On github.com → your profile → Packages → each package → Package settings → Change visibility → Public.

This lets the cluster pull without a pull secret. If you prefer to keep them private, instead run:

```bash
oc create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username=marwanbukhori \
  --docker-password=<token>
oc secrets link default ghcr-pull --for=pull
```

and record which route you took — Task 3 needs to know whether `imagePullSecrets` is required.

- [ ] **Step 5: Commit**

```bash
git add deploy/scripts/build-push.sh
git commit -m "Add a cross-architecture image build and push script"
```

---

## Task 3: Kustomize base and the stateless deployment

Deploys `redis` and `queue-gate` — everything that does not need a database — proving images, pulls, Routes, ConfigMaps and probes before OCI exists.

**Files:**
- Create: `deploy/base/kustomization.yaml`
- Create: `deploy/base/redis/deployment.yaml`, `deploy/base/redis/service.yaml`
- Create: `deploy/base/queue-gate/deployment.yaml`, `service.yaml`, `route.yaml`, `configmap.yaml`
- Create: `deploy/overlays/sandbox/kustomization.yaml`

**Interfaces:**
- Consumes: images from Task 2; probe endpoints from Task 1.
- Produces: a working Route at `queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com`, and the `queue-gate-config` ConfigMap that Task 7 patches to open the drop.

- [ ] **Step 1: Write the Redis manifests**

`deploy/base/redis/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  labels: { app: redis }
spec:
  replicas: 1
  selector:
    matchLabels: { app: redis }
  template:
    metadata:
      labels: { app: redis }
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports:
            - containerPort: 6379
          resources:
            requests: { cpu: 100m, memory: 128Mi }
            limits:   { cpu: 200m, memory: 256Mi }
          livenessProbe:
            tcpSocket: { port: 6379 }
            initialDelaySeconds: 10
          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 5
            periodSeconds: 5
```

No PVC. Queue state is deliberately disposable: if Redis dies, queue positions are lost but **no confirmed booking is**, because those live only in Oracle.

`deploy/base/redis/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
spec:
  selector: { app: redis }
  ports:
    - port: 6379
      targetPort: 6379
```

No Route. A reachable Redis would let anyone forge an admission token with a single `SET`.

- [ ] **Step 2: Write the queue-gate manifests**

`deploy/base/queue-gate/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: queue-gate-config
data:
  DROP_OPENS_AT: "2026-09-03T12:00:00Z"
  DROP_CLOSES_AT: "2030-01-01T00:00:00Z"
  DROP_SEATS: "250"
  DROP_TICKET_CAP: "250"
  DROP_ADMIT_RATE: "200"
  DROP_ADMISSION_WINDOW: "PT30M"
  DROP_TICKET_TTL: "PT30M"
```

`DROP_OPENS_AT` is always set explicitly here. The container entrypoint computes a fallback when it is unset, which is right for a laptop and wrong for a cluster — a pod restarting mid-drop would silently reopen the window and readmit everyone from ticket 1.

`deploy/base/queue-gate/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: queue-gate
  labels: { app: queue-gate }
spec:
  replicas: 2
  selector:
    matchLabels: { app: queue-gate }
  template:
    metadata:
      labels: { app: queue-gate }
    spec:
      containers:
        - name: queue-gate
          image: ghcr.io/marwanbukhori/queue-gate
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 9090
          envFrom:
            - configMapRef: { name: queue-gate-config }
          env:
            - name: SPRING_DATA_REDIS_HOST
              value: redis
            - name: BOOKING_SERVICE_BASE_URL
              value: http://booking-service:8081
          resources:
            requests: { cpu: 100m, memory: 256Mi }
            limits:   { cpu: 500m, memory: 512Mi }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            initialDelaySeconds: 20
            periodSeconds: 10
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: management }
            initialDelaySeconds: 10
            periodSeconds: 5
```

`deploy/base/queue-gate/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: queue-gate
spec:
  selector: { app: queue-gate }
  ports:
    - name: http
      port: 8080
      targetPort: 8080
```

The Service exposes **only** 8080. Port 9090 is deliberately absent, so nothing can route to actuator.

`deploy/base/queue-gate/route.yaml`:

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: queue-gate
spec:
  to:
    kind: Service
    name: queue-gate
  port:
    targetPort: http
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
```

- [ ] **Step 3: Write the kustomizations**

`deploy/base/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - redis/deployment.yaml
  - redis/service.yaml
  - queue-gate/configmap.yaml
  - queue-gate/deployment.yaml
  - queue-gate/service.yaml
  - queue-gate/route.yaml
```

`booking-service` is deliberately absent until Task 5.

`deploy/overlays/sandbox/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: marwanbukhori-dev
resources:
  - ../../base
images:
  - name: ghcr.io/marwanbukhori/queue-gate
    newTag: PLACEHOLDER_SET_BY_BUILD
```

Set the real tag before applying:

```bash
cd deploy/overlays/sandbox
kustomize edit set image \
  ghcr.io/marwanbukhori/queue-gate=ghcr.io/marwanbukhori/queue-gate:$(git rev-parse --short HEAD)
```

(`oc` bundles kustomize; if the standalone binary is absent, edit `newTag` by hand.)

- [ ] **Step 4: Validate before applying**

```bash
oc project -q                                    # must print marwanbukhori-dev
oc kustomize deploy/overlays/sandbox | head -40  # renders without error
oc apply -k deploy/overlays/sandbox --dry-run=server
```

`--dry-run=server` catches schema errors and quota rejections without creating anything.

- [ ] **Step 5: Apply and watch**

```bash
oc apply -k deploy/overlays/sandbox
oc rollout status deploy/queue-gate --timeout=180s
oc rollout status deploy/redis --timeout=120s
oc get pods
```

**If a pod is `CrashLoopBackOff`, check the architecture first:**

```bash
oc logs deploy/queue-gate | head -20
```

`exec format error` means an arm64 image reached the cluster; rebuild with `--platform linux/amd64`.

- [ ] **Step 6: Verify the Route and the boundary**

```bash
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
curl -s -XPOST $GATE/queue | jq .
```

Expected: a JSON body with `token`, `ticket`, `position`.

Then confirm actuator is **not** publicly reachable:

```bash
curl -s -o /dev/null -w '%{http_code}\n' $GATE/actuator/health
```

Expected **404** — the Service does not expose 9090. If this returns 200, the management port is not isolated; fix before continuing.

And confirm the probes are working internally:

```bash
oc get pods -l app=queue-gate \
  -o custom-columns=NAME:.metadata.name,READY:.status.containerStatuses[0].ready
```

Expected `true` — readiness passing proves the probe reaches 9090 inside the pod.

- [ ] **Step 7: Commit**

```bash
git add deploy/
git commit -m "Deploy Redis and the queue gate to the sandbox"
```

---

## Task 4: Oracle Autonomous Database and an egress proof

Interactive account work, ending with hard evidence the cluster can actually reach the database. **Prove this before writing any more manifests** — sandbox egress is not guaranteed, and discovering it late would be expensive.

**Files:**
- Create: `deploy/scripts/check-egress.sh`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a running Autonomous Database, a wallet directory on disk, and the TNS alias Task 5 puts in the JDBC URL.

- [ ] **Step 1: Create the database (manual)**

1. Sign up at `cloud.oracle.com` if needed.
2. Create an **Autonomous Transaction Processing** instance on the **Always Free** shape. Record the ADMIN password.
3. Database actions → SQL, and create the application user:

```sql
CREATE USER booking IDENTIFIED BY "<a strong password>";
GRANT CONNECT, RESOURCE TO booking;
ALTER USER booking QUOTA UNLIMITED ON DATA;
```

`RESOURCE` plus a tablespace quota is what Flyway needs to create tables. Do not grant `DBA`.

- [ ] **Step 2: Download and unpack the wallet**

Console → your database → Database connection → Download wallet (**Instance Wallet**). Set a wallet password when prompted.

```bash
mkdir -p ~/rembayung-wallet
unzip ~/Downloads/Wallet_*.zip -d ~/rembayung-wallet
ls ~/rembayung-wallet
```

Expect `tnsnames.ora`, `sqlnet.ora`, `cwallet.sso`, `ewallet.p12`, `keystore.jks`, `truststore.jks`, `ojdbc.properties`.

**This directory must never be committed.** It is outside the repository on purpose.

Find the alias and the host:

```bash
grep -o '^[a-z0-9_]*' ~/rembayung-wallet/tnsnames.ora | sort -u
grep -o 'host=[^)]*' ~/rembayung-wallet/tnsnames.ora | head -1
```

Use the **`_low`** alias: highest concurrency at the lowest per-query parallelism, which suits many small transactions. Record it — Task 5 needs it.

- [ ] **Step 3: Write the egress check**

`deploy/scripts/check-egress.sh`:

```bash
#!/usr/bin/env bash
# Prove the cluster can open a TCP connection to the Autonomous Database
# before any application manifest depends on it. Sandbox egress is not
# guaranteed, and a connection refused here is far cheaper to find now than
# after booking-service is deployed and failing its readiness probe.
set -euo pipefail

HOST="${1:?usage: check-egress.sh <adb-host> [port]}"
PORT="${2:-1522}"

echo "testing egress from the cluster to ${HOST}:${PORT}"
oc run egress-check-$$ \
  --image=registry.access.redhat.com/ubi9/ubi-minimal:latest \
  --restart=Never --rm -i --quiet --command -- \
  timeout 15 bash -c "</dev/tcp/${HOST}/${PORT} && echo REACHABLE || echo UNREACHABLE"
```

```bash
chmod +x deploy/scripts/check-egress.sh
```

Oracle Autonomous Database listens on **1522**, not 1521.

- [ ] **Step 4: Run it**

```bash
./deploy/scripts/check-egress.sh <host-from-tnsnames> 1522
```

Expected: `REACHABLE`.

If `UNREACHABLE`, stop and report. The remaining options are the database's access-control list (open it to `0.0.0.0/0` for a demo, or to the cluster's egress IP) or a sandbox egress restriction — and knowing which changes the rest of the phase.

- [ ] **Step 5: Commit**

```bash
git add deploy/scripts/check-egress.sh
git commit -m "Add a cluster-to-database egress check"
```

---

## Task 5: Secrets and the booking service

**Files:**
- Create: `deploy/scripts/create-secrets.sh`
- Create: `deploy/base/booking-service/deployment.yaml`, `service.yaml`
- Modify: `deploy/base/kustomization.yaml`
- Modify: `deploy/overlays/sandbox/kustomization.yaml`

**Interfaces:**
- Consumes: the wallet and TNS alias from Task 4; the image from Task 2.
- Produces: `booking-service` reachable in-cluster at `http://booking-service:8081`, which `queue-gate` already points at.

- [ ] **Step 1: Write the secret script**

`deploy/scripts/create-secrets.sh`:

```bash
#!/usr/bin/env bash
# Create the wallet and credential secrets from local files.
#
# These are created imperatively and never committed. The deployment is
# therefore not fully reproducible from the repository alone, which is the
# correct trade: a wallet in git would be worse.
set -euo pipefail

WALLET_DIR="${WALLET_DIR:-$HOME/rembayung-wallet}"
DB_USER="${DB_USER:-booking}"
DB_PASSWORD="${DB_PASSWORD:?set DB_PASSWORD}"

[ -f "${WALLET_DIR}/tnsnames.ora" ] || {
  echo "no wallet at ${WALLET_DIR}" >&2; exit 1; }

echo "creating oracle-wallet from ${WALLET_DIR}"
oc create secret generic oracle-wallet \
  --from-file="${WALLET_DIR}" \
  --dry-run=client -o yaml | oc apply -f -

echo "creating oracle-credentials"
oc create secret generic oracle-credentials \
  --from-literal=SPRING_DATASOURCE_USERNAME="${DB_USER}" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
  --dry-run=client -o yaml | oc apply -f -

oc get secret oracle-wallet oracle-credentials
```

```bash
chmod +x deploy/scripts/create-secrets.sh
DB_PASSWORD='<the booking user password>' ./deploy/scripts/create-secrets.sh
```

`--dry-run=client -o yaml | oc apply -f -` makes the script idempotent: re-running it updates rather than failing with "already exists".

- [ ] **Step 2: Write the booking-service manifests**

`deploy/base/booking-service/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: booking-service
  labels: { app: booking-service }
spec:
  replicas: 2
  selector:
    matchLabels: { app: booking-service }
  template:
    metadata:
      labels: { app: booking-service }
    spec:
      containers:
        - name: booking-service
          image: ghcr.io/marwanbukhori/booking-service
          ports:
            - name: http
              containerPort: 8081
            - name: management
              containerPort: 9090
          env:
            - name: SERVER_PORT
              value: "8081"
            - name: SPRING_DATASOURCE_URL
              value: jdbc:oracle:thin:@REPLACE_TNS_ALIAS?TNS_ADMIN=/oracle/wallet
          envFrom:
            - secretRef: { name: oracle-credentials }
          volumeMounts:
            - name: oracle-wallet
              mountPath: /oracle/wallet
              readOnly: true
          resources:
            requests: { cpu: 200m, memory: 512Mi }
            limits:   { cpu: 500m, memory: 1Gi }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: management }
            initialDelaySeconds: 40
            periodSeconds: 10
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: management }
            initialDelaySeconds: 20
            periodSeconds: 5
      volumes:
        - name: oracle-wallet
          secret:
            secretName: oracle-wallet
```

Replace `REPLACE_TNS_ALIAS` with the `_low` alias from Task 4 — for example `jdbc:oracle:thin:@rembayung_low?TNS_ADMIN=/oracle/wallet`.

`initialDelaySeconds: 40` on liveness is deliberately generous: the first startup runs Flyway against a remote database over mTLS, and a liveness probe that fires too early restarts the pod mid-migration.

Liveness does **not** check the database and readiness does. A database blip must remove the pod from the Service, not restart it — restarts during a database outage make the outage worse.

`deploy/base/booking-service/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: booking-service
spec:
  selector: { app: booking-service }
  ports:
    - name: http
      port: 8081
      targetPort: 8081
```

**No Route.** This is the security boundary: the queue cannot be bypassed because the booking endpoint is not reachable from outside the cluster.

- [ ] **Step 3: Add it to the kustomizations**

Append to `deploy/base/kustomization.yaml`:

```yaml
  - booking-service/deployment.yaml
  - booking-service/service.yaml
```

Add the image to `deploy/overlays/sandbox/kustomization.yaml`:

```yaml
  - name: ghcr.io/marwanbukhori/booking-service
    newTag: PLACEHOLDER_SET_BY_BUILD
```

then set both tags:

```bash
cd deploy/overlays/sandbox
kustomize edit set image \
  ghcr.io/marwanbukhori/booking-service=ghcr.io/marwanbukhori/booking-service:$(git rev-parse --short HEAD) \
  ghcr.io/marwanbukhori/queue-gate=ghcr.io/marwanbukhori/queue-gate:$(git rev-parse --short HEAD)
```

- [ ] **Step 4: Apply and verify**

```bash
oc apply -k deploy/overlays/sandbox
oc rollout status deploy/booking-service --timeout=300s
oc logs deploy/booking-service | grep -iE 'flyway|migrat|HikariPool|started' | head
```

Expect Flyway applying `V1__initial_schema`, then a started banner.

**If the pod is not ready**, the readiness probe is telling you the database is unreachable:

```bash
oc logs deploy/booking-service --tail=50
oc exec deploy/booking-service -- ls /oracle/wallet
```

Common causes: wrong TNS alias, wallet not mounted, or the database ACL rejecting the cluster.

- [ ] **Step 5: Seed a slot and prove the whole path**

```bash
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
TOKEN=$(curl -s -XPOST $GATE/queue | jq -r .token)
curl -s $GATE/queue/$TOKEN | jq .
curl -s -XPOST $GATE/bookings \
  -H "X-Admission-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"slotId":1,"phone":"+60123456789","partySize":2,"idempotencyKey":"cluster-1"}' | jq .
```

If this returns `404 SLOT_NOT_FOUND`, the path works and there is simply no slot yet. Insert one through the OCI console's SQL worksheet:

```sql
INSERT INTO slots (service_date, service_time, capacity, seats_taken)
VALUES (DATE '2026-10-01', '19:00', 250, 0);
COMMIT;
SELECT id, capacity, seats_taken FROM slots;
```

Then repeat the booking with the real `slotId` and expect `201`.

- [ ] **Step 6: Commit**

```bash
git add deploy/
git commit -m "Deploy the booking service against the managed database"
```

---

## Task 6: Autoscaling

**Files:**
- Create: `deploy/base/queue-gate/hpa.yaml`, `deploy/base/booking-service/hpa.yaml`
- Modify: `deploy/base/kustomization.yaml`

**Interfaces:**
- Consumes: both Deployments.
- Produces: HPAs Task 7 exercises under load.

- [ ] **Step 1: Write the HPAs**

`deploy/base/queue-gate/hpa.yaml`:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: queue-gate
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: queue-gate
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

`deploy/base/booking-service/hpa.yaml` is identical except:

```yaml
metadata:
  name: booking-service
spec:
  scaleTargetRef:
    name: booking-service
  minReplicas: 2
  maxReplicas: 4
```

The gate scales to 10 and the booking service stops at 4. That asymmetry is the whole design: the database is the scarce resource, so load is absorbed in front of it rather than passed through. Worst case is 1000m + 800m + 100m = **1900m of the 3000m quota**.

- [ ] **Step 2: Add to the base kustomization**

```yaml
  - queue-gate/hpa.yaml
  - booking-service/hpa.yaml
```

- [ ] **Step 3: Apply and confirm metrics are flowing**

```bash
oc apply -k deploy/overlays/sandbox
oc get hpa
```

Expect a `TARGETS` column showing an actual percentage such as `3%/60%`.

**`<unknown>/60%` means the HPA cannot read metrics and will never scale.** Give it 60 seconds, then check `oc adm top pods` returns figures. If it does and the HPA still shows unknown, the pods are missing CPU *requests* — utilisation is a percentage of the request, so it is undefined without one.

- [ ] **Step 4: Commit**

```bash
git add deploy/
git commit -m "Add autoscaling for the gate and the booking service"
```

---

## Task 7: The demo, end to end

Runs the parent spec's demo steps against the cluster.

**Files:**
- Create: `deploy/scripts/open-drop.sh`

**Interfaces:**
- Consumes: everything above.
- Produces: evidence the invariant holds on the cluster, and a repeatable way to open a drop.

- [ ] **Step 1: Write the drop-opening script**

`deploy/scripts/open-drop.sh`:

```bash
#!/usr/bin/env bash
# Open the drop now and restart the gate so every replica agrees on when it
# opened. The ConfigMap is read at startup, so a patch alone is not enough.
#
# Redis also has to be flushed: queue:ticket is monotonic and the cap is 250,
# so without a reset every join in a second run correctly returns SOLD_OUT and
# the demo shows nothing.
set -euo pipefail

OPENS_AT="$(date -u -d '30 seconds ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
            || date -u -v-30S +%Y-%m-%dT%H:%M:%SZ)"

echo "opening the drop at ${OPENS_AT}"
oc patch configmap queue-gate-config --type merge \
  -p "{\"data\":{\"DROP_OPENS_AT\":\"${OPENS_AT}\"}}"

echo "flushing the ticket counter"
oc exec deploy/redis -- redis-cli FLUSHALL

echo "restarting the gate so the new window is picked up"
oc rollout restart deploy/queue-gate
oc rollout status deploy/queue-gate --timeout=180s

echo "drop open; seats must be reset separately in the database"
```

```bash
chmod +x deploy/scripts/open-drop.sh
```

- [ ] **Step 2: Pre-scale, because HPA cannot react in time**

```bash
oc scale deploy/queue-gate --replicas=10
oc rollout status deploy/queue-gate --timeout=180s
```

The arrival burst is about one second wide; HPA polls every 15 seconds and takes 15–30 to schedule. **For a known event you pre-scale rather than autoscale.** Say so during the demo — it is a better answer than pretending the HPA absorbs it.

- [ ] **Step 3: Reset and fire the load test**

Reset `seats_taken` to 0 in the OCI console, then:

```bash
./deploy/scripts/open-drop.sh
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
k6 run -e GATE=$GATE -e SLOT_ID=<id> loadtest/drop.js
```

Watch scaling in a second terminal:

```bash
oc get hpa -w
```

Expect: no 5xx, `bookings_created` above its threshold, and pods scaling.

- [ ] **Step 4: Verify the invariant — the step that matters**

In the OCI SQL worksheet:

```sql
SELECT capacity, seats_taken, capacity - seats_taken AS remaining FROM slots;
SELECT COUNT(*) AS violations FROM slots WHERE seats_taken > capacity;
```

`violations` must be 0 and `seats_taken` must never exceed `capacity`. A full slot may finish at 250 **or** 249 — once one seat remains, no party of two fits.

- [ ] **Step 5: Kill a pod under load (parent spec demo step 5)**

While k6 runs:

```bash
oc delete pod -l app=booking-service --wait=false
```

In-flight requests fail; the Deployment replaces the pod; **no confirmed booking is lost or duplicated**. Re-run the invariant query to confirm.

- [ ] **Step 6: Scale back down**

```bash
oc scale deploy/queue-gate --replicas=2
```

- [ ] **Step 7: Commit**

```bash
git add deploy/scripts/open-drop.sh
git commit -m "Add a script to open a drop on the cluster"
```

---

## Task 8: Runbook

**Files:**
- Create: `deploy/README.md`
- Create: `docs/notes/04-openshift-deployment.md`
- Modify: `docs/notes/README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: documentation sufficient to redeploy from scratch after the sandbox expires.

- [ ] **Step 1: Write the deployment runbook**

`deploy/README.md` must cover, with real commands:

- Prerequisites: `oc login`, `docker login ghcr.io`, the wallet at `~/rembayung-wallet`.
- First-time setup, in order: `create-secrets.sh` → `build-push.sh` → `kustomize edit set image` → `oc apply -k deploy/overlays/sandbox`.
- Routine redeploy: build, set tag, apply, `oc rollout status`.
- Running a demo: `open-drop.sh`, pre-scale, k6, invariant query, scale down.
- Troubleshooting, each with its cause:
  - `CrashLoopBackOff` with `exec format error` → arm64 image; rebuild with `--platform linux/amd64`.
  - Pod not ready → readiness failing; check `oc logs` and `oc exec -- ls /oracle/wallet`.
  - HPA `<unknown>/60%` → missing CPU requests, or metrics not yet available.
  - Every join returns `SOLD_OUT` → ticket counter exhausted; `open-drop.sh` flushes it.
  - Pods `Pending` → quota; `oc describe resourcequota compute-deploy`.
- **Sandbox expiry:** the cluster URL and project name change on renewal, so `deploy/overlays/sandbox/kustomization.yaml` needs editing and both secrets need recreating.

- [ ] **Step 2: Write the stack note**

`docs/notes/04-openshift-deployment.md`, following the style of notes 01–03: what each object does and *why it was configured that way*. Cover at least:

- Why `booking-service` has no Route, and how that is the same boundary as Phase 2's `expose:`.
- Why liveness excludes the database and readiness includes it.
- Why actuator sits on port 9090 and the Service exposes only 8080.
- Why images are tagged with a git SHA rather than `latest`.
- Why the gate scales to 10 and the booking service to 4.
- Why a one-second spike is pre-scaled rather than autoscaled.
- The arm64/x86_64 trap and how it presents.

- [ ] **Step 3: Update the notes index**

Add a row to `docs/notes/README.md`.

- [ ] **Step 4: Commit**

```bash
git add deploy/README.md docs/notes/
git commit -m "Document the deployment and demo runbook"
```

---

## Self-Review

**Spec coverage.** §3 accounts → Tasks 2 and 4. §4 architecture and object mapping → Tasks 3, 5, 6. §5 images, cross-arch, SHA tags → Task 2. §6 ConfigMap, both Secrets, wallet mount, `TNS_ADMIN` in the URL, explicit `DROP_OPENS_AT` → Tasks 3, 5, 7. §7 probes → Task 1, wired in Tasks 3 and 5. §8 HPA ceilings and pre-scaling → Tasks 6 and 7. §9 Kustomize layout → Tasks 3 and 5. §10 sequencing → the task order itself, with the egress proof at Task 4. §11 demo steps → Task 7. §13 weaknesses → Task 8's runbook.

**Placeholders.** Two intentional and both explicitly resolved by a command in the same step: `PLACEHOLDER_SET_BY_BUILD` (set by `kustomize edit set image`) and `REPLACE_TNS_ALIAS` (set from the alias recorded in Task 4 Step 2). No others.

**Type consistency.** Port numbers agree throughout: `queue-gate` 8080/9090, `booking-service` 8081/9090, `redis` 6379. The Service name `booking-service` matches the `BOOKING_SERVICE_BASE_URL` the gate is given in Task 3, and the Service created in Task 5. Probe paths `/actuator/health/{liveness,readiness}` match the config in Task 1. Secret names `oracle-wallet` and `oracle-credentials` match between Task 5's script and its Deployment. The mount path `/oracle/wallet` matches the `TNS_ADMIN` query parameter.

**Known gaps, carried forward deliberately.** Secrets are imperative, so the repository alone cannot reproduce the deployment — correct for a wallet, and noted in the runbook. Pre-scaling is manual; a CronJob adjusting `minReplicas` is the real answer and belongs to a later phase. One overlay only, since the sandbox grants a single project.

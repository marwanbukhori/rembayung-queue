# OpenShift Deployment Runbook

This is the operational guide for deploying Rembayung booking queue to OpenShift.

---

## Prerequisites

Before attempting deployment, you must have:

- **OpenShift CLI:** `oc` installed and in `$PATH`
- **Docker or Podman:** with buildx support for cross-architecture builds
- **Kustomize:** `kustomize` in `$PATH`, or use `kubectl kustomize` as a fallback
- **GitHub Container Registry access:** logged into `docker login ghcr.io` with a PAT that permits package push
- **The OCI wallet:** at `~/rembayung-wallet/` containing `tnsnames.ora`, `cwallet.sso`, `sqlnet.ora` and the key files. This is downloaded separately from OCI and is **never committed**.
- **Oracle Autonomous Database credentials:** the wallet password and database login password (seen below).

---

## Cluster and Sandbox Details

**Cluster:** `api.rm3.7wse.p1.openshiftapps.com`  
**Project namespace:** `marwanbukhori-dev`  
**Route:** `https://queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com`

Get a login token from the OpenShift web console, then authenticate:

```bash
oc login api.rm3.7wse.p1.openshiftapps.com --token=<token>
```

---

## First-Time Setup

The following must be run in order, only once per new sandbox. After sandbox expiry (see below), some steps must be repeated.

### Step 1: Create Secrets

The wallet and database credentials are stored as Kubernetes Secrets and mounted at runtime. They are **not** committed to the repository.

```bash
export DB_PASSWORD="<your database password>"
deploy/scripts/create-secrets.sh
```

This script:
- Reads the OCI wallet from `~/rembayung-wallet/`
- Creates a Secret named `oracle-wallet` containing the wallet files
- Creates a Secret named `oracle-credentials` with the database username and password

If the wallet is in a different location, set `WALLET_DIR`:

```bash
export WALLET_DIR="/path/to/wallet" DB_PASSWORD="..." deploy/scripts/create-secrets.sh
```

If a secret already exists, the script will update it.

Verify both secrets exist:

```bash
oc get secret oracle-wallet oracle-credentials
```

### Step 2: Build and Push Images

Images are tagged with the current git commit SHA and pushed to GitHub Container Registry. They must be `linux/amd64` even though you are building on `arm64`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
deploy/scripts/build-push.sh
```

This builds the JARs natively on arm64 (this is fast), then builds and pushes amd64 images. The script will print the tag used (the short SHA) and confirm the platform:

```
pushed at tag 270288f
set it in the overlay with:
  cd deploy/overlays/sandbox && kustomize edit set image ...
```

If the build environment differs, you can set `JAVA_HOME`, `REGISTRY`, or `TAG`:

```bash
export REGISTRY="ghcr.io/your-org" TAG="v1.0.0"
deploy/scripts/build-push.sh
```

### Step 3: Update the Image Tag in the Overlay

The Kustomize overlay tells Kubernetes which image tag to use. Update it to the tag you just pushed:

```bash
cd deploy/overlays/sandbox
kustomize edit set image \
  ghcr.io/marwanbukhori/booking-service=ghcr.io/marwanbukhori/booking-service:270288f \
  ghcr.io/marwanbukhori/queue-gate=ghcr.io/marwanbukhori/queue-gate:270288f
```

Use the short SHA from the previous step. Verify the edit:

```bash
cat kustomization.yaml
```

### Step 4: Deploy to OpenShift

Apply all manifests (Deployments, Services, NetworkPolicies, ConfigMaps) via Kustomize:

```bash
oc apply -k deploy/overlays/sandbox
```

This creates:
- A `queue-gate` Deployment with a public Route
- A `booking-service` Deployment (no public Route)
- A `redis` Deployment
- Services for all three
- NetworkPolicies restricting ingress to `queue-gate` only
- ConfigMaps for configuration

Wait for all pods to be ready:

```bash
oc rollout status deploy/queue-gate --timeout=180s
oc rollout status deploy/booking-service --timeout=180s
oc rollout status deploy/redis --timeout=180s
```

Verify the Route is up and the deployment is working:

```bash
oc get route queue-gate
curl -I https://queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com/actuator/health
```

---

## Routine Redeploy

After the initial setup, code changes flow through this pipeline:

1. **Push code to GitHub** → triggers a test run
2. **Build and push new images**

   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@25
   deploy/scripts/build-push.sh
   ```

3. **Update the overlay with the new tag** (see Step 3 above)

4. **Apply the updated deployment**

   ```bash
   oc apply -k deploy/overlays/sandbox
   ```

5. **Monitor the rollout**

   ```bash
   oc rollout status deploy/queue-gate --timeout=180s
   ```

The deployment uses `RollingUpdate` strategy by default, so old pods drain gracefully before new ones start. If you want faster feedback, tail the logs:

```bash
oc logs deploy/queue-gate -f --all-containers --since=30s
```

---

## Running a Demo

The demo flow is: open the drop window, pre-scale to handle load, run k6, check the result, scale back down.

### Step 1: Reset State

```bash
deploy/scripts/open-drop.sh
```

This script:
- Sets `DROP_OPENS_AT` to 30 seconds ago (so the drop is live immediately)
- Flushes Redis (the ticket counter is monotonic and maxes at 250)
- Restarts `queue-gate` so all replicas see the new window

The **seats counter in the database must be reset separately:**

```bash
oc exec deploy/booking-service -- \
  sqlplus -S booking/"${BOOKING_PASSWORD}"@cv02eb7y952snfi2_low@adb.ap-kulai-2.oraclecloud.com:1522 <<EOF
UPDATE booking.slots SET seats_taken = 0 WHERE capacity = 250;
COMMIT;
SELECT capacity, seats_taken FROM booking.slots;
EXIT;
EOF
```

(Replace `${BOOKING_PASSWORD}` with the actual database password.)

### Step 2: Pre-scale

The gate and booking service will scale up under load, but the HPA reacts every 15 seconds and takes 15–30 seconds to schedule new pods. A one-second spike would be over before autoscaling could react, so pre-scale manually:

```bash
oc scale deploy/queue-gate --replicas=10
oc scale deploy/booking-service --replicas=4
```

Wait for the pods to be ready:

```bash
oc get pods -l app.kubernetes.io/name=queue-gate,app.kubernetes.io/name=booking-service
```

### Step 3: Run the Load Test

From the repository root:

```bash
export VUS=1500  # virtual users (tunable; default is 1500)
k6 run --vus $VUS --duration 30s loadtest/drop.js
```

`k6` is the load-testing tool. It will report:
- Requests sent and received
- Latency percentiles
- How many bookings the *client* observed (this may be less than the database count due to response timeouts; see "Honesty" below)

The test typically runs 30 seconds and tries to fill the 250-seat slot.

### Step 4: Verify the Invariant

Query the database directly to see the true result:

```bash
oc exec deploy/booking-service -- \
  sqlplus -S booking/"${BOOKING_PASSWORD}"@cv02eb7y952snfi2_low@adb.ap-kulai-2.oraclecloud.com:1522 <<EOF
SELECT capacity, seats_taken FROM booking.slots;
SELECT COUNT(*) as bookings FROM booking.bookings;
SELECT COUNT(*) as unique_customers FROM (SELECT DISTINCT customer_id FROM booking.bookings);
EXIT;
EOF
```

The key row is `seats_taken`: it must never exceed `capacity`, even under load.

**Honesty:** k6 counts *client-observed* responses. The database counts *committed rows*. If some booking requests were processed and committed but the response was dropped on the return path, k6 will report fewer bookings than the database holds. The SQL result is the authoritative ground truth.

### Step 5: Scale Down

When the demo is complete, scale the replicas back to the base level (usually 1 or 2):

```bash
oc scale deploy/queue-gate --replicas=2
oc scale deploy/booking-service --replicas=1
```

---

## Troubleshooting

### Pod stuck in `CrashLoopBackOff` with `exec format error`

**Symptom:** `oc describe pod <name>` shows:
```
Error response from daemon: ... exec format error
```

**Cause:** The image was built for arm64 instead of amd64. This happens when:
- You used `docker build` instead of `docker buildx build --platform linux/amd64`
- Or you have an old image tag pointing to an arm64 image

**Fix:**
1. Rebuild with the platform flag:
   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@25
   deploy/scripts/build-push.sh
   ```
2. Verify the image architecture before pushing:
   ```bash
   docker buildx imagetools inspect ghcr.io/marwanbukhori/queue-gate:YOUR-TAG
   ```
   The output must include `linux/amd64`.

3. Update the overlay and redeploy:
   ```bash
   cd deploy/overlays/sandbox
   kustomize edit set image ghcr.io/marwanbukhori/queue-gate=ghcr.io/marwanbukhori/queue-gate:NEW-TAG
   oc apply -k deploy/overlays/sandbox
   ```

**Why this happens:** The development machine is arm64 (Apple Silicon), the cluster is x86_64. A naive `docker build` produces arm64 layers that fail to run on amd64. The `build-push.sh` script works around this by building JARs natively on arm64 (bytecode is architecture-independent) and copying them into amd64 base images.

### Pod stuck in `CreateContainerConfigError`

**Symptom:** `oc describe pod <name>` shows:
```
Error creating: configmap "oracle-wallet" not found
```

**Cause:** A referenced Secret does not exist, usually because `create-secrets.sh` was not run or failed.

**Fix:**
```bash
export DB_PASSWORD="<password>" WALLET_DIR="$HOME/rembayung-wallet"
deploy/scripts/create-secrets.sh
```

Then restart the pods:
```bash
oc rollout restart deploy/booking-service
```

### Pod slow to become ready, or readiness failing

**Symptom:** `oc logs` shows:
```
ORA-17957: Unable to initialize the key store
```

or

```
SSO KeyStore not available
```

**Cause:** The Oracle JDBC driver `ojdbc11` cannot read the OCI wallet alone. The wallet contains an auto-login file (`cwallet.sso`) that requires the `oraclepki` library.

**Fix:** This is in `booking-service/pom.xml`. Ensure the dependency is present:

```xml
<dependency>
  <groupId>com.oracle.database.security</groupId>
  <artifactId>oraclepki</artifactId>
</dependency>
```

(Do not add `osdt_core` or `osdt_cert` — they do not exist in the modern Oracle JDBC driver and build will fail.)

Rebuild and redeploy:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
deploy/scripts/build-push.sh
cd deploy/overlays/sandbox && kustomize edit set image ...
oc apply -k deploy/overlays/sandbox
```

### HPA showing `<unknown>/60%` for resource utilization

**Symptom:** `oc describe hpa` shows:
```
queue-gate   Deployment/queue-gate   <unknown>/60%   1         10
```

**Cause:** One of two things:
1. **Transient:** metrics are not yet available. This is normal right after a pod restart. The HPA needs to collect at least two data points before calculating utilization, which takes 60 seconds.
2. **Persistent:** the Deployment is missing CPU *requests*. The HPA calculates utilization as a percentage of the requested CPU, so if there is no request, utilization is undefined.

**Fix (transient):** Wait 60 seconds and check again:

```bash
sleep 60
oc describe hpa queue-gate
```

**Fix (persistent):** Ensure the Deployment has `resources.requests.cpu` set. This is in `deploy/base/queue-gate/deployment.yaml` (or similar). Example:

```yaml
resources:
  requests:
    cpu: 100m
    memory: 512Mi
```

If this was changed, redeploy:
```bash
oc apply -k deploy/overlays/sandbox
```

### Every join returns `SOLD_OUT`

**Symptom:** All requests to `POST /bookings` return HTTP 409 with:
```json
{"reason": "SOLD_OUT"}
```

even though the database shows `seats_taken < capacity`.

**Cause:** The ticket counter in Redis is monotonic and capped at 250. After one load test fills it, a second test without a reset measures nothing — every join finds the counter at 250 and returns `SOLD_OUT` immediately.

**Fix:**
1. Flush the ticket counter:
   ```bash
   oc exec deploy/redis -- redis-cli FLUSHALL
   ```

2. Reset the seats counter in the database:
   ```bash
   oc exec deploy/booking-service -- \
     sqlplus -S booking/"${DB_PASSWORD}"@cv02eb7y952snfi2_low@adb.ap-kulai-2.oraclecloud.com:1522 <<EOF
   UPDATE booking.slots SET seats_taken = 0 WHERE capacity = 250;
   COMMIT;
   EXIT;
   EOF
   ```

Or use the convenience script (which does both):

```bash
deploy/scripts/open-drop.sh
```

### `oc` returns `Unauthorized`

**Symptom:**
```
error: Unauthorized
```

**Cause:** The sandbox token expired. The OpenShift sandbox is temporary; credentials are good for a limited time only.

**Fix:** Fetch a fresh token from the web console:
1. Visit `https://console-openshift-console.apps.rm3.7wse.p1.openshiftapps.com`
2. Click your username → "Copy login command"
3. Paste and run the command in your terminal

This does not affect the running application — it blocks the operator only.

### Pods in `Pending` state

**Symptom:** `oc describe pod <name>` shows:
```
  Type     Reason            Status  Message
  ----     ------            ------  -------
  Warning  FailedScheduling  True    ...insufficient...
```

**Cause:** The project quota has been exceeded. The sandbox grants 3 CPU and 30Gi RAM total. The HPAs can scale up to 1900m (1.9 CPU) across all replicas, but if you manually scale beyond quota, pods will not schedule.

**Fix:** Check the quota:

```bash
oc describe resourcequota compute-deploy
```

And scale back:

```bash
oc scale deploy/queue-gate --replicas=2
oc scale deploy/booking-service --replicas=1
oc scale deploy/redis --replicas=1
```

---

## Sandbox Expiry and Renewal

OpenShift sandboxes expire periodically (typically after 48 hours of inactivity). When the sandbox renews:

- **The cluster URL changes:** `api.rm3.7wse.p1.openshiftapps.com` is a placeholder; renewal may assign a different endpoint.
- **The project namespace changes:** `marwanbukhori-dev` is just an example; it may be `marwanbukhori-sandbox-xyz` after renewal.
- **All Secrets are lost:** you must run `create-secrets.sh` again.
- **All Deployments are lost:** you must reapply the manifests.

To handle renewal:

1. Log into the new sandbox with the fresh token from the console.

2. Update the cluster URL and namespace in `deploy/overlays/sandbox/kustomization.yaml`:

   ```yaml
   namespace: <new-namespace>
   ```

3. Update any hardcoded references in scripts if the cluster endpoint changed. For now, this is documented in this runbook but not in scripts.

4. Recreate the secrets:

   ```bash
   export DB_PASSWORD="<password>" WALLET_DIR="$HOME/rembayung-wallet"
   deploy/scripts/create-secrets.sh
   ```

5. Redeploy:

   ```bash
   oc apply -k deploy/overlays/sandbox
   ```

---

## Important Caveats

- **The deployment is stateful:** the Oracle database and Redis are outside the cluster and carry state across restarts. Flushing Redis or changing the database requires manual steps.
- **Secrets are imperative:** the wallet and credentials are not in git and cannot be recreated from the repository alone. Back up the wallet file separately.
- **Pre-scaling is manual:** a real production setup would use a CronJob to adjust `minReplicas` before known traffic spikes. This phase uses manual scaling for simplicity.
- **No CI/CD yet:** code changes require a manual build and tag. Phase 4 adds GitHub Actions and Jenkins.

---

## Quick Reference

**Authenticate:**
```bash
oc login api.rm3.7wse.p1.openshiftapps.com --token=<token>
```

**First-time deploy:**
```bash
export DB_PASSWORD="..."
deploy/scripts/create-secrets.sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
deploy/scripts/build-push.sh
cd deploy/overlays/sandbox && kustomize edit set image ... && cd ../../..
oc apply -k deploy/overlays/sandbox
oc rollout status deploy/queue-gate --timeout=180s
```

**Check status:**
```bash
oc get pods
oc get route queue-gate
oc describe hpa queue-gate
```

**View logs:**
```bash
oc logs deploy/queue-gate -f --tail=50
oc logs deploy/booking-service -f --tail=50
```

**Scale manually:**
```bash
oc scale deploy/queue-gate --replicas=10
```

**Redeploy:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
deploy/scripts/build-push.sh
cd deploy/overlays/sandbox && kustomize edit set image ... && cd ../../..
oc apply -k deploy/overlays/sandbox
oc rollout status deploy/queue-gate --timeout=180s
```

**Run a demo:**
```bash
deploy/scripts/open-drop.sh
oc scale deploy/queue-gate --replicas=10
oc scale deploy/booking-service --replicas=4
k6 run --vus 1500 --duration 30s loadtest/drop.js
# then check the database for the true seat count
oc exec deploy/booking-service -- sqlplus -S booking/"$DB_PASSWORD"@...
```

# 05 — Debugging on OpenShift

Written from the failures actually hit while deploying this project, not from a
tutorial. Every symptom below is one we saw, and every diagnosis is the one that
turned out to be right — including the two where the first answer was wrong.

---

## The method

Most OpenShift debugging is answering one question: **how far did it get?**

A workload passes through six gates. Find the first one it failed, and you have
the bug. Working from the top down saves you from theorising about layer six
when the problem is at layer two.

```
1. Scheduled?    is there a pod at all, or is it Pending?
2. Image pulled? or ImagePullBackOff / ErrImagePull
3. Container created?  or CreateContainerConfigError
4. Process started?    or CrashLoopBackOff
5. Ready?              or 0/1 Running — probes failing
6. Reachable?          or the Route/Service does not lead to it
```

One command tells you which gate you are at:

```bash
oc get pods
```

| What you see | Failed at | Look at |
|---|---|---|
| `Pending` | scheduling | `oc describe pod` → Events → quota or resources |
| `ImagePullBackOff` | pull | tag, registry visibility, pull secret |
| `CreateContainerConfigError` | container creation | a missing Secret or ConfigMap |
| `CrashLoopBackOff` | process start | `oc logs` — the app started and died |
| `0/1 Running` | readiness | `oc describe pod` → probe failures |
| `1/1 Running` but calls fail | reachability or logic | Service ports, Routes, NetworkPolicy, app logs |

**`oc describe pod` is the workhorse.** Its Events section at the bottom is
usually the whole answer for gates 1–3.

---

## The eight failures we actually hit

### 1. `CrashLoopBackOff` with `exec format error`

**Cause:** an arm64 image on an x86_64 cluster. Built on an Apple Silicon Mac
with a plain `docker build`.

**Diagnosis:** `oc logs` shows `exec format error` and nothing else — the
container never ran a line of your code.

**Fix:** `docker buildx build --platform linux/amd64`, and verify before
deploying:

```bash
docker buildx imagetools inspect ghcr.io/you/image:tag | grep -i platform
```

**Worth internalising:** check the architecture *before* deploying. Two seconds
of `imagetools inspect` versus an hour of a `CrashLoopBackOff` whose logs say
nothing useful.

### 2. `CreateContainerConfigError`

**Cause:** the pod references a Secret or ConfigMap that does not exist. In our
case `oracle-credentials`, before it had been created.

**Diagnosis:** this one names itself.

```bash
oc describe pod -l app=booking-service | grep -A3 -i secret
```

Shows exactly which object is missing.

**Fix:** create it. Note that `oc logs` gives you *nothing* here — the container
was never created, so there is nothing to log. Reaching for logs first is the
common mistake.

### 3. `CrashLoopBackOff` with `ORA-17957: SSO KeyStore not available`

**Cause:** the Oracle wallet ships `cwallet.sso`, an auto-login format that
`ojdbc11` cannot read alone. The `SSO` KeyStore type lives in a separate
artifact.

**Diagnosis:** `oc logs` had the full stack trace. The useful line was four
`Caused by:` levels down — read to the bottom of a stack trace, not the top.

**Fix:** add `com.oracle.database.security:oraclepki`.

**The trap:** most Oracle documentation also tells you to add `osdt_core` and
`osdt_cert`. Those **do not exist at 23.x** — they were folded into `oraclepki`.
Following that advice fails the build. Verify a dependency exists before
believing advice about it:

```bash
mvn dependency:get -Dartifact=group:artifact:version
```

### 4. HTTP 500 on every request — Redis `MISCONF`

**Cause:** the design gives Redis no volume, because queue state is disposable.
But nothing told *Redis* that. Its default save points stayed active, it tried
to snapshot to `/data`, OpenShift's restricted SCC denied the write, and Redis
then **refused all writes** — `stop-writes-on-bgsave-error` is the default.

**Diagnosis:** the app logs named it exactly. The lesson is where we looked:

```bash
oc logs deploy/queue-gate --tail=60 | grep -A6 -i exception
oc logs deploy/redis --tail=20            # ← the dependency's own logs
oc exec deploy/redis -- redis-cli CONFIG GET save
```

**When a service fails, read its dependencies' logs too.** The gate's error
named Redis; Redis's own log had `Permission denied` and `Background saving
error`, which is the actual root cause.

**Fix:** `command: [redis-server, --save, "", --appendonly, "no"]` — make the
manifest state the intent instead of leaving a default fighting it.

**Why it is nasty:** it survives light use and only appears under load, once a
save threshold trips. Exactly the failure that waits for an audience.

### 5. HPA showing `<unknown>/60%`

**Cause:** usually transient right after a rollout, while metrics are still
being collected.

**Diagnosis:**

```bash
oc get hpa                # TARGETS column
oc adm top pods           # does the metrics API answer at all?
```

If `oc adm top pods` works but the HPA still says unknown after a minute, the
pods are missing CPU **requests** — utilisation is a percentage of the request
and is undefined without one.

**Worth knowing:** an HPA showing `<unknown>` will never scale. On a demo built
around autoscaling, that is a silent failure of the centrepiece.

### 6. `error: You must be logged in to the server (Unauthorized)`

**Cause:** the sandbox token expired. They are short-lived.

**Fix:** console → your name → *Copy login command* → *Display Token*.

**The distinction that matters:** this blocks **you**, not the application. Your
pods keep serving traffic throughout. If it happens mid-demo, say so — an
expired CLI token is not an outage.

### 7. `ORA-00942: table or view does not exist`

**Cause:** connected as `ADMIN`, but the tables belong to the `BOOKING` schema.
Unqualified names resolve against your own schema.

**Fix:** qualify them — `booking.slots` — or `ALTER SESSION SET CURRENT_SCHEMA =
booking;` once per session.

**Worth noting:** this is least privilege working as intended. The application
user owns its own schema and `ADMIN` is not used for application work.

### 8. A load test that failed 86% with zero application errors

This one is the most instructive, because **the first diagnosis was wrong.**

**The wrong answer:** "the shared ingress is saturating and dropping return
traffic." Plausible, tidy, and inferred from connection errors without checking
anything.

**What was actually true:** two independent faults, conflated into one story.
Fault one was the Redis `MISCONF` above. Fault two was a genuine ingress
ceiling — but it was only established by *measuring* it:

```bash
# Offer N virtual users; count how many requests actually landed in Redis.
for V in 200 1000 3000; do
  oc exec deploy/redis -- redis-cli SET queue:ticket 0
  k6 run -e VUS=$V ladder.js
  oc exec deploy/redis -- redis-cli GET queue:ticket   # ← how many arrived
done
```

| Offered | Arrived | Failed |
|---|---|---|
| 200 | 200 | 0% |
| 1000 | 662 | 75% |
| 3000 | 818 | 92% |

Throughput plateaus at roughly 600–800 regardless of offered load, and the
application logs zero errors at every level. *That* is evidence of an edge
connection cap. The earlier story was a guess that happened to point at the
right component for the wrong reason.

**The technique worth stealing:** to find out whether requests are arriving,
count them somewhere the server controls — a Redis counter, a metric, a log
line. Client-side failure counts cannot distinguish "never arrived" from
"arrived and the answer was lost".

---

## Things that are true generally

### Read the logs before forming a theory

Every wrong diagnosis in this project came from reasoning about what *should*
be happening instead of reading what *was*. Both times, the answer was already
sitting in a log we had not opened.

### The client is not the source of truth

Our load test reported 28 bookings while the database held 73. A dropped
response after a committed transaction looks like a failure and is not one.
When a client and a database disagree about what happened, **the database is
right**.

```sql
SELECT capacity, seats_taken FROM booking.slots;
SELECT COUNT(*) FROM booking.slots WHERE seats_taken > capacity;  -- must be 0
```

### Verify at the layer below before blaming the layer above

The gate looked broken. Redis was broken. Redis looked broken. The filesystem
permission was the cause. Each step down was one command.

### "It worked, then it stopped" usually means state, not code

Nothing about the application changed between the working run and the failing
one. Redis had accumulated enough writes to trip a snapshot. Look for
counters, disks, tokens and caches — things that fill up or expire.

---

## Commands worth memorising

```bash
# Where did it get to?
oc get pods
oc describe pod <name>              # Events section is at the bottom
oc logs deploy/<name> --tail=50
oc logs deploy/<name> --previous    # the crashed container, not the new one

# Is it reachable, and from where?
oc get svc,route
oc get networkpolicy
oc exec deploy/queue-gate -- curl -s http://booking-service:8081/actuator/health

# What is it actually configured with?
oc set env deploy/<name> --list
oc get configmap <name> -o yaml
oc describe pod <name> | grep -A5 Mounts

# Is it resource-starved?
oc describe resourcequota
oc adm top pods
oc get hpa

# Prove connectivity from inside the cluster
oc run probe --rm -i --restart=Never --image=registry.access.redhat.com/ubi9/ubi-minimal \
  --command -- timeout 10 bash -c '</dev/tcp/host/port && echo REACHABLE || echo UNREACHABLE'
```

That last one retired the biggest unknown in this phase — whether the cluster
could reach Oracle Cloud at all — in about fifteen seconds, before any manifest
depended on it. **Prove the risky assumption first, cheaply.**

---

## What I would tell someone starting out

1. **`oc get pods` then `oc describe pod`.** Most answers are in the Events
   section, and you have not earned a theory until you have read them.
2. **`oc logs --previous`** for a crash loop, or you are reading the container
   that has not failed yet.
3. **Check the dependency's logs**, not just the service that reported the
   error. The reporter is rarely the cause.
4. **Measure before concluding.** "The ingress is saturated" became true only
   after counting how many requests arrived. Before that it was a guess wearing
   a confident voice.
5. **When a demo breaks, ask what accumulated.** Code does not rot between two
   runs; state does.

# 07 — Continuous Delivery: how CI, Ansible and OpenShift actually connect

**Covers:** what CD really does (it is not what most people assume), why Ansible
rather than a one-line `oc set image`, and where the image bytes actually travel.

---

## The thing worth getting right first

The intuitive model of CD is:

> CI builds an image and pushes it to a registry. CD **downloads that image and
> puts it on the cluster.**

The second half is wrong, and the design only makes sense once it is corrected.

**CD never touches the image.** It never downloads it, never uploads it, never
holds a byte of it. What CD changes is a **single string** in one Kubernetes
object:

```yaml
spec:
  template:
    spec:
      containers:
        - name: booking-service
          image: ghcr.io/marwanbukhori/booking-service:4573e8f0a692...
                                                       ^^^^^^^^^^^^
                                          this is the entire deployment
```

Everything else follows from Kubernetes noticing that string changed.

### Proof, from this cluster

```
kubelet  Successfully pulled image
         "ghcr.io/marwanbukhori/booking-service:4573e8f0a692..."
         in 1.509s. Image size: 410073262 bytes
```

410 MB, pulled by the **kubelet** — the agent on the cluster node. The Ansible
playbook that triggered this deploy ran on a laptop and transferred none of it.
Had it run on a GitHub Actions runner, the same would be true.

The image travels **registry → node**, once, directly. The control path and the
data path are different paths.

---

## The whole chain

```
  git push
     │
     ├─ CI  (.github/workflows/ci.yml, GitHub Actions)
     │    runs 36 + 38 tests against real Oracle and real Redis
     │    builds two images
     │    pushes them to ghcr.io tagged with the commit SHA
     │    ── produces: an immutable artifact and a NAME for it
     │
     ├─ CD  (Ansible, deploy/ansible/)
     │    reads what each Deployment is running now
     │    writes the new tag into the Deployment's image field   ← the whole job
     │    waits for Kubernetes to finish
     │    smoke-tests the public Route
     │    puts the old tags back if any of that fails
     │
     └─ OpenShift  (does the actual work)
          sees spec changed  →  metadata.generation increments
          creates a new ReplicaSet
          schedules new pods
          kubelet pulls the image from ghcr.io          ← the bytes move here
          new pod passes its readiness probe
          only THEN is an old pod terminated
```

Ansible's part is the middle box, and the middle box moves no data. It makes a
decision, records it in the cluster's desired state, and then supervises.

---

## Why the rollout is safe, and why that is a property of the manifests

The Deployments specify `maxUnavailable: 0`, so Kubernetes may not remove a
working pod until a replacement is Ready.

**They did not always.** This note originally claimed the manifests specified
`maxUnavailable: 25%`, which on 2 replicas rounds down to zero and gives the
same behaviour. That was wrong in a way worth recording: there was no
`strategy:` block anywhere in `deploy/` at all. 25% is the *API default* — a
value nobody here chose, nobody controls, and which stops giving zero the moment
the replica count reaches 5, where 25% rounds to 1. The property the whole phase
demonstrates was resting on an accident. It is now pinned explicitly.

Consequence: **a bad image is a non-event.** Deploy a tag that does not exist
and the new pod sits in `ImagePullBackOff` forever, never becomes Ready, and
therefore never earns the right to replace anything. The old pods keep serving.
The deploy fails; the service does not.

That is why the rollback test in this phase is safe to run on a live cluster,
and it is worth saying out loud in a demo. The rollback is not what keeps the
site up during a bad deploy — `maxUnavailable: 0` already did that. The rollback
is what stops the cluster *sitting* in a half-changed state afterwards.

---

## Why Ansible, when `oc set image` is one line

It genuinely is one line:

```bash
oc set image deploy/booking-service booking-service=ghcr.io/.../booking-service:<sha>
```

That command deploys. It does not deliver. Everything that makes the difference
is what it does not do:

| | `oc set image` | the playbook |
|---|---|---|
| Knows what was running before | no | reads it, per Deployment |
| Waits for the rollout | no, returns instantly | waits on the generation-guarded predicate |
| Checks the app actually works | no | smoke-tests the public Route |
| Undoes itself on failure | no | restores each service to its own previous tag |
| Fails loudly and non-zero | no | yes, naming both tags |
| Refuses a nonsense input | no | asserts the tag and the Route first |

The playbook is a decision procedure. `oc set image` is one step inside it.

### Why `kubernetes.core.k8s` and not shelling out to `oc`

`kubernetes.core` is Red Hat's own Ansible collection and talks to the
Kubernetes API directly. Two things follow that matter:

**It is declarative in the same way the manifests are.** Re-running with the
same tag is a genuine no-op — measured: `changed=0`, with `booking-service`
still at generation 9 and `queue-gate` at 68. Those are the two the playbook
manages; `redis` also sits in the namespace, but the playbook never touches it,
so its generation is not evidence of anything. A shell-out would have to parse
text to know any of this.

**The playbook is portable to Ansible Automation Platform unchanged.** That is
the production story: the same role, run by an AAP Job Template with a managed
inventory and credentials, instead of by a laptop or a runner.

---

## Where it runs, and why not in the cluster

The playbook runs on `localhost` — the machine invoking it. There is no SSH and
no managed host list; the "host" is the Kubernetes API endpoint, reached over
HTTPS.

In production this would be an AAP Job Template. It is not, here, for a measured
reason: the sandbox arrived with an AAP stack consuming **1950m of the 3000m**
CPU quota, and it had to be deleted before the autoscaling demonstration could
run at all. The demonstration was the point, so AAP went.

That is also why the original Jenkins plan was dropped. Jenkins was chosen
because CD needs a host with network reach into the cluster — and a GitHub
Actions runner already is one, so the server was solving a problem that had
stopped existing.

---

## Why CI and CD are separate workflows

Because **rollback must not require a rebuild.**

If deploying were the last step of CI, reverting would mean pushing a revert
commit and waiting out a full test-and-build cycle. With them separate, CD can
be re-run against any tag that was ever published — which is what you want at
21:05 on a drop night, not a 2.5-minute rebuild.

It has a second benefit: a failed *deploy* does not turn the *build* red, which
would be a lie about the code.

---

## Immutable tags are what make any of this possible

Every image is tagged with its full commit SHA. There is no `latest`.

This is not stylistic. Three things depend on it:

**Rollback needs a name to go back to.** "The previous image" is only a thing
you can deploy if it has a stable identifier.

**Nothing drifts.** `imagePullPolicy: IfNotPresent` plus an immutable tag means
a pod that restarts comes back as the *same* code. With `latest`, an unrelated
restart at 21:00 could silently pull something new.

**A deploy is inert until someone decides.** Publishing a new SHA changes
nothing on the cluster — no pod is watching the registry. That is why CI can
publish freely and the cluster only moves when CD says so.

The cost is that you can never retype a tag from memory. CI publishes the full
40-character SHA and `deploy/scripts/build-push.sh` publishes the 7-character
one; ask for the wrong form and you get `manifest unknown`. Copy the tag from
the run summary. The playbook takes it as a required argument and never derives
it, for exactly this reason.

---

## What the playbook does, in order

```
discover.yml   assert image_tag was supplied          ← before anything
               read each Deployment's current tag     ← per service, they can differ
               read and assert the public Route
apply.yml      patch both Deployments' image field
               wait for both rollouts
smoke.yml      POST /queue, GET /queue/{token}
               confirm booking-service has ready replicas
rollback.yml   (only on failure) restore each service's own previous tag,
               re-verify, then fail loudly naming both
```

Everything read-only or assertable happens **first**, so a bad input stops the
play before any Deployment has been touched — verified: a wrong Route name exits
2 with `changed=0` and all generations unchanged. The Route is read and asserted
in `discover.yml`, not in `smoke.yml` where it is actually used, because an
earlier version read it there instead — after `apply.yml` had already patched
both Deployments — and a mistyped Route name would have failed the play with a
new tag live and unverified, and no rollback, because that failure happened
while evaluating a fact rather than through the rollback path.

---

## The smoke test does not book, deliberately

It calls `POST /queue` and `GET /queue/{token}`, never `POST /bookings`.

Seats come from a finite 250-seat slot. A smoke test that booked would drain
real inventory on every single deploy. Verified across a full run:
`seats_taken` was 202/250 before and after.

**The honest cost:** a deploy can pass its smoke test while booking is broken.
The gap is narrower than it sounds — `booking-service`'s readiness probe already
covers the datasource, and the playbook checks it has ready replicas — but it is
real, and it is a deliberate trade rather than an oversight.

One smaller cost: each deploy leaves one unused token in Redis, advancing the
ticket counter by one against the drop's 250-ticket cap. The documented
procedure flushes Redis before any drop or load test, which resets it.

---

## Why the ServiceAccount cannot read Secrets, verified

The identity GitHub Actions deploys with (`rembayung-cd`, in
`deploy/openshift/cd-serviceaccount.yaml`) is a namespace-scoped ServiceAccount,
not a human's login token. A human token carries the human's whole namespace
authority, including the Oracle wallet; this one has exactly the verbs the
playbook issues and nothing else, so a leaked repository secret cannot reach
anything the playbook does not already touch.

Checked directly against the cluster with `oc auth can-i --as=system:serviceaccount:marwanbukhori-dev:rembayung-cd`:

| Allowed | Denied |
|---|---|
| `patch deployments` | `get secrets` |
| `get routes` | `delete deployments` |
| | `create pods` |
| | `create pods/exec` |
| | patch outside the namespace |

No `create pods` and no `create pods/exec` matter as much as no `get secrets`
does: even a compromised token cannot open a shell in a running container or
read the wallet and database credentials mounted next to it.

The Role originally also granted `get`/`list`/`watch` on pods and services,
with a comment claiming the playbook "checks pod status." That comment was
false — there is no reference to kind `Pod` or `Service` anywhere in the role;
`smoke.yml` reads `readyReplicas` off the Deployment's own status, which the
`deployments` grant already covers. Both were removed. A false comment
justifying an over-broad grant is worse than the grant itself, because it is
exactly the kind of comment that stops the next person from re-examining it.

---

## The `workflow_run` name trap

`cd.yml` triggers on:

```yaml
on:
  workflow_run:
    workflows: [ci]
    types: [completed]
```

`workflows: [ci]` matches on `ci.yml`'s `name: ci` field, not its filename, and
the match is case-sensitive. Writing `[CI]` here parses as valid YAML, raises
no error anywhere, and simply never fires — there is no failed run, no log
line, nothing to grep for. The only symptom is that CD silently never starts
after a successful CI run, which looks identical to "nobody pushed to `main`
yet."

`types: [completed]` also fires on a CI run that *failed*, so the job carries
an explicit `if: ... workflow_run.conclusion == 'success'` guard. Omitting it
would deploy a commit CI just rejected.

The tag comes from `workflow_run.head_sha`, not `github.sha`. On a
`workflow_run` event, `github.sha` points at the current head of the default
branch, which can differ from the commit CI actually built if another push
landed in between. `head_sha` is pinned to the run that triggered CD.

---

## Why the timeout is 40 minutes, not 20

The rollout wait in `apply.yml` runs in a per-service loop over
`rembayung_services`, so each phase of the play costs up to
`2 × rembayung_rollout_timeout` (2 × 300s = 600s), not one wait of 300s.

Measured on the live rollback test below: the apply phase's two waits took
302s and 303s — 605s, against a 600s theoretical ceiling for that phase alone.
A worst-case run pays that once failing, then again rolling back, plus smoke
retries on both paths — comfortably past 20 minutes.

A 20-minute cap would kill the job mid-rollback on exactly the run where the
rollback matters: the workflow would be terminated by GitHub before
`rollback.yml` finished, leaving the cluster in the half-changed state this
whole phase exists to prevent. `timeout-minutes: 40` is sized off the measured
number, not a round guess.

---

## The rollback test, run against the live cluster

The test: deploy an all-zeros tag —
`ghcr.io/marwanbukhori/<svc>:0000000000000000000000000000000000000000` — an
image that cannot exist, and watch the playbook fail and recover on its own.

**Why the wait had to be right before this test meant anything.** An earlier
version of `apply.yml` used
`wait_condition: {type: Progressing, reason: NewReplicaSetAvailable}`. That
condition is a stateless snapshot match, and `kubernetes.core`'s waiter issues
its first GET immediately — `waiter.py`'s `clock()` yields `0` before any sleep
— so it can poll before the Deployment controller has reconciled the patch.
Worse, `NewReplicaSetAvailable` is also the reason left behind by the
*previous successful rollout* — the same string, unchanged, still sitting on
the object. A poll that lands in that window reports a deploy as complete
before it has started, which would have made this rollback test pass against a
tag that was never actually running. The fix was bare `wait: true` with no
`wait_condition`, which selects the collection's built-in `deployment_ready`
predicate: it additionally requires `status.observedGeneration ==
metadata.generation`. A stale status always carries the old generation, and a
generation always bumps on a spec change, so a pre-reconcile snapshot
structurally cannot satisfy the predicate — there is no window left for the specific race that bit us.

To be precise about how strong that claim is: `deployment_ready` checks
`spec.replicas == status.replicas`, `availableReplicas == replicas`,
`observedGeneration == metadata.generation` and `not unavailableReplicas`. It
does **not** check `updatedReplicas`. `observedGeneration` proves the controller
has seen the patch, which is what kills the stale-condition race — it does not
prove every surge pod is already reflected in status. The live test waited the
full 302s and 303s rather than returning early, so the fix works where it
mattered; "structurally cannot match a stale status" is accurate about
generation and would be overstating it about everything else.

**What actually happened, measured:**

- The two rollout waits in `apply.yml` (one per service) each ran to their full
  budget rather than returning early: **302s and 303s** against the 300s
  `rembayung_rollout_timeout`. Neither pod ever became Ready — both sat in
  `ImagePullBackOff`, which cannot resolve — so the timeout firing, not a false
  early success, is the correct outcome.
- Total wall clock for the run, apply-and-fail through rollback-and-verify:
  **628s**.
- Exit code: **2**.
- The final message, naming the failed tag, the restored tags, and the health
  of both halves of the recovery:

  ```
  Deploy of 0000… failed: rollout did not complete within 300s for tag 0000….
  Rolled back to {'booking-service': '4573e8f0…', 'queue-gate': '4573e8f0…'}.
  Rollback rollout: ok. Rollback smoke: ok.
  ```

Each service was restored to *its own* previous tag — the per-service
`current_tags` map doing exactly the job it was built for — and both the
rollback's own rollout wait and its post-rollback smoke check passed before the
play failed loudly.

---

## Zero downtime is measured, not claimed

It is one thing to reason from the manifests that a bad deploy cannot take the
service down (see "Why the rollout is safe" above); it is another to watch it
not happen.

**Captured while the failed deploy above was in flight:** both Deployments
already pointed at the all-zeros tag, both new pods were in
`ImagePullBackOff`, and `POST /queue` through the public Route returned
**HTTP 200 three times in a row**. The Service's endpoints stayed populated
with the old, working pods throughout — nothing was ever removed from them.

**Afterwards:** all five pods across both Deployments showed **0 restarts**
and ages of **70–73 minutes** — the pods that were serving before the bad
deploy were never terminated. Nothing crashed and nothing was replaced; the
new ReplicaSets simply never earned the right to take over.

The cause is `maxUnavailable: 0`: Kubernetes cannot evict a working pod for a
replacement that never becomes Ready. (At the time of this test the value was
still the 25% API default, which on 2 replicas rounds down to zero and behaved
identically. It has since been pinned, for the reason given earlier.)

**This is worth separating from rollback explicitly, because conflating the
two is the natural mistake to make watching this test.** `maxUnavailable: 0`
is what keeps the site answering requests *during* a bad
deploy — that guarantee held with the bad tag still applied and the rollback
not yet run. Rollback is what stops the cluster from *sitting* in that
half-changed state afterwards, with a dead ReplicaSet parked next to a live
one and no record of what should be running. They are two different
guarantees, enforced by two different mechanisms, and this test is the first
time both were checked at once rather than assumed from reading the YAML.

---

## The readiness tradeoff: total outage vs. partial degradation

`queue-gate`'s readiness probe checks the Spring Boot readiness group
`readinessState,redis` — it is not Ready unless Redis answers. This is
correct given what the service does: `queue-gate` cannot serve *any* endpoint
without Redis, since the ticket counter and token state live there. Reporting
unready in that case is an honest signal, not a bug.

The cost is that a Redis outage removes **every** `queue-gate` replica from
the Service's endpoints simultaneously, rather than degrading gradually. This
happened: Redis drifted to 0 replicas, and the public Route returned 503 for
roughly **9 hours** before anyone noticed, because nothing here alerts. Once
Redis came back, recovery was fast and clean — about **70s**, **zero
restarts** on the `queue-gate` pods, which simply started passing readiness
again as soon as their dependency did.

Put the two properties side by side rather than picking one: an
all-or-nothing readiness gate produces a total, self-healing outage the moment
its one hard dependency is gone, where a looser gate would have produced
partial, harder-to-diagnose degradation instead — requests failing at the
booking step rather than the queue step, for a service that cannot actually
complete either without Redis. The gate's behavior was correct here. The
missing alerting is the actual gap, not the readiness check.

---

## What is not finished

Automatic deploys **have not fired yet.** CD is dispatch-only right now. Two
things are required before `workflow_run` can trigger it on its own:

1. The repository owner creates a token for the `rembayung-cd` ServiceAccount
   and stores it as the `OPENSHIFT_TOKEN` repository secret — a credential,
   so it is not something an implementer generates and commits on someone
   else's behalf.
2. This branch merges to `main`, because `workflow_run` only fires for
   workflows defined on the repository's default branch.

Until both are true, every deploy documented above was run by hand — either
via `workflow_dispatch` or by invoking the playbook directly — and that is the
only way CD has been exercised. This is a known gap, not a finished pipeline.

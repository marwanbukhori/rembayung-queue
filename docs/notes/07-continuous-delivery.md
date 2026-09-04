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

The Deployments specify `maxUnavailable: 25%` on 2 replicas. 25% of 2 is 0.5,
which rounds **down to zero**. So Kubernetes may not remove a working pod until
a replacement is Ready.

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
same tag is a genuine no-op — measured: `changed=0`, and all three Deployment
generations unchanged. A shell-out would have to parse text to know that.

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
2 with `changed=0` and all generations unchanged.

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

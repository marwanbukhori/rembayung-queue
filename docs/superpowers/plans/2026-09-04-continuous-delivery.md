# Continuous Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Ansible playbook that deploys a named image tag to OpenShift, waits for the rollout, smoke-tests the public Route, and rolls back to the previously running tag when either fails.

**Architecture:** One role (`rembayung`) invoked by one play. It discovers the tag each Deployment is currently running, applies the new one through `kubernetes.core.k8s`, waits, smoke-tests, and on failure re-applies the discovered tags. A second GitHub Actions workflow triggers it on CI success; the same playbook runs unchanged from a laptop.

**Tech Stack:** Ansible 9+, `kubernetes.core` collection, Kustomize, OpenShift 4, GitHub Actions

**Spec:** [`docs/superpowers/specs/2026-09-04-continuous-delivery-design.md`](../specs/2026-09-04-continuous-delivery-design.md)

## Global Constraints

Copied from the spec. Every task's requirements implicitly include these.

- **`image_tag` is required and never derived.** No default, no `git rev-parse`. The play fails immediately if it is absent. CI publishes full 40-character SHAs and `build-push.sh` publishes 7-character ones; deriving is how a deploy targets an image that does not exist.
- **`rollback_tag` is read from the live Deployment, and is one value per Deployment.** `booking-service` and `queue-gate` are currently on *different* tags (`66393c1` and `270288f`). A single cluster-wide rollback tag would restore one service to the other's tag.
- **The smoke test must not consume seat inventory.** It exercises `POST /queue` and `GET /queue/{token}` only. It never calls `POST /bookings`.
- **Rollback is a re-apply of a known tag, never `oc rollout undo`.** `undo` depends on retained ReplicaSets, which a pruned history can lose.
- **A failed rollback fails loudly**, ending the play non-zero and naming both tags.
- **The ServiceAccount gets no access to Secrets.** The Oracle wallet and database credentials are human-managed and never touched by CD.
- **Namespace is `marwanbukhori-dev`**, cluster `https://api.rm3.7wse.p1.openshiftapps.com:6443`, public Route host `queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com`. The sandbox expires around 2026-10-01 and all three change on renewal, so none is hardcoded outside `defaults/main.yml`.
- **Commit messages contain no AI attribution.** No `Co-Authored-By`, no `Claude-Session`, no claude.ai URL, no "Generated with", no robot emoji, no mention of an assistant, model or session. This is a hard standing rule of the repository owner and was violated once already in this project, requiring a history rewrite. After each commit, run `git log -1 --format=%B` and amend if anything but your own text is present.

### Three things that will surprise you

**Ansible is not installed on this machine.** Task 1 installs it. `python3` is 3.14.7 and Homebrew is present.

**`kubernetes.core` needs a Python library, not just the collection.** `ansible-galaxy collection install kubernetes.core` gets the modules; they fail at runtime without `pip install kubernetes`. The error names `kubernetes` but appears only when a task runs, so `--syntax-check` passes and the first real run fails.

**The cluster is live and serving.** `booking-service:66393c1` and `queue-gate:270288f` are running with real pods and a working end-to-end path. Any task that leaves the cluster broken is a failure, including the rollback test in Task 4 — which must end with the cluster back on working images.

---

## File Structure

```
deploy/ansible/
├── deploy.yml                      the play: one host, one role, no logic
├── inventory.ini                   localhost, connection=local
├── requirements.yml                the kubernetes.core collection pin
└── roles/rembayung/
    ├── defaults/main.yml           namespace, route host, timeouts, service list
    └── tasks/
        ├── main.yml                orchestration only — includes the four below
        ├── discover.yml            validate image_tag; read current tags per Deployment
        ├── apply.yml               set images, apply, wait for rollout
        ├── smoke.yml               exercise the queue path through the public Route
        └── rollback.yml            re-apply discovered tags, re-verify, fail loudly

.github/workflows/cd.yml            triggers on CI success; runs the playbook

deploy/openshift/cd-serviceaccount.yaml   ServiceAccount + Role + RoleBinding

docs/notes/07-continuous-delivery.md      why this shape, and what it cost
```

Splitting `tasks/` by phase rather than keeping one file is deliberate: `main.yml` becomes a readable four-line summary of what a deploy *is*, and a reviewer can reject `smoke.yml` without re-reading the apply logic.

---

## Task 1: Playbook skeleton and tag discovery

Read-only against the cluster. Produces a play that refuses to run without a tag and correctly reports what is currently deployed.

**Files:**
- Create: `deploy/ansible/deploy.yml`
- Create: `deploy/ansible/inventory.ini`
- Create: `deploy/ansible/requirements.yml`
- Create: `deploy/ansible/roles/rembayung/defaults/main.yml`
- Create: `deploy/ansible/roles/rembayung/tasks/main.yml`
- Create: `deploy/ansible/roles/rembayung/tasks/discover.yml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the fact `current_tags`, a dict mapping deployment name to its running tag, e.g. `{"booking-service": "66393c1", "queue-gate": "270288f"}`. Tasks 2 and 4 read it. Also `rembayung_services`, the list `["booking-service", "queue-gate"]`, and `rembayung_namespace`.

- [ ] **Step 1: Install Ansible and its Kubernetes dependency**

```bash
brew install ansible
python3 -m pip install --user kubernetes
ansible --version
```

Expected: Ansible 9 or newer. If `pip` refuses with "externally-managed-environment", use `python3 -m pip install --user --break-system-packages kubernetes` — this is a user-level install on a developer laptop, not a system package.

- [ ] **Step 2: Create the collection requirements file**

`deploy/ansible/requirements.yml`:

```yaml
---
collections:
  - name: kubernetes.core
    version: ">=3.0.0"
```

Install it:

```bash
ansible-galaxy collection install -r deploy/ansible/requirements.yml
```

- [ ] **Step 3: Create the inventory**

`deploy/ansible/inventory.ini`:

```ini
# The playbook talks to the cluster's API over HTTPS, not to managed hosts over
# SSH. localhost is the only "host" involved; kubernetes.core does the rest.
[local]
localhost ansible_connection=local
```

- [ ] **Step 4: Create the role defaults**

`deploy/ansible/roles/rembayung/defaults/main.yml`:

```yaml
---
# Everything here changes when the sandbox is renewed (expected around
# 2026-10-01). Nothing below should appear hardcoded anywhere else.
rembayung_namespace: marwanbukhori-dev
rembayung_route_name: queue-gate
rembayung_registry: ghcr.io/marwanbukhori

# Both Deployments carry their own image tag and can legitimately differ.
rembayung_services:
  - booking-service
  - queue-gate

# A rollout on this cluster takes well under a minute; 300s is slack for a slow
# image pull, not an expectation.
rembayung_rollout_timeout: 300

# Route admission and pod readiness are not instantaneous after an apply.
rembayung_smoke_retries: 10
rembayung_smoke_delay: 6
```

- [ ] **Step 5: Create the play**

`deploy/ansible/deploy.yml`:

```yaml
---
- name: Deploy Rembayung to OpenShift
  hosts: local
  gather_facts: false
  roles:
    - rembayung
```

- [ ] **Step 6: Create the orchestration file**

`deploy/ansible/roles/rembayung/tasks/main.yml`:

```yaml
---
# What a deploy is, in four lines. Each include is one phase and is readable on
# its own. rollback.yml is invoked from apply.yml and smoke.yml on failure, not
# from here, because it must run only when something has already gone wrong.
- name: Discover current state and validate inputs
  ansible.builtin.include_tasks: discover.yml
```

Tasks 2, 3 and 4 append to this file.

- [ ] **Step 7: Write the discovery tasks**

`deploy/ansible/roles/rembayung/tasks/discover.yml`:

```yaml
---
# image_tag has no default on purpose. CI publishes full 40-character SHAs and
# deploy/scripts/build-push.sh publishes 7-character ones; a derived tag would
# silently target an image that does not exist. Fail before touching anything.
- name: Require an explicit image_tag
  ansible.builtin.assert:
    that:
      - image_tag is defined
      - image_tag | length > 0
    fail_msg: >-
      image_tag is required and is never derived.
      Pass it explicitly, e.g.
      ansible-playbook deploy/ansible/deploy.yml -e image_tag=<sha>
      Copy the tag from the CI run summary or from build-push.sh output.

- name: Read the image currently running on each Deployment
  kubernetes.core.k8s_info:
    api_version: apps/v1
    kind: Deployment
    namespace: "{{ rembayung_namespace }}"
    name: "{{ item }}"
  loop: "{{ rembayung_services }}"
  register: current_deployments

# One tag per Deployment, not one per cluster. booking-service and queue-gate
# are currently on different tags because Phase 3 deployed them separately by
# hand. A single rollback tag would restore one service to the other's tag,
# which is worse than the failure it was reverting.
- name: Build the per-service map of currently running tags
  ansible.builtin.set_fact:
    current_tags: >-
      {{ dict(rembayung_services | zip(
           current_deployments.results
           | map(attribute='resources.0.spec.template.spec.containers.0.image')
           | map('split', ':') | map('last') | list)) }}

- name: Show what is running now
  ansible.builtin.debug:
    msg: "currently deployed: {{ current_tags }}"
```

- [ ] **Step 8: Verify it fails without a tag**

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml
```

Expected: FAILS at "Require an explicit image_tag", printing the `fail_msg`. It must not reach the `k8s_info` task.

- [ ] **Step 9: Verify discovery reports the truth**

```bash
oc get deploy booking-service -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
oc get deploy queue-gate      -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml -e image_tag=irrelevant
```

Expected: the debug line prints `{'booking-service': '66393c1', 'queue-gate': '270288f'}`, matching the two `oc` outputs exactly. If the two services show the same tag, re-read the `set_fact` — the zip is probably collapsing.

- [ ] **Step 10: Commit**

```bash
git add deploy/ansible
git commit -m "Add Ansible playbook skeleton with deployment tag discovery"
git log -1 --format=%B
```

The `git log` is not decoration. Read the output and amend if it contains anything you did not write.

---

## Task 2: Apply the tag and wait for the rollout

**Files:**
- Create: `deploy/ansible/roles/rembayung/tasks/apply.yml`
- Modify: `deploy/ansible/roles/rembayung/tasks/main.yml` (append the include)

**Interfaces:**
- Consumes: `current_tags`, `rembayung_services`, `rembayung_namespace`, `rembayung_rollout_timeout` from Task 1.
- Produces: nothing new for later tasks. Leaves both Deployments running `image_tag` and their rollouts complete.

- [ ] **Step 1: Write the apply tasks**

`deploy/ansible/roles/rembayung/tasks/apply.yml`:

```yaml
---
# strategic_merge_patch on the Deployment rather than `oc apply -k`: the overlay
# pins a tag in git, and CD deploys a tag chosen at run time. Patching the image
# leaves every other field the overlay owns untouched.
- name: Set the image on each Deployment
  kubernetes.core.k8s:
    state: patched
    api_version: apps/v1
    kind: Deployment
    namespace: "{{ rembayung_namespace }}"
    name: "{{ item }}"
    definition:
      spec:
        template:
          spec:
            containers:
              - name: "{{ item }}"
                image: "{{ rembayung_registry }}/{{ item }}:{{ image_tag }}"
  loop: "{{ rembayung_services }}"

# wait_condition Available is not sufficient on its own: a Deployment stays
# Available while a new ReplicaSet is still rolling, because the old pods are
# still serving. Progressing with reason NewReplicaSetAvailable is what actually
# means "the new pods are up".
- name: Wait for both rollouts to complete
  kubernetes.core.k8s_info:
    api_version: apps/v1
    kind: Deployment
    namespace: "{{ rembayung_namespace }}"
    name: "{{ item }}"
    wait: true
    wait_condition:
      type: Progressing
      status: "True"
      reason: NewReplicaSetAvailable
    wait_timeout: "{{ rembayung_rollout_timeout }}"
  loop: "{{ rembayung_services }}"
  register: rollout_result
  # A timeout here means the new image will not run — a bad tag, an unpullable
  # image, a crash loop. Do not let the play stop: rollback.yml must run.
  ignore_errors: true

- name: Roll back if either rollout failed
  ansible.builtin.include_tasks: rollback.yml
  vars:
    failure_reason: >-
      rollout did not complete within {{ rembayung_rollout_timeout }}s
      for tag {{ image_tag }}
  when: rollout_result is failed
```

- [ ] **Step 2: Add the include to `main.yml`**

Append to `deploy/ansible/roles/rembayung/tasks/main.yml`:

```yaml
- name: Apply the requested tag and wait for the rollout
  ansible.builtin.include_tasks: apply.yml
```

- [ ] **Step 3: Create a rollback stub so the include resolves**

`deploy/ansible/roles/rembayung/tasks/rollback.yml` — Task 4 replaces this entirely. Without it, Task 2 cannot run at all.

```yaml
---
# Replaced in full by Task 4. Present so apply.yml's include resolves.
- name: Rollback placeholder
  ansible.builtin.fail:
    msg: "deploy failed ({{ failure_reason }}) and rollback is not implemented yet"
```

- [ ] **Step 4: Verify the syntax**

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml \
  -e image_tag=66393c1 --syntax-check
```

Expected: no output beyond the playbook path.

- [ ] **Step 5: Deploy the tag that is already running — a real no-op**

`booking-service` is on `66393c1` and `queue-gate` on `270288f`, so deploy each to its own current tag. Record the pod names first; they must not change.

```bash
oc get pods -o name | sort > /tmp/pods-before.txt

ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml \
  -e image_tag=66393c1
```

Expected: this **fails** on `queue-gate`, because `queue-gate:66393c1` does not exist. That is correct behaviour and proves the per-service tag problem is real rather than theoretical — a single tag cannot describe this cluster.

Now deploy each service to a tag that genuinely exists for it, using the CI-published tag that is present for both:

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml \
  -e image_tag=4573e8f0a692c7337c0af9b51a615aeeb24767df

oc get pods -o name | sort > /tmp/pods-after.txt
diff /tmp/pods-before.txt /tmp/pods-after.txt || true
oc get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'
```

Expected: the play succeeds, both Deployments now show the `4573e8f0…` tag, pods are replaced (the diff is non-empty — this is a real deploy, not a no-op), and all pods reach `1/1 Running`.

- [ ] **Step 6: Confirm the app still works end to end**

```bash
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
curl -s -XPOST $GATE/queue
```

Expected: a JSON body containing a `token`, or `{"error":"NOT_OPEN"}` if the drop window is closed. Either proves the gate is serving. A connection error or a 5xx means the deploy broke something — investigate before continuing.

- [ ] **Step 7: Commit**

```bash
git add deploy/ansible
git commit -m "Apply the requested image tag and wait for rollout"
git log -1 --format=%B
```

---

## Task 3: Smoke test the public Route

**Files:**
- Create: `deploy/ansible/roles/rembayung/tasks/smoke.yml`
- Modify: `deploy/ansible/roles/rembayung/tasks/main.yml` (append the include)
- Modify: `deploy/ansible/roles/rembayung/defaults/main.yml` (no new keys; verify the retry keys are present)

**Interfaces:**
- Consumes: `rembayung_namespace`, `rembayung_route_name`, `rembayung_smoke_retries`, `rembayung_smoke_delay` from Task 1.
- Produces: the fact `route_host`, the Route's hostname read from the cluster. Task 4 reuses it when re-verifying after a rollback.

- [ ] **Step 1: Write the smoke tasks**

`deploy/ansible/roles/rembayung/tasks/smoke.yml`:

```yaml
---
# Read the host rather than hardcoding it. The sandbox expires around
# 2026-10-01 and the hostname changes on renewal.
- name: Read the public Route host
  kubernetes.core.k8s_info:
    api_version: route.openshift.io/v1
    kind: Route
    namespace: "{{ rembayung_namespace }}"
    name: "{{ rembayung_route_name }}"
  register: route_info

- name: Store the Route host
  ansible.builtin.set_fact:
    route_host: "{{ route_info.resources[0].spec.host }}"

# This test deliberately does NOT book. A booking consumes seats from a finite
# 250-seat slot, so a smoke test that booked would drain real inventory on every
# deploy. It proves the Route, the gate and Redis are all working; booking-
# service's own readiness probe already covers the datasource, which is checked
# below.
- name: Join the queue through the public Route
  ansible.builtin.uri:
    url: "https://{{ route_host }}/queue"
    method: POST
    status_code: [200, 409]
    return_content: true
  register: queue_join
  retries: "{{ rembayung_smoke_retries }}"
  delay: "{{ rembayung_smoke_delay }}"
  until: queue_join is succeeded
  ignore_errors: true

# 409 SOLD_OUT is a correct answer from a healthy system whose ticket counter is
# exhausted, so it counts as a pass. Only a 200 gives a token to check further.
- name: Check the token's status when one was issued
  ansible.builtin.uri:
    url: "https://{{ route_host }}/queue/{{ queue_join.json.token }}"
    method: GET
    status_code: 200
    return_content: true
  register: queue_status
  when:
    - queue_join is succeeded
    - queue_join.status == 200
  ignore_errors: true

- name: Confirm booking-service is Ready
  kubernetes.core.k8s_info:
    api_version: apps/v1
    kind: Deployment
    namespace: "{{ rembayung_namespace }}"
    name: booking-service
  register: booking_state

- name: Decide whether the smoke test passed
  ansible.builtin.set_fact:
    smoke_passed: >-
      {{ (queue_join is succeeded)
         and (queue_status is not failed)
         and (booking_state.resources[0].status.readyReplicas | default(0) | int > 0) }}

- name: Report the smoke result
  ansible.builtin.debug:
    msg: >-
      smoke: queue={{ queue_join.status | default('unreachable') }}
      booking-service ready replicas={{ booking_state.resources[0].status.readyReplicas | default(0) }}
      passed={{ smoke_passed }}

- name: Roll back if the smoke test failed
  ansible.builtin.include_tasks: rollback.yml
  vars:
    failure_reason: "smoke test failed against https://{{ route_host }}"
  when: not smoke_passed
```

- [ ] **Step 2: Add the include to `main.yml`**

Append to `deploy/ansible/roles/rembayung/tasks/main.yml`:

```yaml
- name: Smoke-test the public Route
  ansible.builtin.include_tasks: smoke.yml
```

- [ ] **Step 3: Run it against the healthy cluster**

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml \
  -e image_tag=4573e8f0a692c7337c0af9b51a615aeeb24767df
```

Expected: `passed=True`, and the play completes without invoking rollback.

- [ ] **Step 4: Prove the smoke test can actually fail**

A test that has never failed is not known to test anything. Force a bad hostname:

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml \
  -e image_tag=4573e8f0a692c7337c0af9b51a615aeeb24767df \
  -e rembayung_route_name=does-not-exist
```

Expected: the play fails while reading the Route (the `resources` list is empty), before any deploy decision. This confirms the Route lookup is load-bearing rather than decorative.

- [ ] **Step 5: Confirm no seats were consumed**

The smoke test must not have booked anything. In the OCI SQL worksheet:

```sql
SELECT capacity, seats_taken FROM booking.slots;
```

Expected: `seats_taken` is unchanged from before Step 3. If it moved, the smoke test is booking and violates a global constraint — remove the offending task.

- [ ] **Step 6: Commit**

```bash
git add deploy/ansible
git commit -m "Smoke-test the queue path after deploying"
git log -1 --format=%B
```

---

## Task 4: Rollback

The task that makes this delivery rather than a scripted `oc apply`.

**Files:**
- Modify: `deploy/ansible/roles/rembayung/tasks/rollback.yml` (replaces the Task 2 stub in full)

**Interfaces:**
- Consumes: `current_tags` from Task 1, `route_host` from Task 3, `failure_reason` passed by the caller.
- Produces: nothing. Terminal — the play always ends non-zero after a rollback.

- [ ] **Step 1: Replace the stub**

`deploy/ansible/roles/rembayung/tasks/rollback.yml`:

```yaml
---
- name: Announce the rollback
  ansible.builtin.debug:
    msg: >-
      DEPLOY FAILED ({{ failure_reason }}).
      Rolling back to {{ current_tags }}.

# Re-applying a known tag, not `oc rollout undo`. undo reverts to the previous
# ReplicaSet, which is state the cluster happens to still hold and which a
# pruned revision history can lose. Re-applying is explicit, reproducible from
# the repository, and works on a cluster that has forgotten.
#
# Each service goes back to ITS OWN previous tag. current_tags is a per-service
# map for exactly this reason.
- name: Restore each Deployment to the tag it was running
  kubernetes.core.k8s:
    state: patched
    api_version: apps/v1
    kind: Deployment
    namespace: "{{ rembayung_namespace }}"
    name: "{{ item }}"
    definition:
      spec:
        template:
          spec:
            containers:
              - name: "{{ item }}"
                image: "{{ rembayung_registry }}/{{ item }}:{{ current_tags[item] }}"
  loop: "{{ rembayung_services }}"

- name: Wait for the rollback to complete
  kubernetes.core.k8s_info:
    api_version: apps/v1
    kind: Deployment
    namespace: "{{ rembayung_namespace }}"
    name: "{{ item }}"
    wait: true
    wait_condition:
      type: Progressing
      status: "True"
      reason: NewReplicaSetAvailable
    wait_timeout: "{{ rembayung_rollout_timeout }}"
  loop: "{{ rembayung_services }}"
  register: rollback_rollout
  ignore_errors: true

- name: Re-check the queue path after rolling back
  ansible.builtin.uri:
    url: "https://{{ route_host | default(rembayung_route_fallback_host) }}/queue"
    method: POST
    status_code: [200, 409]
  register: rollback_smoke
  retries: "{{ rembayung_smoke_retries }}"
  delay: "{{ rembayung_smoke_delay }}"
  until: rollback_smoke is succeeded
  ignore_errors: true

# Always non-zero. A rollback that succeeded still means the requested deploy
# did not happen, and a caller that treats that as success will keep going.
- name: Fail loudly, naming both tags
  ansible.builtin.fail:
    msg: >-
      Deploy of {{ image_tag }} failed: {{ failure_reason }}.
      Rolled back to {{ current_tags }}.
      Rollback rollout: {{ 'ok' if rollback_rollout is not failed else 'DID NOT COMPLETE' }}.
      Rollback smoke: {{ 'ok' if rollback_smoke is succeeded else 'DID NOT PASS — A HUMAN IS NEEDED' }}.
```

- [ ] **Step 2: Add the fallback host default**

`route_host` is unset when the rollout fails before Task 3 runs. Append to `deploy/ansible/roles/rembayung/defaults/main.yml`:

```yaml
# Used only when a rollout fails before smoke.yml has read the live Route.
rembayung_route_fallback_host: queue-gate-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com
```

- [ ] **Step 3: Record the state you must return to**

```bash
oc get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image' \
  | tee /tmp/before-rollback-test.txt
```

- [ ] **Step 4: Inject a real failure — deploy a tag that does not exist**

This is the only honest way to test a rollback. A nonexistent tag gives `ImagePullBackOff`, the rollout wait times out, and the rollback path runs.

```bash
ansible-playbook -i deploy/ansible/inventory.ini deploy/ansible/deploy.yml \
  -e image_tag=0000000000000000000000000000000000000000
```

Expected, in order:
1. Discovery prints the current tags
2. The patch succeeds (Kubernetes accepts any image string)
3. The rollout wait times out after 300s
4. The rollback announces itself and restores each service to its own tag
5. The rollback's smoke test passes
6. The play **fails** with a message naming `0000…`, both restored tags, `Rollback rollout: ok` and `Rollback smoke: ok`

Exit code must be non-zero: `echo $?` after the run.

- [ ] **Step 5: Confirm the cluster is genuinely back**

```bash
oc get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'
oc get pods
GATE=https://$(oc get route queue-gate -o jsonpath='{.spec.host}')
curl -s -XPOST $GATE/queue
```

Expected: images match `/tmp/before-rollback-test.txt` exactly, all pods `1/1 Running`, and the gate answers. **Do not proceed until this is true** — a rollback test that leaves the cluster broken is worse than no rollback test.

- [ ] **Step 6: Commit**

```bash
git add deploy/ansible
git commit -m "Roll back to the previously running tags when a deploy fails"
git log -1 --format=%B
```

---

## Task 5: ServiceAccount and the CD workflow

**Files:**
- Create: `deploy/openshift/cd-serviceaccount.yaml`
- Create: `.github/workflows/cd.yml`

**Interfaces:**
- Consumes: `deploy/ansible/deploy.yml` and the whole role from Tasks 1–4.
- Produces: a workflow that runs the playbook after CI succeeds on `main`.

- [ ] **Step 1: Write the ServiceAccount, Role and RoleBinding**

`deploy/openshift/cd-serviceaccount.yaml`:

```yaml
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: rembayung-cd
  namespace: marwanbukhori-dev
  labels:
    app.kubernetes.io/part-of: rembayung-queue
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: rembayung-cd
  namespace: marwanbukhori-dev
  labels:
    app.kubernetes.io/part-of: rembayung-queue
rules:
  # Deployments are patched and watched; that is the whole job.
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "watch", "patch", "update"]
  # Read-only: the playbook reads the Route's hostname and checks pod status.
  - apiGroups: [""]
    resources: ["pods", "services"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["route.openshift.io"]
    resources: ["routes"]
    verbs: ["get", "list", "watch"]
# Deliberately absent: secrets. The Oracle wallet and database credentials are
# created once by a human and are never read, written or mounted by CD. A
# compromised CD token cannot exfiltrate them.
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: rembayung-cd
  namespace: marwanbukhori-dev
  labels:
    app.kubernetes.io/part-of: rembayung-queue
subjects:
  - kind: ServiceAccount
    name: rembayung-cd
    namespace: marwanbukhori-dev
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: rembayung-cd
```

- [ ] **Step 2: Apply it and confirm the permission boundary**

```bash
oc apply -f deploy/openshift/cd-serviceaccount.yaml

SA=system:serviceaccount:marwanbukhori-dev:rembayung-cd
oc auth can-i patch deployments  --as="$SA" -n marwanbukhori-dev   # yes
oc auth can-i get routes         --as="$SA" -n marwanbukhori-dev   # yes
oc auth can-i get secrets        --as="$SA" -n marwanbukhori-dev   # no
oc auth can-i delete deployments --as="$SA" -n marwanbukhori-dev   # no
```

All four must print exactly what is commented. `get secrets` returning `yes` means the Role is wrong — stop and fix it.

- [ ] **Step 3: STOP. The token is the repository owner's to create**

**Do not create, read, print, or copy a ServiceAccount token.** Handling credentials in plain text is not yours to do. Report to the controller that Task 5 needs this from the repository owner, and continue to Step 4 — the workflow can be written and committed before the secret exists.

The owner runs:

```bash
oc create token rembayung-cd --duration=8760h -n marwanbukhori-dev
```

and stores the output as the repository secret `OPENSHIFT_TOKEN` at
`https://github.com/marwanbukhori/rembayung-queue/settings/secrets/actions`.

- [ ] **Step 4: Write the CD workflow**

`.github/workflows/cd.yml`:

```yaml
name: CD

# Separate from CI so a rollback needs no rebuild: this workflow can be
# re-dispatched against any previously published tag. It also means a failed
# deploy does not mark the build red, which would be a lie about the code.
on:
  workflow_run:
    workflows: [CI]
    types: [completed]
    branches: [main]
  workflow_dispatch:
    inputs:
      image_tag:
        description: Image tag to deploy (full commit SHA from the CI run summary)
        required: true
        type: string

concurrency:
  group: cd-${{ github.ref }}
  # Never cancel a deploy midway. A half-applied rollout is worse than a queued
  # one, and the same reasoning applies here as to CI's publish steps.
  cancel-in-progress: false

jobs:
  deploy:
    # A CI run that failed must not deploy. workflow_run fires on completion
    # regardless of outcome, so the conclusion has to be checked explicitly —
    # this is the one place where omitting it would ship a broken commit.
    if: >-
      github.event_name == 'workflow_dispatch' ||
      github.event.workflow_run.conclusion == 'success'
    runs-on: ubuntu-latest
    timeout-minutes: 20

    steps:
      - uses: actions/checkout@v4

      - name: Install Ansible and the Kubernetes client library
        run: |
          python3 -m pip install --upgrade pip
          python3 -m pip install ansible kubernetes
          ansible-galaxy collection install -r deploy/ansible/requirements.yml

      - name: Authenticate to OpenShift
        env:
          OPENSHIFT_TOKEN: ${{ secrets.OPENSHIFT_TOKEN }}
        run: |
          # K8S_AUTH_* is what kubernetes.core reads. Never echo the token.
          echo "K8S_AUTH_HOST=https://api.rm3.7wse.p1.openshiftapps.com:6443" >> "$GITHUB_ENV"
          echo "K8S_AUTH_API_KEY=${OPENSHIFT_TOKEN}" >> "$GITHUB_ENV"

      - name: Resolve the tag to deploy
        run: |
          if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
            tag="${{ inputs.image_tag }}"
          else
            # The SHA CI built and published, not github.sha — which on a
            # workflow_run points at the default branch head and can differ.
            tag="${{ github.event.workflow_run.head_sha }}"
          fi
          echo "IMAGE_TAG=${tag}" >> "$GITHUB_ENV"
          echo "deploying tag ${tag}"

      - name: Deploy
        run: |
          ansible-playbook \
            -i deploy/ansible/inventory.ini \
            deploy/ansible/deploy.yml \
            -e image_tag="${IMAGE_TAG}"

      - name: Summary
        if: always()
        run: |
          {
            echo "### CD"
            echo ""
            echo "Tag: \`${IMAGE_TAG}\`"
            echo ""
            echo "Result: ${{ job.status }}"
            echo ""
            echo "Redeploy a previous tag from the Actions tab via Run workflow."
          } >> "$GITHUB_STEP_SUMMARY"
```

- [ ] **Step 5: Verify the workflow parses**

```bash
ruby -ryaml -e 'YAML.load_file(".github/workflows/cd.yml"); puts "valid"'
```

`python3` has no `yaml` module in this environment; `ruby` does.

- [ ] **Step 6: Commit**

```bash
git add deploy/openshift/cd-serviceaccount.yaml .github/workflows/cd.yml
git commit -m "Add a scoped service account and the CD workflow"
git log -1 --format=%B
```

Do not dispatch the workflow. It cannot succeed until the owner has created `OPENSHIFT_TOKEN`, and a failed run would be noise rather than information.

---

## Task 6: Document it

**Files:**
- Create: `docs/notes/07-continuous-delivery.md`
- Modify: `docs/notes/README.md` (add a row for note 07)
- Modify: `deploy/README.md` (add a Continuous Delivery section after the CI section)

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: nothing.

- [ ] **Step 1: Read the notes you are matching**

Read `docs/notes/03-how-this-runs.md` and `docs/notes/06-continuous-integration.md` first. They explain *why* a choice was made — especially where a default was rejected — rather than narrating what a file contains. Match that voice.

- [ ] **Step 2: Write note 07**

`docs/notes/07-continuous-delivery.md` must cover:

- **Why Ansible and not Jenkins.** The sandbox arrived with an AAP stack consuming 1950m of the 3000m CPU quota and it had to be deleted before the autoscaling demo could run. In-cluster Jenkins has the same problem smaller. Jenkins' rationale — a host with network reach into the cluster — is already satisfied by a runner. Say plainly that AAP is where this would run in production and that it is not run here, and why.
- **Why `image_tag` is required and never derived.** CI publishes full 40-character SHAs; `build-push.sh` publishes 7-character ones. Deriving picks the wrong namespace and yields `manifest unknown`.
- **Why `rollback_tag` is per-Deployment.** Include the real observation: `booking-service` was on `66393c1` while `queue-gate` was on `270288f`. A single tag would have restored one service to the other's tag.
- **Why rollback re-applies rather than `oc rollout undo`.** `undo` depends on retained ReplicaSets, which a pruned history can lose.
- **Why the smoke test does not book.** Seats are finite; a booking smoke test drains real inventory every deploy. State the cost honestly: a deploy can pass while booking logic is broken, narrowed but not closed by readiness covering the datasource.
- **Why CD is a separate workflow.** Rollback without a rebuild; a deploy failure does not mark the build red.
- **Why the ServiceAccount cannot read Secrets**, and what that buys.
- **How the rollback was actually tested** — deploying an all-zeros tag, watching `ImagePullBackOff`, the wait time out, the rollback restore each service to its own tag. Report what really happened, including the wall-clock time it took.

- [ ] **Step 3: Add the README row**

Add a row for note 07 to `docs/notes/README.md`, matching the existing table format exactly.

- [ ] **Step 4: Add the deploy/README section**

Add a "Continuous Delivery" section to `deploy/README.md` immediately after the CI section, covering: how to run the playbook by hand, how to deploy a specific tag from the Actions tab, how rollback behaves, and that the sandbox's expiry around 2026-10-01 requires updating `defaults/main.yml` and the `OPENSHIFT_TOKEN` secret.

- [ ] **Step 5: Commit**

```bash
git add docs/notes deploy/README.md
git commit -m "Document the continuous delivery pipeline"
git log -1 --format=%B
```

---

## Self-Review

**1. Spec coverage.** Every section of the spec maps to a task: §4 architecture → Tasks 2 and 5; §5 playbook layout, variables and the non-booking smoke test → Tasks 1 and 3; §6 rollback, including the per-Deployment tag and the loud failure → Task 4; §7 the namespace-scoped ServiceAccount with no Secrets access → Task 5; §8 the testing ladder, including the deliberate broken-tag injection → Task 4 Step 4; §10 known weaknesses → Task 6. §9's out-of-scope items are correctly absent.

**2. Placeholder scan.** Every code step contains the file's actual content. The one intentional stub is Task 2 Step 3's `rollback.yml`, which is labelled as such and replaced in full by Task 4 Step 1 — without it, Task 2's include cannot resolve.

**3. Type consistency.** `current_tags` is a dict keyed by service name throughout, produced in Task 1 and read in Tasks 2 and 4. `route_host` is a plain string, set in Task 3 and read in Task 4 with a default for the case where a rollout fails before Task 3 runs. `rembayung_services`, `rembayung_namespace`, `rembayung_registry`, `rembayung_rollout_timeout`, `rembayung_smoke_retries` and `rembayung_smoke_delay` are defined once in `defaults/main.yml` and spelled identically everywhere.

**One gap worth naming rather than hiding.** Task 5 cannot complete unattended: the ServiceAccount token must be created and stored by the repository owner, because handling credentials in plain text is not an implementer's job. Tasks 1–4 and 6 are unaffected, and the workflow is committed before the secret exists so that only the dispatch waits.

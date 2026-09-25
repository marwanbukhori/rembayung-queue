# Cluster Inspector, Step 1: Clickable Graph and Inspector — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every object in the console's object graph becomes clickable and opens an inspector showing its live status, facts, related objects and events.

**Architecture:** A new `dev.marwan.console.objects` package in the existing console app. `ObjectSource` reads the cluster; pure describer classes turn fabric8 models into one generic `ObjectDetail` (headline, tone, label/value facts, related links, events); `ObjectsProvider` adds scope checks, a 2s cache and failure isolation; `ObjectsController` serves it. The Angular side adds an `InspectorService` (polls the selected object, syncs `?inspect=` in the URL) and an `Inspector` component beside the existing SVG graph, which becomes a drawer under 900px.

**Tech Stack:** Java 25, Spring Boot 4.1, fabric8 kubernetes-client 6.13.4, JUnit 5 + AssertJ + Mockito, Angular (standalone components, signals).

**Spec:** [`docs/superpowers/specs/2026-09-25-cluster-inspector-and-run-agent-design.md`](../specs/2026-09-25-cluster-inspector-and-run-agent-design.md). This plan is §9 step 1. Logs (step 2), charts (step 3) and the run agent (step 4) are separate plans.

**Deliberately not in this step** (spec parts owned by later plans):
- The Job inspector's k6 numbers (booked, rejected, latency) and the Analysis tab come with the run agent (step 4), which parses `K6_SUMMARY`. Here a Job shows its status and duration.
- The past-rushes list comes from Jobs (15-minute lifetime) until step 4 stores analyses in ConfigMaps.
- Redis's "what it holds" overview line (tickets, admitted, queue depth) comes with step 2, alongside the Logs tab that explains redis logs only its lifecycle.

## Global Constraints

- Everything lives in the existing `console` app. No new Deployment, image, Route, CI or CD step.
- Every endpoint here is GET, so public under `KeyFilter`. Nothing in this plan exposes logs.
- No endpoint returns 500 because the cluster is unreadable: it returns `available: false` with a one-line reason (`KubernetesAccess.summarise`).
- A failed cluster call calls `KubernetesAccess.invalidate()` so the next poll reconnects.
- Server-side cache: 2 seconds per object, following `ObservabilityProbe`.
- Only this project's objects are describable (see `Scope`); anything else is 404.
- RBAC is applied by hand once; CD withholds RBAC.
- Commit messages: plain sentences in the repository's existing style, with no AI attribution and no `Co-Authored-By` or `Generated with` trailers.
- Java builds need `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` on the development machine.

## Review Focus

1. **A pod that disappears while inspected.** A rollout or HPA scale-in deletes the selected pod between polls. Expected: the inspector says the pod no longer exists and links to its Deployment; it does not blank, spin forever, or show a stale "Running". Pinned by the provider's 404 test (Task 5) and the UI handling in Task 7.
2. **Objects with no status yet.** A Deployment scaled to 0 has `availableReplicas` null; a fresh HPA has no `currentMetrics`; a pending pod has no `containerStatuses`. Expected: facts read `0` or `—`, never an exception. Pinned in Tasks 3 and 4.
3. **Events without `lastTimestamp`.** Newer events carry only `eventTime`. Expected: they sort correctly among the rest and show a time. Pinned in Task 4.
4. **A name that is not ours.** `oc debug` pods, arbitrary names, other kinds. Expected: 404, never a description of an object outside this project. Pinned in Tasks 2 and 5.
5. **A Route probe that hangs.** The public host not answering. Expected: the probe gives up after 3 seconds, the Route shows "no answer", and other objects are unaffected. Pinned in Task 4 (rendering) and Task 5 (probe timeout is a constructor constant, exercised via the fake).

---

## File Structure

**Backend — create, all under `console/src/main/java/dev/marwan/console/objects/`:**

| File | Responsibility |
|---|---|
| `ObjectKind.java` | The seven kinds and their URL names |
| `ObjectDetail.java` | The one response shape the inspector renders, plus `unavailable(...)` |
| `ObjectSummary.java` | One row in a list of objects (recent Jobs) |
| `Scope.java` | Decides whether an object belongs to this project |
| `Facts.java` | Small shared formatting helpers (ages, image tags, IntOrString) |
| `WorkloadDescriber.java` | Deployment, Pod, HPA, Job, CronJob → `ObjectDetail` |
| `NetworkDescriber.java` | Route, Service → `ObjectDetail` |
| `EventLines.java` | Kubernetes Events → sorted, capped `EventLine`s |
| `RouteProbe.java` | Result of asking a public host whether the app answers |
| `ObjectSource.java` | Interface: every read the inspector needs |
| `KubernetesObjectSource.java` | `ObjectSource` over fabric8 plus an HTTP probe |
| `ObjectsProvider.java` | Kind dispatch, scope, cache, failure isolation |
| `ObjectNotFound.java` | Thrown for unknown kinds, absent objects, or objects out of scope |
| `ObjectsController.java` | `GET /api/objects/{kind}/{name}`, `GET /api/objects?kind=job` |

**Backend — tests under `console/src/test/java/dev/marwan/console/objects/`:** `ObjectKindTest`, `ScopeTest`, `WorkloadDescriberTest`, `NetworkDescriberTest`, `EventLinesTest`, `ObjectsProviderTest`, `FakeObjectSource`, and `ObjectsControllerTest`.

**Deploy — modify:** `deploy/base/console/rbac.yaml`.

**UI — under `console/ui/src/app/`:**

| File | Change |
|---|---|
| `state.ts` | Add `ObjectRef`, `ObjectDetail`, `ObjectSummary` types |
| `inspector.service.ts` | Create: selection signal synced to `?inspect=`, polling, 404 handling |
| `inspector.ts` | Create: the inspector panel and drawer |
| `object-graph.ts` | Boxes carry a `ref`; clickable, keyboard accessible, selected highlight |
| `cluster-page.ts` | The "How these objects connect" card becomes graph + inspector |

---

### Task 1: RBAC for reading the objects

**Files:**
- Modify: `deploy/base/console/rbac.yaml` (the `rules:` list of the `console` Role)

**Interfaces:**
- Produces: the `console` ServiceAccount can get/list deployments, replicasets, endpointslices, networkpolicies and cronjobs, and list jobs. Tasks 5 and 10 rely on it.

- [ ] **Step 1: Add the rules**

In `deploy/base/console/rbac.yaml`, replace the `batch` jobs rule and append the new rules so the `rules:` list reads:

```yaml
rules:
  - apiGroups: ["batch"]
    resources: ["jobs"]
    # list added for the inspector: recent load runs and keepalive runs.
    verbs: ["get", "list", "create", "delete"]
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get", "create", "update"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources: ["resourcequotas"]
    verbs: ["get", "list"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["get", "list"]
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["get", "list"]
  - apiGroups: ["route.openshift.io"]
    resources: ["routes"]
    verbs: ["get", "list"]
  # The cluster inspector (spec 2026-09-25 §7). Read only: nothing here writes
  # a workload, reads a Secret, or execs into a pod.
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list"]
  - apiGroups: ["discovery.k8s.io"]
    resources: ["endpointslices"]
    verbs: ["get", "list"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["networkpolicies"]
    verbs: ["get", "list"]
  - apiGroups: ["batch"]
    resources: ["cronjobs"]
    verbs: ["get", "list"]
```

- [ ] **Step 2: Check it renders and the server accepts it**

Run: `oc kustomize deploy/overlays/sandbox > /tmp/render.yaml && oc apply --dry-run=server -f /tmp/render.yaml | grep role`
Expected: `role.rbac.authorization.k8s.io/console configured (server dry run)`

- [ ] **Step 3: Commit**

```bash
git add deploy/base/console/rbac.yaml
git commit -m "Let the console read the objects its inspector will describe"
```

- [ ] **Step 4: Hand the apply to the human**

CD withholds RBAC. Add this to the top of `RUN-THESE.md` under **PENDING NOW** (the file is gitignored, so it is not committed) and tell the human it must be run before Task 10:

```zsh
oc apply -f <(oc kustomize deploy/overlays/sandbox | yq 'select(.kind == "Role" and .metadata.name == "console")')
for r in deployments.apps replicasets.apps endpointslices.discovery.k8s.io networkpolicies.networking.k8s.io cronjobs.batch; do
  printf "%s: " $r; oc auth can-i list $r --as=system:serviceaccount:marwanbukhori-dev:console
done
```

Expected after running: five lines ending `yes`.

---

### Task 2: The response model and the scope rule

**Files:**
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectKind.java`
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectDetail.java`
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectSummary.java`
- Create: `console/src/main/java/dev/marwan/console/objects/Scope.java`
- Test: `console/src/test/java/dev/marwan/console/objects/ObjectKindTest.java`
- Test: `console/src/test/java/dev/marwan/console/objects/ScopeTest.java`

**Interfaces:**
- Produces:
  - `enum ObjectKind { ROUTE, SERVICE, DEPLOYMENT, POD, HPA, JOB, CRONJOB; String path(); static Optional<ObjectKind> parse(String) }`
  - `record ObjectDetail(String kind, String name, boolean available, String detail, String tone, String headline, List<Fact> facts, List<Link> related, List<EventLine> events)` with nested `Fact(String label, String value, String tone)`, `Link(String kind, String name, String label, String tone)`, `EventLine(String at, String type, String reason, String message, int count)`, constants `OK`, `WARN`, `BAD`, `NEUTRAL`, `static ObjectDetail unavailable(ObjectKind, String name, String reason)`, and `ObjectDetail withEvents(List<EventLine>)`
  - `record ObjectSummary(String kind, String name, String tone, String headline, String at)`
  - `final class Scope { static boolean ours(HasMetadata) }`

- [ ] **Step 1: Write the failing tests**

`ObjectKindTest.java`:

```java
package dev.marwan.console.objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectKindTest {

    @Test
    void everyKindParsesFromItsOwnPathName() {
        for (ObjectKind kind : ObjectKind.values()) {
            assertThat(ObjectKind.parse(kind.path())).contains(kind);
        }
    }

    /** The path segment is user input. Anything unrecognised is absent, never an exception. */
    @Test
    void unknownOrOddlyCasedKindsAreAbsent() {
        assertThat(ObjectKind.parse("secret")).isEmpty();
        assertThat(ObjectKind.parse("POD")).isEmpty();
        assertThat(ObjectKind.parse("")).isEmpty();
        assertThat(ObjectKind.parse(null)).isEmpty();
    }
}
```

`ScopeTest.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {

    @Test
    void anythingKustomizeLabelledAsThisProjectIsOurs() {
        Service service = new ServiceBuilder().withNewMetadata().withName("queue-gate")
                .addToLabels("app.kubernetes.io/part-of", "rembayung-queue").endMetadata().build();

        assertThat(Scope.ours(service)).isTrue();
    }

    /** Pod templates do not carry the part-of label; the app label is what they share. */
    @Test
    void podsOfOurFourWorkloadsAndLoadRunsAreOurs() {
        for (String app : new String[]{"console", "queue-gate", "booking-service", "redis", "rembayung-load"}) {
            assertThat(Scope.ours(pod("x", app))).as(app).isTrue();
        }
    }

    @Test
    void keepaliveJobsAndTheirPodsAreOursThroughTheirOwners() {
        Job job = new JobBuilder().withNewMetadata().withName("keepalive-29838360")
                .addNewOwnerReference().withKind("CronJob").withName("keepalive").endOwnerReference()
                .endMetadata().build();
        Pod pod = new PodBuilder().withNewMetadata().withName("keepalive-29838360-zg4hz")
                .addNewOwnerReference().withKind("Job").withName("keepalive-29838360").endOwnerReference()
                .endMetadata().build();

        assertThat(Scope.ours(job)).isTrue();
        assertThat(Scope.ours(pod)).isTrue();
    }

    /** `oc debug` strips labels. A debug pod, or anything else unlabelled, is not ours to describe. */
    @Test
    void anUnlabelledPodIsNotOurs() {
        Pod debug = new PodBuilder().withNewMetadata().withName("console-debug-fbfbz").endMetadata().build();

        assertThat(Scope.ours(debug)).isFalse();
        assertThat(Scope.ours(pod("x", "someone-else"))).isFalse();
    }

    private static Pod pod(String name, String app) {
        return new PodBuilder().withNewMetadata().withName(name).addToLabels("app", app).endMetadata().build();
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd console && ./mvnw -q test -Dtest='ObjectKindTest,ScopeTest'`
Expected: compilation failure, `cannot find symbol: class ObjectKind` / `Scope`.

- [ ] **Step 3: Implement**

`ObjectKind.java`:

```java
package dev.marwan.console.objects;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds the inspector can describe, by the name each carries in a URL.
 *
 * A closed set on purpose. The kind comes from a public URL, and an open
 * mapping to Kubernetes kinds is one typo away from describing Secrets.
 */
public enum ObjectKind {
    ROUTE, SERVICE, DEPLOYMENT, POD, HPA, JOB, CRONJOB;

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ObjectKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ObjectKind kind : values()) {
            if (kind.path().equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
```

`ObjectDetail.java`:

```java
package dev.marwan.console.objects;

import java.util.List;

/**
 * Everything the inspector shows about one object, in one shape for every kind.
 *
 * Generic on purpose: label and value pairs rather than a record per kind, so
 * the page renders a Route and a CronJob with the same code and a new kind is
 * a describer method, not a new component.
 *
 * @param available false when the cluster could not be read; {@code detail} says why
 * @param tone      one of {@link #OK}, {@link #WARN}, {@link #BAD}, {@link #NEUTRAL}
 * @param headline  one line: the state a reader should take away
 */
public record ObjectDetail(String kind, String name, boolean available, String detail,
                           String tone, String headline,
                           List<Fact> facts, List<Link> related, List<EventLine> events) {

    public static final String OK = "ok";
    public static final String WARN = "warn";
    public static final String BAD = "bad";
    public static final String NEUTRAL = "neutral";

    /** @param tone null when the fact is plain information */
    public record Fact(String label, String value, String tone) {
        public Fact(String label, String value) {
            this(label, value, null);
        }
    }

    /** Another object this one points at, for the inspector to link to. */
    public record Link(String kind, String name, String label, String tone) { }

    /** @param at ISO-8601 instant, or null when the event carried no time at all */
    public record EventLine(String at, String type, String reason, String message, int count) { }

    public static ObjectDetail unavailable(ObjectKind kind, String name, String reason) {
        return new ObjectDetail(kind.path(), name, false, reason, NEUTRAL,
                "not readable right now", List.of(), List.of(), List.of());
    }

    public ObjectDetail withEvents(List<EventLine> lines) {
        return new ObjectDetail(kind, name, available, detail, tone, headline, facts, related, lines);
    }
}
```

`ObjectSummary.java`:

```java
package dev.marwan.console.objects;

/**
 * One row in a list of objects, enough to pick one.
 *
 * @param at when it started, ISO-8601, or null
 */
public record ObjectSummary(String kind, String name, String tone, String headline, String at) { }
```

`Scope.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether an object belongs to this project, and so may be described.
 *
 * The namespace is ours, but not everything in it is: `oc debug` pods and
 * anything a person creates by hand live there too. Three ways in, each
 * matching how that kind of object is actually labelled here:
 * kustomize puts part-of on every manifest but not on pod templates; the four
 * workloads and load runs share an `app` label; keepalive Jobs and their pods
 * carry nothing but their owner.
 */
final class Scope {

    static final String PART_OF = "app.kubernetes.io/part-of";
    static final String PROJECT = "rembayung-queue";
    static final Set<String> APPS =
            Set.of("console", "queue-gate", "booking-service", "redis", "rembayung-load");

    private Scope() {
    }

    static boolean ours(HasMetadata object) {
        ObjectMeta meta = object == null ? null : object.getMetadata();
        if (meta == null) {
            return false;
        }
        Map<String, String> labels = meta.getLabels() == null ? Map.of() : meta.getLabels();
        if (PROJECT.equals(labels.get(PART_OF)) || APPS.contains(labels.get("app"))) {
            return true;
        }
        List<OwnerReference> owners = meta.getOwnerReferences() == null ? List.of() : meta.getOwnerReferences();
        return owners.stream().anyMatch(o ->
                ("CronJob".equals(o.getKind()) && "keepalive".equals(o.getName()))
                        || ("Job".equals(o.getKind()) && o.getName() != null
                            && o.getName().startsWith("keepalive-")));
    }
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `cd console && ./mvnw -q test -Dtest='ObjectKindTest,ScopeTest'`
Expected: no output, exit 0. Confirm with `grep -h 'tests=' target/surefire-reports/TEST-*objects*.xml` showing `failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/objects console/src/test/java/dev/marwan/console/objects
git commit -m "Give the inspector one response shape and a rule for what it may describe"
```

---

### Task 3: Describing workloads (Deployment, Pod, HPA)

**Files:**
- Create: `console/src/main/java/dev/marwan/console/objects/Facts.java`
- Create: `console/src/main/java/dev/marwan/console/objects/WorkloadDescriber.java`
- Test: `console/src/test/java/dev/marwan/console/objects/WorkloadDescriberTest.java`

**Interfaces:**
- Consumes: `ObjectDetail`, `ObjectKind` (Task 2).
- Produces (all `static`, package-private, in `WorkloadDescriber`):
  - `ObjectDetail deployment(Deployment d, List<ReplicaSet> replicaSets, HorizontalPodAutoscaler hpaOrNull, List<Pod> pods, Instant now)`
  - `ObjectDetail pod(Pod p, Instant now)`
  - `ObjectDetail hpa(HorizontalPodAutoscaler h, Instant now)`
- Produces in `Facts`: `static String age(String iso, Instant now)`, `static String tag(String image)`, `static String intOrString(IntOrString v)`, `static int orZero(Integer v)`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadDescriberTest {

    private static final Instant NOW = Instant.parse("2026-09-25T08:00:00Z");

    /**
     * The outage of 2026-09-25: desired 0, no status at all. It must read as
     * the problem it is, not as a quiet "0/0" that looks settled.
     */
    @Test
    void aDeploymentScaledToZeroIsBadAndSaysSo() {
        Deployment d = deployment("booking-service", 0, null);

        ObjectDetail detail = WorkloadDescriber.deployment(d, List.of(), null, List.of(), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("scaled to 0 - nothing is serving");
    }

    @Test
    void aDeploymentShortOfReplicasIsAWarning() {
        ObjectDetail detail = WorkloadDescriber.deployment(
                deployment("queue-gate", 2, 1), List.of(), null, List.of(), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.WARN);
        assertThat(detail.headline()).isEqualTo("1 of 2 available");
    }

    @Test
    void aHealthyDeploymentListsItsImageReplicaSetsAndPods() {
        ReplicaSet current = replicaSet("booking-service-874f94d9", "7", 2);
        ReplicaSet old = replicaSet("booking-service-55bf87f47c", "6", 0);
        Pod pod = new PodBuilder().withNewMetadata().withName("booking-service-874f94d9-mfnlb")
                .addToLabels("app", "booking-service").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();

        ObjectDetail detail = WorkloadDescriber.deployment(
                deployment("booking-service", 2, 2), List.of(old, current), null, List.of(pod), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.OK);
        assertThat(detail.headline()).isEqualTo("2 of 2 available");
        assertThat(detail.facts()).extracting(ObjectDetail.Fact::label, ObjectDetail.Fact::value)
                .contains(org.assertj.core.groups.Tuple.tuple("Image", "8abd7b73ff15"),
                        org.assertj.core.groups.Tuple.tuple("ReplicaSets", "874f94d9 current, 1 kept"));
        assertThat(detail.related()).extracting(ObjectDetail.Link::name)
                .contains("booking-service-874f94d9-mfnlb");
    }

    /** A pod that is not yet scheduled has no container statuses at all. */
    @Test
    void aPendingPodWithNoContainerStatusesDoesNotThrow() {
        Pod pending = new PodBuilder().withNewMetadata().withName("queue-gate-x")
                .withCreationTimestamp("2026-09-25T07:59:00Z").endMetadata()
                .withNewStatus().withPhase("Pending").endStatus().build();

        ObjectDetail detail = WorkloadDescriber.pod(pending, NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.WARN);
        assertThat(detail.headline()).isEqualTo("Pending");
    }

    @Test
    void aCrashLoopingPodIsBadAndNamesTheReason() {
        Pod crashing = new PodBuilder().withNewMetadata().withName("queue-gate-y").endMetadata()
                .withNewStatus().withPhase("Pending")
                .addNewInitContainerStatus().withName("dynatrace-agent").withRestartCount(6)
                    .withNewState().withNewWaiting().withReason("CrashLoopBackOff").endWaiting().endState()
                .endInitContainerStatus()
                .endStatus().build();

        ObjectDetail detail = WorkloadDescriber.pod(crashing, NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("CrashLoopBackOff in dynatrace-agent");
    }

    /**
     * An HPA whose target sits at 0 reports ScalingActive=False and stands
     * still. That is the second half of the 2026-09-25 outage.
     */
    @Test
    void anHpaThatHasStoppedScalingIsBad() {
        HorizontalPodAutoscaler hpa = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("queue-gate").endMetadata()
                .withNewSpec().withMinReplicas(2).withMaxReplicas(10).endSpec()
                .withNewStatus().withCurrentReplicas(0).withDesiredReplicas(0)
                .addNewCondition().withType("ScalingActive").withStatus("False")
                    .withReason("ScalingDisabled").withMessage("scaling is disabled since the replica count of the target is zero")
                .endCondition()
                .endStatus().build();

        ObjectDetail detail = WorkloadDescriber.hpa(hpa, NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("not scaling: ScalingDisabled");
    }

    /** Freshly created: no status, no metrics. Still a description. */
    @Test
    void anHpaWithNoStatusYetDoesNotThrow() {
        HorizontalPodAutoscaler hpa = new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName("booking-service").endMetadata()
                .withNewSpec().withMinReplicas(2).withMaxReplicas(4).endSpec().build();

        ObjectDetail detail = WorkloadDescriber.hpa(hpa, NOW);

        assertThat(detail.headline()).isEqualTo("0 of 2-4");
    }

    private static Deployment deployment(String name, int desired, Integer available) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withNewSpec().withReplicas(desired)
                    .withNewStrategy().withNewRollingUpdate()
                        .withMaxSurge(new IntOrString(1)).withMaxUnavailable(new IntOrString(0))
                    .endRollingUpdate().endStrategy()
                    .withNewTemplate().withNewSpec().addNewContainer().withName(name)
                        .withImage("ghcr.io/marwanbukhori/" + name + ":8abd7b73ff15deadbeef")
                    .endContainer().endSpec().endTemplate()
                .endSpec()
                .withNewStatus().withAvailableReplicas(available).endStatus()
                .build();
    }

    private static ReplicaSet replicaSet(String name, String revision, int replicas) {
        return new ReplicaSetBuilder().withNewMetadata().withName(name)
                .addToAnnotations("deployment.kubernetes.io/revision", revision).endMetadata()
                .withNewSpec().withReplicas(replicas).endSpec().build();
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `cd console && ./mvnw -q test -Dtest=WorkloadDescriberTest`
Expected: compilation failure, `cannot find symbol: class WorkloadDescriber`.

- [ ] **Step 3: Implement**

`Facts.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.IntOrString;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** The small formatting every describer needs, in one place so they agree. */
final class Facts {

    static final String NONE = "—";

    private Facts() {
    }

    static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** "12m", "3h", "2d" from an ISO timestamp, or {@link #NONE}. */
    static String age(String iso, Instant now) {
        if (iso == null) {
            return NONE;
        }
        try {
            Duration d = Duration.between(Instant.parse(iso), now);
            if (d.isNegative()) {
                return "0s";
            }
            if (d.toMinutes() < 1) {
                return d.toSeconds() + "s";
            }
            if (d.toHours() < 1) {
                return d.toMinutes() + "m";
            }
            if (d.toDays() < 1) {
                return d.toHours() + "h";
            }
            return d.toDays() + "d";
        } catch (DateTimeParseException e) {
            return NONE;
        }
    }

    /** The tag of an image, first 12 characters, which is how commits are shown everywhere else. */
    static String tag(String image) {
        if (image == null || !image.contains(":")) {
            return image == null ? NONE : "latest";
        }
        String tag = image.substring(image.lastIndexOf(':') + 1);
        return tag.length() > 12 ? tag.substring(0, 12) : tag;
    }

    static String intOrString(IntOrString value) {
        if (value == null) {
            return NONE;
        }
        return value.getIntVal() != null ? String.valueOf(value.getIntVal()) : value.getStrVal();
    }
}
```

`WorkloadDescriber.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerCondition;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static dev.marwan.console.objects.Facts.NONE;
import static dev.marwan.console.objects.Facts.orZero;
import static dev.marwan.console.objects.ObjectDetail.BAD;
import static dev.marwan.console.objects.ObjectDetail.NEUTRAL;
import static dev.marwan.console.objects.ObjectDetail.OK;
import static dev.marwan.console.objects.ObjectDetail.WARN;

/**
 * Deployments, pods and autoscalers, turned into what the inspector shows.
 *
 * Pure: every input is a model object already read, so each rule here is
 * tested without a cluster. Every field is treated as possibly absent, because
 * the objects that most need describing - a Deployment at 0, a pod that has
 * not started - are exactly the ones with the least status.
 */
final class WorkloadDescriber {

    private static final String REVISION = "deployment.kubernetes.io/revision";

    private WorkloadDescriber() {
    }

    static ObjectDetail deployment(Deployment d, List<ReplicaSet> replicaSets,
                                   HorizontalPodAutoscaler hpa, List<Pod> pods, Instant now) {
        String name = d.getMetadata().getName();
        int desired = orZero(d.getSpec().getReplicas());
        int available = d.getStatus() == null ? 0 : orZero(d.getStatus().getAvailableReplicas());

        String tone;
        String headline;
        if (desired == 0) {
            tone = BAD;
            headline = "scaled to 0 - nothing is serving";
        } else if (available == 0) {
            tone = BAD;
            headline = "0 of " + desired + " available";
        } else if (available < desired) {
            tone = WARN;
            headline = available + " of " + desired + " available";
        } else {
            tone = OK;
            headline = available + " of " + desired + " available";
        }

        List<Container> containers = d.getSpec().getTemplate().getSpec().getContainers();
        Container main = containers.isEmpty() ? null : containers.getFirst();

        List<ObjectDetail.Fact> facts = new ArrayList<>();
        facts.add(new ObjectDetail.Fact("Available", available + " / " + desired, tone));
        facts.add(new ObjectDetail.Fact("Image", main == null ? NONE : Facts.tag(main.getImage())));
        if (d.getSpec().getStrategy() != null && d.getSpec().getStrategy().getRollingUpdate() != null) {
            var rolling = d.getSpec().getStrategy().getRollingUpdate();
            facts.add(new ObjectDetail.Fact("Rollout", "surge " + Facts.intOrString(rolling.getMaxSurge())
                    + ", unavailable " + Facts.intOrString(rolling.getMaxUnavailable())));
        }
        facts.add(new ObjectDetail.Fact("ReplicaSets", replicaSetsFact(replicaSets)));
        if (main != null) {
            facts.add(new ObjectDetail.Fact("Probes", probes(main)));
        }
        if (hpa != null) {
            facts.add(new ObjectDetail.Fact("Autoscaler", hpaPosition(hpa)));
        }

        List<ObjectDetail.Link> related = new ArrayList<>();
        if (hpa != null) {
            related.add(new ObjectDetail.Link("hpa", hpa.getMetadata().getName(), "HPA", null));
        }
        related.add(new ObjectDetail.Link("service", name, "Service", null));
        for (Pod pod : pods) {
            related.add(new ObjectDetail.Link("pod", pod.getMetadata().getName(), "Pod", podTone(pod)));
        }
        return new ObjectDetail("deployment", name, true, null, tone, headline, facts, related, List.of());
    }

    static ObjectDetail pod(Pod p, Instant now) {
        String name = p.getMetadata().getName();
        String phase = p.getStatus() == null || p.getStatus().getPhase() == null
                ? "Unknown" : p.getStatus().getPhase();
        Optional<ContainerStatus> waiting = allStatuses(p)
                .filter(s -> s.getState() != null && s.getState().getWaiting() != null
                        && s.getState().getWaiting().getReason() != null)
                .filter(s -> !"PodInitializing".equals(s.getState().getWaiting().getReason())
                        && !"ContainerCreating".equals(s.getState().getWaiting().getReason()))
                .findFirst();
        List<ContainerStatus> main = p.getStatus() == null || p.getStatus().getContainerStatuses() == null
                ? List.of() : p.getStatus().getContainerStatuses();
        boolean ready = !main.isEmpty() && main.stream().allMatch(s -> Boolean.TRUE.equals(s.getReady()));
        int restarts = allStatuses(p).mapToInt(s -> orZero(s.getRestartCount())).sum();

        String tone;
        String headline;
        if (waiting.isPresent()) {
            tone = BAD;
            headline = waiting.get().getState().getWaiting().getReason() + " in " + waiting.get().getName();
        } else if ("Running".equals(phase) && ready) {
            tone = OK;
            headline = "Running, ready";
        } else if ("Running".equals(phase)) {
            tone = WARN;
            headline = "Running, not ready";
        } else if ("Succeeded".equals(phase)) {
            tone = NEUTRAL;
            headline = "Completed";
        } else if ("Failed".equals(phase)) {
            tone = BAD;
            headline = "Failed";
        } else {
            tone = WARN;
            headline = phase;
        }

        List<ObjectDetail.Fact> facts = new ArrayList<>();
        facts.add(new ObjectDetail.Fact("Phase", phase));
        facts.add(new ObjectDetail.Fact("Ready", ready ? "yes" : "no", ready ? OK : WARN));
        facts.add(new ObjectDetail.Fact("Restarts", String.valueOf(restarts), restarts > 0 ? WARN : null));
        facts.add(new ObjectDetail.Fact("Node", p.getSpec() == null || p.getSpec().getNodeName() == null
                ? NONE : p.getSpec().getNodeName()));
        facts.add(new ObjectDetail.Fact("Age", Facts.age(p.getMetadata().getCreationTimestamp(), now)));

        List<ObjectDetail.Link> related = new ArrayList<>();
        for (OwnerReference owner : owners(p)) {
            if ("ReplicaSet".equals(owner.getKind())) {
                String rs = owner.getName();
                facts.add(new ObjectDetail.Fact("ReplicaSet", rs));
                String deployment = rs.contains("-") ? rs.substring(0, rs.lastIndexOf('-')) : rs;
                related.add(new ObjectDetail.Link("deployment", deployment, "Deployment", null));
            } else if ("Job".equals(owner.getKind())) {
                related.add(new ObjectDetail.Link("job", owner.getName(), "Job", null));
            }
        }
        return new ObjectDetail("pod", name, true, null, tone, headline, facts, related, List.of());
    }

    static ObjectDetail hpa(HorizontalPodAutoscaler h, Instant now) {
        String name = h.getMetadata().getName();
        Optional<HorizontalPodAutoscalerCondition> scalingActive = conditions(h)
                .filter(c -> "ScalingActive".equals(c.getType())).findFirst();

        String tone;
        String headline;
        if (scalingActive.isPresent() && "False".equals(scalingActive.get().getStatus())) {
            tone = BAD;
            headline = "not scaling: " + scalingActive.get().getReason();
        } else {
            tone = OK;
            headline = hpaPosition(h);
        }

        List<ObjectDetail.Fact> facts = new ArrayList<>();
        facts.add(new ObjectDetail.Fact("Replicas", hpaPosition(h)));
        facts.add(new ObjectDetail.Fact("Desired", h.getStatus() == null
                ? NONE : String.valueOf(orZero(h.getStatus().getDesiredReplicas()))));
        facts.add(new ObjectDetail.Fact("CPU", cpu(h)));
        facts.add(new ObjectDetail.Fact("Last scaled", h.getStatus() == null
                ? NONE : Facts.age(h.getStatus().getLastScaleTime(), now) + " ago"));
        conditions(h).forEach(c -> facts.add(new ObjectDetail.Fact(c.getType(),
                c.getStatus() + (c.getReason() == null ? "" : " (" + c.getReason() + ")"),
                "True".equals(c.getStatus()) ? null : BAD)));

        String target = h.getSpec().getScaleTargetRef() == null
                ? name : h.getSpec().getScaleTargetRef().getName();
        List<ObjectDetail.Link> related = List.of(
                new ObjectDetail.Link("deployment", target, "Deployment", null));
        return new ObjectDetail("hpa", name, true, null, tone, headline, facts, related, List.of());
    }

    /** "2 of 2-4": current replicas of the allowed range. */
    static String hpaPosition(HorizontalPodAutoscaler h) {
        int current = h.getStatus() == null ? 0 : orZero(h.getStatus().getCurrentReplicas());
        return current + " of " + orZero(h.getSpec().getMinReplicas()) + "-" + h.getSpec().getMaxReplicas();
    }

    static String podTone(Pod pod) {
        return pod(pod, Instant.EPOCH).tone();
    }

    private static String replicaSetsFact(List<ReplicaSet> replicaSets) {
        if (replicaSets.isEmpty()) {
            return NONE;
        }
        List<ReplicaSet> byRevision = new ArrayList<>(replicaSets);
        byRevision.sort(Comparator.comparingLong(WorkloadDescriber::revision).reversed());
        String current = byRevision.getFirst().getMetadata().getName();
        String hash = current.substring(current.lastIndexOf('-') + 1);
        return hash + " current, " + (byRevision.size() - 1) + " kept";
    }

    private static long revision(ReplicaSet rs) {
        var annotations = rs.getMetadata().getAnnotations();
        try {
            return annotations == null ? 0 : Long.parseLong(annotations.getOrDefault(REVISION, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String probes(Container c) {
        List<String> present = new ArrayList<>();
        if (c.getStartupProbe() != null) {
            present.add("startup");
        }
        if (c.getLivenessProbe() != null) {
            present.add("liveness");
        }
        if (c.getReadinessProbe() != null) {
            present.add("readiness");
        }
        return present.isEmpty() ? "none" : String.join(", ", present);
    }

    private static String cpu(HorizontalPodAutoscaler h) {
        Integer target = h.getSpec().getMetrics() == null ? null : h.getSpec().getMetrics().stream()
                .filter(m -> m.getResource() != null && "cpu".equals(m.getResource().getName()))
                .map(MetricSpec::getResource)
                .map(r -> r.getTarget() == null ? null : r.getTarget().getAverageUtilization())
                .findFirst().orElse(null);
        Integer current = h.getStatus() == null || h.getStatus().getCurrentMetrics() == null ? null
                : h.getStatus().getCurrentMetrics().stream()
                    .filter(m -> m.getResource() != null && "cpu".equals(m.getResource().getName()))
                    .map(MetricStatus::getResource)
                    .map(r -> r.getCurrent() == null ? null : r.getCurrent().getAverageUtilization())
                    .findFirst().orElse(null);
        return (current == null ? "unknown" : current + "%")
                + " of " + (target == null ? NONE : target + "%") + " target";
    }

    private static Stream<ContainerStatus> allStatuses(Pod p) {
        if (p.getStatus() == null) {
            return Stream.empty();
        }
        List<ContainerStatus> init = p.getStatus().getInitContainerStatuses() == null
                ? List.of() : p.getStatus().getInitContainerStatuses();
        List<ContainerStatus> main = p.getStatus().getContainerStatuses() == null
                ? List.of() : p.getStatus().getContainerStatuses();
        return Stream.concat(init.stream(), main.stream());
    }

    private static Stream<HorizontalPodAutoscalerCondition> conditions(HorizontalPodAutoscaler h) {
        return h.getStatus() == null || h.getStatus().getConditions() == null
                ? Stream.empty() : h.getStatus().getConditions().stream();
    }

    private static List<OwnerReference> owners(Pod p) {
        return p.getMetadata().getOwnerReferences() == null ? List.of() : p.getMetadata().getOwnerReferences();
    }
}
```

- [ ] **Step 4: Run to see it pass**

Run: `cd console && ./mvnw -q test -Dtest=WorkloadDescriberTest`
Expected: exit 0; surefire report for `WorkloadDescriberTest` shows `tests="7" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/objects console/src/test/java/dev/marwan/console/objects
git commit -m "Describe deployments, pods and autoscalers, including the ones with no status"
```

---

### Task 4: Describing Jobs, the CronJob, Routes, Services and events

**Files:**
- Modify: `console/src/main/java/dev/marwan/console/objects/WorkloadDescriber.java` (add `job`, `cronJob`)
- Create: `console/src/main/java/dev/marwan/console/objects/RouteProbe.java`
- Create: `console/src/main/java/dev/marwan/console/objects/NetworkDescriber.java`
- Create: `console/src/main/java/dev/marwan/console/objects/EventLines.java`
- Test: `console/src/test/java/dev/marwan/console/objects/NetworkDescriberTest.java`
- Test: `console/src/test/java/dev/marwan/console/objects/EventLinesTest.java`
- Test: add to `console/src/test/java/dev/marwan/console/objects/WorkloadDescriberTest.java`

**Interfaces:**
- Consumes: Task 2 model, Task 3 `Facts`, `WorkloadDescriber.podTone`.
- Produces:
  - `WorkloadDescriber.job(Job j, List<Pod> pods, Instant now)` and `WorkloadDescriber.cronJob(CronJob c, List<Job> runs, Instant now)`, both returning `ObjectDetail`
  - `WorkloadDescriber.jobSummary(Job j)` returning `ObjectSummary`
  - `record RouteProbe(int status, long millis, boolean appAnswered, String error)` with `static RouteProbe failed(String error)`
  - `NetworkDescriber.route(GenericKubernetesResource route, RouteProbe probeOrNull)` and `NetworkDescriber.service(Service s, List<EndpointSlice> slices, List<NetworkPolicy> policies)`
  - `EventLines.from(List<Event> events)` returning `List<ObjectDetail.EventLine>`, newest first, at most 20

- [ ] **Step 1: Write the failing tests**

`NetworkDescriberTest.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkDescriberTest {

    /**
     * What "Connection refused" meant on 2026-09-25: a Service with nothing
     * behind it. The inspector must say that in the headline.
     */
    @Test
    void aServiceWithNoReadyEndpointsIsBad() {
        ObjectDetail detail = NetworkDescriber.service(service("booking-service"), List.of(), List.of());

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("0 ready endpoints - connections will be refused");
    }

    @Test
    void readyEndpointsAreCountedAndLinked() {
        EndpointSlice slice = new EndpointSliceBuilder().withNewMetadata().withName("booking-service-abc").endMetadata()
                .addNewEndpoint().withNewConditions().withReady(true).endConditions()
                    .withNewTargetRef().withKind("Pod").withName("booking-service-874f94d9-mfnlb").endTargetRef()
                .endEndpoint()
                .addNewEndpoint().withNewConditions().withReady(false).endConditions()
                    .withNewTargetRef().withKind("Pod").withName("booking-service-874f94d9-79ttd").endTargetRef()
                .endEndpoint()
                .build();

        ObjectDetail detail = NetworkDescriber.service(service("booking-service"), List.of(slice), List.of());

        assertThat(detail.tone()).isEqualTo(ObjectDetail.WARN);
        assertThat(detail.headline()).isEqualTo("1 of 2 endpoints ready");
        assertThat(detail.related()).extracting(ObjectDetail.Link::name)
                .contains("booking-service-874f94d9-mfnlb", "booking-service-874f94d9-79ttd");
    }

    @Test
    void aNetworkPolicySelectingTheServicesPodsIsNamedWithWhoItAdmits() {
        NetworkPolicy policy = new NetworkPolicyBuilder().withNewMetadata()
                .withName("booking-service-from-gate-only").endMetadata()
                .withNewSpec().withNewPodSelector().addToMatchLabels("app", "booking-service").endPodSelector()
                    .addNewIngress().addNewFrom().withNewPodSelector().addToMatchLabels("app", "queue-gate")
                    .endPodSelector().endFrom().endIngress()
                .endSpec().build();

        ObjectDetail detail = NetworkDescriber.service(service("booking-service"), List.of(), List.of(policy));

        assertThat(detail.facts()).extracting(ObjectDetail.Fact::label, ObjectDetail.Fact::value)
                .contains(org.assertj.core.groups.Tuple.tuple("NetworkPolicy",
                        "booking-service-from-gate-only: ingress from app=queue-gate only"));
    }

    @Test
    void aRouteWhoseAppAnswersIsOk() {
        ObjectDetail detail = NetworkDescriber.route(route("queue-gate"), new RouteProbe(404, 38, true, null));

        assertThat(detail.tone()).isEqualTo(ObjectDetail.OK);
        assertThat(detail.headline()).isEqualTo("app answering in 38 ms");
    }

    /** The router's own "Application is not available" page is not the app answering. */
    @Test
    void aRouteAnsweredOnlyByTheRouterIsBad() {
        ObjectDetail detail = NetworkDescriber.route(route("queue-gate"), new RouteProbe(503, 12, false, null));

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("503 from the router - nothing behind it");
    }

    @Test
    void aRouteThatDoesNotAnswerAtAllSaysSo() {
        ObjectDetail detail = NetworkDescriber.route(route("queue-gate"), RouteProbe.failed("timed out after 3s"));

        assertThat(detail.tone()).isEqualTo(ObjectDetail.BAD);
        assertThat(detail.headline()).isEqualTo("no answer: timed out after 3s");
    }

    private static Service service(String name) {
        return new ServiceBuilder().withNewMetadata().withName(name).endMetadata()
                .withNewSpec().addToSelector("app", name)
                    .addNewPort().withName("http").withPort(8081).endPort()
                .endSpec().build();
    }

    private static GenericKubernetesResource route(String name) {
        GenericKubernetesResource route = new GenericKubernetesResource();
        route.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().withName(name).build());
        route.setAdditionalProperty("spec", Map.of(
                "host", name + "-marwanbukhori-dev.apps.example.com",
                "to", Map.of("kind", "Service", "name", name),
                "port", Map.of("targetPort", "http"),
                "tls", Map.of("termination", "edge", "insecureEdgeTerminationPolicy", "Redirect")));
        return route;
    }
}
```

`EventLinesTest.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.MicroTime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventLinesTest {

    /** Newer events carry only eventTime. They must sort among the rest, not fall to the bottom. */
    @Test
    void eventsWithOnlyAnEventTimeSortByIt() {
        Event old = new EventBuilder().withType("Normal").withReason("Pulled")
                .withLastTimestamp("2026-09-25T07:00:00Z").withCount(1).build();
        Event recent = new EventBuilder().withType("Warning").withReason("Unhealthy")
                .withEventTime(new MicroTime("2026-09-25T07:30:00.000000Z")).build();

        List<ObjectDetail.EventLine> lines = EventLines.from(List.of(old, recent));

        assertThat(lines).extracting(ObjectDetail.EventLine::reason).containsExactly("Unhealthy", "Pulled");
        assertThat(lines.getFirst().at()).isEqualTo("2026-09-25T07:30:00Z");
        assertThat(lines.getFirst().count()).isEqualTo(1);
    }

    @Test
    void anEventWithNoTimeAtAllIsKeptLast() {
        Event timeless = new EventBuilder().withType("Normal").withReason("Mystery").build();
        Event timed = new EventBuilder().withType("Normal").withReason("Created")
                .withLastTimestamp("2026-09-25T07:00:00Z").build();

        assertThat(EventLines.from(List.of(timeless, timed)))
                .extracting(ObjectDetail.EventLine::reason).containsExactly("Created", "Mystery");
    }

    @Test
    void atMostTwentyAreKept() {
        List<Event> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new EventBuilder().withReason("r" + i)
                    .withLastTimestamp(String.format("2026-09-25T07:%02d:00Z", i)).build());
        }

        assertThat(EventLines.from(many)).hasSize(20).first()
                .extracting(ObjectDetail.EventLine::reason).isEqualTo("r29");
    }
}
```

Add to `WorkloadDescriberTest.java` (new imports: `io.fabric8.kubernetes.api.model.batch.v1.CronJob`, `CronJobBuilder`, `Job`, `JobBuilder`):

```java
    @Test
    void aCompletedJobReportsItsDuration() {
        Job job = new JobBuilder().withNewMetadata().withName("load-d-3fa951d5")
                .addToLabels("app", "rembayung-load").endMetadata()
                .withNewStatus().withSucceeded(1)
                    .withStartTime("2026-09-25T07:23:00Z").withCompletionTime("2026-09-25T07:24:50Z")
                .endStatus().build();

        ObjectDetail detail = WorkloadDescriber.job(job, List.of(), NOW);

        assertThat(detail.tone()).isEqualTo(ObjectDetail.OK);
        assertThat(detail.headline()).isEqualTo("Complete in 110s");
    }

    /** The keepalive's schedule is five-field cron in UTC. The next run is what a reader wants. */
    @Test
    void theCronJobSaysWhenItNextRuns() {
        CronJob keepalive = new CronJobBuilder().withNewMetadata().withName("keepalive").endMetadata()
                .withNewSpec().withSchedule("0 2,10,18 * * *").endSpec()
                .withNewStatus().withLastSuccessfulTime("2026-09-25T02:00:49Z").endStatus().build();

        ObjectDetail detail = WorkloadDescriber.cronJob(keepalive, List.of(), NOW);

        assertThat(detail.facts()).extracting(ObjectDetail.Fact::label, ObjectDetail.Fact::value)
                .contains(org.assertj.core.groups.Tuple.tuple("Next run", "2026-09-25T10:00:00Z"));
    }
```

- [ ] **Step 2: Run to see them fail**

Run: `cd console && ./mvnw -q test -Dtest='NetworkDescriberTest,EventLinesTest,WorkloadDescriberTest'`
Expected: compilation failure naming `NetworkDescriber`, `RouteProbe`, `EventLines`, `job`, `cronJob`.

- [ ] **Step 3: Implement**

`RouteProbe.java`:

```java
package dev.marwan.console.objects;

/**
 * What a public host said when asked for "/".
 *
 * @param status      HTTP status, or -1 when nothing answered
 * @param appAnswered the body came from our app (JSON), not the router's HTML
 *                    page, which answers 503 when a Route has nothing behind it
 * @param error       why nothing answered, or null
 */
public record RouteProbe(int status, long millis, boolean appAnswered, String error) {

    static RouteProbe failed(String error) {
        return new RouteProbe(-1, -1, false, error);
    }
}
```

`NetworkDescriber.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.marwan.console.objects.Facts.NONE;
import static dev.marwan.console.objects.ObjectDetail.BAD;
import static dev.marwan.console.objects.ObjectDetail.OK;
import static dev.marwan.console.objects.ObjectDetail.WARN;

/**
 * Routes and Services: how a request gets in, and whether anything is there
 * to receive it.
 */
final class NetworkDescriber {

    private NetworkDescriber() {
    }

    /** Routes are read as generic resources, as ClusterStateProvider does, to avoid openshift-client. */
    static ObjectDetail route(GenericKubernetesResource route, RouteProbe probe) {
        String name = route.getMetadata().getName();
        Map<?, ?> spec = route.getAdditionalProperties().get("spec") instanceof Map<?, ?> m ? m : Map.of();
        String host = String.valueOf(spec.get("host"));
        String target = spec.get("to") instanceof Map<?, ?> to ? String.valueOf(to.get("name")) : NONE;
        String port = spec.get("port") instanceof Map<?, ?> p ? String.valueOf(p.get("targetPort")) : NONE;
        String tls = spec.get("tls") instanceof Map<?, ?> t
                ? t.get("termination") + ", http " + String.valueOf(t.get("insecureEdgeTerminationPolicy")).toLowerCase()
                : "none";

        String tone;
        String headline;
        if (probe == null) {
            tone = WARN;
            headline = "not checked";
        } else if (probe.error() != null) {
            tone = BAD;
            headline = "no answer: " + probe.error();
        } else if (probe.appAnswered()) {
            tone = OK;
            headline = "app answering in " + probe.millis() + " ms";
        } else {
            tone = BAD;
            headline = probe.status() + " from the router - nothing behind it";
        }

        List<ObjectDetail.Fact> facts = List.of(
                new ObjectDetail.Fact("Host", host),
                new ObjectDetail.Fact("TLS", tls),
                new ObjectDetail.Fact("Target", target + " : " + port),
                new ObjectDetail.Fact("Answer", headline, tone));
        List<ObjectDetail.Link> related = List.of(new ObjectDetail.Link("service", target, "Service", null));
        return new ObjectDetail("route", name, true, null, tone, headline, facts, related, List.of());
    }

    static ObjectDetail service(Service s, List<EndpointSlice> slices, List<NetworkPolicy> policies) {
        String name = s.getMetadata().getName();
        Map<String, String> selector = s.getSpec().getSelector() == null ? Map.of() : s.getSpec().getSelector();

        List<Endpoint> endpoints = slices.stream()
                .flatMap(slice -> slice.getEndpoints() == null ? java.util.stream.Stream.empty() : slice.getEndpoints().stream())
                .toList();
        long ready = endpoints.stream()
                .filter(e -> e.getConditions() != null && Boolean.TRUE.equals(e.getConditions().getReady()))
                .count();

        String tone;
        String headline;
        if (ready == 0) {
            tone = BAD;
            headline = "0 ready endpoints - connections will be refused";
        } else if (ready < endpoints.size()) {
            tone = WARN;
            headline = ready + " of " + endpoints.size() + " endpoints ready";
        } else {
            tone = OK;
            headline = ready + " ready " + (ready == 1 ? "endpoint" : "endpoints");
        }

        List<ObjectDetail.Fact> facts = new ArrayList<>();
        facts.add(new ObjectDetail.Fact("Selector", selector.isEmpty() ? NONE : labels(selector)));
        facts.add(new ObjectDetail.Fact("Ports", s.getSpec().getPorts() == null ? NONE
                : s.getSpec().getPorts().stream().map(NetworkDescriber::port).collect(Collectors.joining(", "))));
        facts.add(new ObjectDetail.Fact("Endpoints", ready + " ready of " + endpoints.size(), tone));
        for (NetworkPolicy policy : policies) {
            Map<String, String> selects = policy.getSpec().getPodSelector() == null
                    || policy.getSpec().getPodSelector().getMatchLabels() == null
                    ? Map.of() : policy.getSpec().getPodSelector().getMatchLabels();
            if (!selects.isEmpty() && selector.entrySet().containsAll(selects.entrySet())) {
                facts.add(new ObjectDetail.Fact("NetworkPolicy",
                        policy.getMetadata().getName() + ": " + admits(policy)));
            }
        }

        List<ObjectDetail.Link> related = new ArrayList<>();
        for (Endpoint e : endpoints) {
            if (e.getTargetRef() != null && "Pod".equals(e.getTargetRef().getKind())) {
                boolean isReady = e.getConditions() != null && Boolean.TRUE.equals(e.getConditions().getReady());
                related.add(new ObjectDetail.Link("pod", e.getTargetRef().getName(), "Pod", isReady ? OK : WARN));
            }
        }
        related.add(new ObjectDetail.Link("deployment", name, "Deployment", null));
        return new ObjectDetail("service", name, true, null, tone, headline, facts, related, List.of());
    }

    private static String admits(NetworkPolicy policy) {
        if (policy.getSpec().getIngress() == null || policy.getSpec().getIngress().isEmpty()) {
            return "denies all ingress";
        }
        List<String> sources = policy.getSpec().getIngress().stream()
                .flatMap(rule -> rule.getFrom() == null ? java.util.stream.Stream.empty() : rule.getFrom().stream())
                .map(NetworkPolicyPeer::getPodSelector)
                .filter(selector -> selector != null && selector.getMatchLabels() != null)
                .map(selector -> labels(selector.getMatchLabels()))
                .toList();
        return sources.isEmpty() ? "ingress rules without a pod selector"
                : "ingress from " + String.join(", ", sources) + " only";
    }

    private static String labels(Map<String, String> labels) {
        return labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private static String port(ServicePort p) {
        return (p.getName() == null ? "" : p.getName() + ":") + p.getPort();
    }
}
```

`EventLines.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Event;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;

/**
 * Kubernetes Events for one object, newest first, as the inspector lists them.
 *
 * Two timestamp fields exist and events use either: the older lastTimestamp,
 * or eventTime alone on events from newer controllers. Sorting by only one of
 * them puts the other kind at the bottom, which is where the probe failures
 * of a rollout would have ended up.
 */
final class EventLines {

    static final int LIMIT = 20;

    private EventLines() {
    }

    static List<ObjectDetail.EventLine> from(List<Event> events) {
        return events.stream()
                .map(e -> new ObjectDetail.EventLine(when(e), e.getType(), e.getReason(), e.getMessage(),
                        e.getCount() == null ? 1 : e.getCount()))
                .sorted(Comparator.comparing(ObjectDetail.EventLine::at,
                        Comparator.nullsLast(Comparator.<String>reverseOrder())))
                .limit(LIMIT)
                .toList();
    }

    /** Normalised to second-precision ISO so lexical order is time order. */
    private static String when(Event e) {
        String raw = e.getLastTimestamp() != null ? e.getLastTimestamp()
                : e.getEventTime() != null ? e.getEventTime().getTime()
                : e.getFirstTimestamp();
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
```

Add to `WorkloadDescriber.java` (new imports: `io.fabric8.kubernetes.api.model.batch.v1.CronJob`, `io.fabric8.kubernetes.api.model.batch.v1.Job`, `org.springframework.scheduling.support.CronExpression`, `java.time.Duration`, `java.time.ZoneOffset`, `java.time.ZonedDateTime`):

```java
    static ObjectDetail job(Job j, List<Pod> pods, Instant now) {
        String name = j.getMetadata().getName();
        var status = j.getStatus();
        int succeeded = status == null ? 0 : orZero(status.getSucceeded());
        int failed = status == null ? 0 : orZero(status.getFailed());
        int active = status == null ? 0 : orZero(status.getActive());
        String started = status == null ? null : status.getStartTime();
        String finished = status == null ? null : status.getCompletionTime();

        String tone;
        String headline;
        if (succeeded > 0 && finished != null) {
            tone = OK;
            headline = "Complete in " + seconds(started, finished) + "s";
        } else if (active > 0) {
            tone = WARN;
            headline = "Running for " + Facts.age(started, now);
        } else if (failed > 0) {
            tone = BAD;
            headline = "Failed after " + failed + (failed == 1 ? " attempt" : " attempts");
        } else {
            tone = WARN;
            headline = "Waiting to start";
        }

        List<ObjectDetail.Fact> facts = List.of(
                new ObjectDetail.Fact("Started", started == null ? NONE : started),
                new ObjectDetail.Fact("Finished", finished == null ? NONE : finished),
                new ObjectDetail.Fact("Attempts", succeeded + " succeeded, " + failed + " failed",
                        failed > 0 ? WARN : null),
                new ObjectDetail.Fact("Kind of run", isLoad(j) ? "load run (a rush)" : "keepalive"));

        List<ObjectDetail.Link> related = new ArrayList<>();
        for (Pod pod : pods) {
            related.add(new ObjectDetail.Link("pod", pod.getMetadata().getName(), "Pod", podTone(pod)));
        }
        if (!isLoad(j)) {
            related.add(new ObjectDetail.Link("cronjob", "keepalive", "CronJob", null));
        }
        return new ObjectDetail("job", name, true, null, tone, headline, facts, related, List.of());
    }

    static ObjectSummary jobSummary(Job j) {
        ObjectDetail detail = job(j, List.of(), Instant.now());
        return new ObjectSummary("job", j.getMetadata().getName(), detail.tone(),
                (isLoad(j) ? "rush: " : "keepalive: ") + detail.headline(),
                j.getStatus() == null ? null : j.getStatus().getStartTime());
    }

    static ObjectDetail cronJob(CronJob c, List<Job> runs, Instant now) {
        String name = c.getMetadata().getName();
        String schedule = c.getSpec().getSchedule();
        boolean suspended = Boolean.TRUE.equals(c.getSpec().getSuspend());
        String lastSuccess = c.getStatus() == null ? null : c.getStatus().getLastSuccessfulTime();
        String lastScheduled = c.getStatus() == null ? null : c.getStatus().getLastScheduleTime();

        String next = NONE;
        try {
            // Kubernetes cron has five fields; Spring's has six, seconds first.
            ZonedDateTime at = CronExpression.parse("0 " + schedule).next(now.atZone(ZoneOffset.UTC));
            next = at == null ? NONE : at.toInstant().toString();
        } catch (IllegalArgumentException e) {
            next = "unreadable schedule";
        }

        String tone = suspended ? WARN : OK;
        String headline = suspended ? "suspended" : "next run " + next;

        List<ObjectDetail.Fact> facts = List.of(
                new ObjectDetail.Fact("Schedule", schedule + " (UTC)"),
                new ObjectDetail.Fact("Next run", next),
                new ObjectDetail.Fact("Last scheduled", lastScheduled == null ? NONE : lastScheduled),
                new ObjectDetail.Fact("Last success", lastSuccess == null ? NONE : lastSuccess));

        List<ObjectDetail.Link> related = runs.stream()
                .sorted(Comparator.comparing((Job j) -> j.getMetadata().getName()).reversed())
                .map(j -> new ObjectDetail.Link("job", j.getMetadata().getName(), "Run", job(j, List.of(), now).tone()))
                .toList();
        return new ObjectDetail("cronjob", name, true, null, tone, headline, facts, related, List.of());
    }

    static boolean isLoad(Job j) {
        var labels = j.getMetadata().getLabels();
        return labels != null && "rembayung-load".equals(labels.get("app"));
    }

    private static long seconds(String from, String to) {
        try {
            return Duration.between(Instant.parse(from), Instant.parse(to)).toSeconds();
        } catch (RuntimeException e) {
            return 0;
        }
    }
```

- [ ] **Step 4: Run to see them pass**

Run: `cd console && ./mvnw -q test -Dtest='NetworkDescriberTest,EventLinesTest,WorkloadDescriberTest'`
Expected: exit 0; 6 + 3 + 9 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/objects console/src/test/java/dev/marwan/console/objects
git commit -m "Describe runs, the keepalive, routes and services, and order their events"
```

---

### Task 5: Reading the cluster, with scope, cache and failure isolation

**Files:**
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectSource.java`
- Create: `console/src/main/java/dev/marwan/console/objects/KubernetesObjectSource.java`
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectNotFound.java`
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectsProvider.java`
- Test: `console/src/test/java/dev/marwan/console/objects/FakeObjectSource.java`
- Test: `console/src/test/java/dev/marwan/console/objects/ObjectsProviderTest.java`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `interface ObjectSource` (methods below) and `@Component KubernetesObjectSource implements ObjectSource`
  - `class ObjectNotFound extends RuntimeException`
  - `@Component ObjectsProvider` with `ObjectDetail describe(String kind, String name)` (throws `ObjectNotFound`) and `List<ObjectSummary> recentJobs()`

- [ ] **Step 1: Write the fake and the failing tests**

`FakeObjectSource.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An in-memory cluster. Counts reads so caching can be observed. */
class FakeObjectSource implements ObjectSource {

    final List<Pod> pods = new ArrayList<>();
    final List<Job> jobs = new ArrayList<>();
    RuntimeException failWith;
    int reads;
    int resets;

    @Override public Optional<GenericKubernetesResource> route(String name) { read(); return Optional.empty(); }
    @Override public Optional<Service> service(String name) { read(); return Optional.empty(); }
    @Override public List<EndpointSlice> endpointSlices(String service) { read(); return List.of(); }
    @Override public List<NetworkPolicy> networkPolicies() { read(); return List.of(); }
    @Override public Optional<Deployment> deployment(String name) { read(); return Optional.empty(); }
    @Override public List<ReplicaSet> replicaSets(String deployment) { read(); return List.of(); }
    @Override public Optional<HorizontalPodAutoscaler> hpa(String name) { read(); return Optional.empty(); }
    @Override public Optional<CronJob> cronJob(String name) { read(); return Optional.empty(); }
    @Override public List<Event> events(String kind, String name) { read(); return List.of(); }
    @Override public RouteProbe probe(String host) { return new RouteProbe(200, 5, true, null); }
    @Override public void reset() { resets++; }

    @Override
    public Optional<Pod> pod(String name) {
        read();
        return pods.stream().filter(p -> p.getMetadata().getName().equals(name)).findFirst();
    }

    @Override
    public List<Pod> pods(String appLabel) {
        read();
        return pods;
    }

    @Override
    public Optional<Job> job(String name) {
        read();
        return jobs.stream().filter(j -> j.getMetadata().getName().equals(name)).findFirst();
    }

    @Override
    public List<Job> jobs() {
        read();
        return jobs;
    }

    private void read() {
        reads++;
        if (failWith != null) {
            throw failWith;
        }
    }
}
```

`ObjectsProviderTest.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectsProviderTest {

    private final FakeObjectSource source = new FakeObjectSource();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ObjectsProvider provider = new ObjectsProvider(source, clock);

    @Test
    void anUnknownKindIsNotFound() {
        assertThatThrownBy(() -> provider.describe("secret", "splunk-hec")).isInstanceOf(ObjectNotFound.class);
    }

    /** Review focus 1: a pod deleted by a rollout between two polls. */
    @Test
    void aPodThatNoLongerExistsIsNotFound() {
        assertThatThrownBy(() -> provider.describe("pod", "queue-gate-gone")).isInstanceOf(ObjectNotFound.class);
    }

    /** Review focus 4: a pod in our namespace that is not ours. */
    @Test
    void aPodOutsideTheProjectIsNotFoundEvenThoughItExists() {
        source.pods.add(new PodBuilder().withNewMetadata().withName("console-debug-fbfbz").endMetadata().build());

        assertThatThrownBy(() -> provider.describe("pod", "console-debug-fbfbz")).isInstanceOf(ObjectNotFound.class);
    }

    @Test
    void anUnreadableClusterIsAReasonNotAnException() {
        source.failWith = new IllegalStateException("API server refused");

        ObjectDetail detail = provider.describe("pod", "queue-gate-x");

        assertThat(detail.available()).isFalse();
        assertThat(detail.detail()).contains("API server refused");
        assertThat(source.resets).isEqualTo(1);
    }

    @Test
    void aSecondReadWithinTwoSecondsIsServedFromCache() {
        source.pods.add(pod("queue-gate-x"));

        provider.describe("pod", "queue-gate-x");
        int afterFirst = source.reads;
        provider.describe("pod", "queue-gate-x");
        assertThat(source.reads).isEqualTo(afterFirst);

        now.set(now.get().plus(Duration.ofSeconds(3)));
        provider.describe("pod", "queue-gate-x");
        assertThat(source.reads).isGreaterThan(afterFirst);
    }

    @Test
    void recentJobsAreNewestFirst() {
        source.jobs.add(new JobBuilder().withNewMetadata().withName("keepalive-1")
                .addNewOwnerReference().withKind("CronJob").withName("keepalive").endOwnerReference().endMetadata()
                .withNewStatus().withStartTime("2026-09-25T02:00:00Z").endStatus().build());
        source.jobs.add(new JobBuilder().withNewMetadata().withName("load-d-3fa951d5")
                .addToLabels("app", "rembayung-load").endMetadata()
                .withNewStatus().withStartTime("2026-09-25T07:23:00Z").endStatus().build());

        assertThat(provider.recentJobs()).extracting(ObjectSummary::name)
                .containsExactly("load-d-3fa951d5", "keepalive-1");
    }

    private static Pod pod(String name) {
        return new PodBuilder().withNewMetadata().withName(name).addToLabels("app", "queue-gate").endMetadata()
                .withNewStatus().withPhase("Running").endStatus().build();
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `cd console && ./mvnw -q test -Dtest=ObjectsProviderTest`
Expected: compilation failure naming `ObjectSource`, `ObjectsProvider`, `ObjectNotFound`.

- [ ] **Step 3: Implement**

`ObjectSource.java`:

```java
package dev.marwan.console.objects;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;

import java.util.List;
import java.util.Optional;

/**
 * Every read the inspector makes, in this namespace. An interface so the
 * provider's rules are tested against an in-memory cluster.
 */
public interface ObjectSource {
    Optional<GenericKubernetesResource> route(String name);
    Optional<Service> service(String name);
    List<EndpointSlice> endpointSlices(String service);
    List<NetworkPolicy> networkPolicies();
    Optional<Deployment> deployment(String name);
    List<ReplicaSet> replicaSets(String deployment);
    Optional<Pod> pod(String name);
    List<Pod> pods(String appLabel);
    Optional<HorizontalPodAutoscaler> hpa(String name);
    Optional<Job> job(String name);
    List<Job> jobs();
    Optional<CronJob> cronJob(String name);
    List<Event> events(String kind, String name);
    RouteProbe probe(String host);
    /** Drop a client whose call failed. */
    void reset();
}
```

`KubernetesObjectSource.java`:

```java
package dev.marwan.console.objects;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** {@link ObjectSource} over the console's one Kubernetes client. */
@Component
class KubernetesObjectSource implements ObjectSource {

    /** Review focus 5: a hanging host must not hold a request thread. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static final ResourceDefinitionContext ROUTES = new ResourceDefinitionContext.Builder()
            .withGroup("route.openshift.io").withVersion("v1").withPlural("routes").withNamespaced(true).build();

    private final KubernetesAccess kubernetes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    KubernetesObjectSource(KubernetesAccess kubernetes) {
        this.kubernetes = kubernetes;
    }

    private String ns() {
        return kubernetes.namespace();
    }

    @Override
    public Optional<GenericKubernetesResource> route(String name) {
        return Optional.ofNullable(kubernetes.client().genericKubernetesResources(ROUTES)
                .inNamespace(ns()).withName(name).get());
    }

    @Override
    public Optional<Service> service(String name) {
        return Optional.ofNullable(kubernetes.client().services().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<EndpointSlice> endpointSlices(String service) {
        return kubernetes.client().discovery().v1().endpointSlices().inNamespace(ns())
                .withLabel("kubernetes.io/service-name", service).list().getItems();
    }

    @Override
    public List<NetworkPolicy> networkPolicies() {
        return kubernetes.client().network().v1().networkPolicies().inNamespace(ns()).list().getItems();
    }

    @Override
    public Optional<Deployment> deployment(String name) {
        return Optional.ofNullable(kubernetes.client().apps().deployments().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<ReplicaSet> replicaSets(String deployment) {
        return kubernetes.client().apps().replicaSets().inNamespace(ns()).withLabel("app", deployment)
                .list().getItems().stream()
                .filter(rs -> rs.getMetadata().getOwnerReferences() != null && rs.getMetadata().getOwnerReferences()
                        .stream().anyMatch(o -> "Deployment".equals(o.getKind()) && deployment.equals(o.getName())))
                .toList();
    }

    @Override
    public Optional<Pod> pod(String name) {
        return Optional.ofNullable(kubernetes.client().pods().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<Pod> pods(String appLabel) {
        return kubernetes.client().pods().inNamespace(ns()).withLabel("app", appLabel).list().getItems();
    }

    @Override
    public Optional<HorizontalPodAutoscaler> hpa(String name) {
        return Optional.ofNullable(kubernetes.client().autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(ns()).withName(name).get());
    }

    @Override
    public Optional<Job> job(String name) {
        return Optional.ofNullable(kubernetes.client().batch().v1().jobs().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<Job> jobs() {
        return kubernetes.client().batch().v1().jobs().inNamespace(ns()).list().getItems();
    }

    @Override
    public Optional<CronJob> cronJob(String name) {
        return Optional.ofNullable(kubernetes.client().batch().v1().cronjobs().inNamespace(ns()).withName(name).get());
    }

    @Override
    public List<Event> events(String kind, String name) {
        return kubernetes.client().v1().events().inNamespace(ns())
                .withField("involvedObject.kind", kind)
                .withField("involvedObject.name", name)
                .list().getItems();
    }

    @Override
    public RouteProbe probe(String host) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("https://" + host + "/")).timeout(PROBE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - start) / 1_000_000;
            String body = response.body() == null ? "" : response.body().stripLeading();
            // Our apps answer in JSON (queue-gate) or serve the console page; the
            // router's "Application is not available" page is neither.
            boolean app = response.statusCode() < 500
                    && (body.startsWith("{") || body.contains("<title>Rembayung"));
            return new RouteProbe(response.statusCode(), millis, app, null);
        } catch (java.net.http.HttpTimeoutException e) {
            return RouteProbe.failed("timed out after " + PROBE_TIMEOUT.toSeconds() + "s");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return RouteProbe.failed(KubernetesAccess.summarise(e));
        }
    }

    @Override
    public void reset() {
        kubernetes.invalidate();
    }
}
```

`ObjectNotFound.java`:

```java
package dev.marwan.console.objects;

/** An unknown kind, an absent object, or one that is not this project's. All three answer 404. */
public class ObjectNotFound extends RuntimeException {
    public ObjectNotFound(String message) {
        super(message);
    }
}
```

`ObjectsProvider.java`:

```java
package dev.marwan.console.objects;

import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One object's description, for the inspector.
 *
 * Three rules sit here rather than in the describers: only this project's
 * objects are described (anything else is a 404, however it was named); a
 * reading is reused for two seconds, so ten people watching a rush cost the
 * API server what one does; and an unreadable cluster is a sentence, never an
 * exception, as it is everywhere else in this console.
 */
@Component
public class ObjectsProvider {

    static final Duration TTL = Duration.ofSeconds(2);
    static final int RECENT_JOBS = 20;

    private final ObjectSource source;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ObjectDetail detail, Instant at) { }

    public ObjectsProvider(ObjectSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public ObjectDetail describe(String kindName, String name) {
        ObjectKind kind = ObjectKind.parse(kindName)
                .orElseThrow(() -> new ObjectNotFound("no such kind: " + kindName));
        String key = kind.path() + "/" + name;
        Instant now = clock.instant();
        Cached held = cache.get(key);
        if (held != null && held.at().plus(TTL).isAfter(now)) {
            return held.detail();
        }
        ObjectDetail fresh;
        try {
            fresh = read(kind, name, now);
        } catch (ObjectNotFound e) {
            cache.remove(key);
            throw e;
        } catch (Throwable e) {
            source.reset();
            fresh = ObjectDetail.unavailable(kind, name, KubernetesAccess.summarise(e));
        }
        cache.put(key, new Cached(fresh, now));
        return fresh;
    }

    public List<ObjectSummary> recentJobs() {
        return source.jobs().stream()
                .filter(Scope::ours)
                .map(WorkloadDescriber::jobSummary)
                .sorted(Comparator.comparing(ObjectSummary::at, Comparator.nullsLast(Comparator.<String>reverseOrder())))
                .limit(RECENT_JOBS)
                .toList();
    }

    private ObjectDetail read(ObjectKind kind, String name, Instant now) {
        return switch (kind) {
            case ROUTE -> {
                var route = ours(source.route(name), kind, name);
                Object spec = route.getAdditionalProperties().get("spec");
                String host = spec instanceof Map<?, ?> m && m.get("host") != null ? String.valueOf(m.get("host")) : null;
                yield NetworkDescriber.route(route, host == null ? null : source.probe(host))
                        .withEvents(EventLines.from(source.events("Route", name)));
            }
            case SERVICE -> NetworkDescriber.service(ours(source.service(name), kind, name),
                            source.endpointSlices(name), source.networkPolicies())
                    .withEvents(EventLines.from(source.events("Service", name)));
            case DEPLOYMENT -> {
                Deployment d = ours(source.deployment(name), kind, name);
                yield WorkloadDescriber.deployment(d, source.replicaSets(name),
                                source.hpa(name).orElse(null), source.pods(name), now)
                        .withEvents(EventLines.from(source.events("Deployment", name)));
            }
            case POD -> WorkloadDescriber.pod(ours(source.pod(name), kind, name), now)
                    .withEvents(EventLines.from(source.events("Pod", name)));
            case HPA -> WorkloadDescriber.hpa(ours(source.hpa(name), kind, name), now)
                    .withEvents(EventLines.from(source.events("HorizontalPodAutoscaler", name)));
            case JOB -> {
                Job j = ours(source.job(name), kind, name);
                String app = WorkloadDescriber.isLoad(j) ? "rembayung-load" : null;
                var pods = app == null ? List.<io.fabric8.kubernetes.api.model.Pod>of()
                        : source.pods(app).stream().filter(p -> ownedBy(p, name)).toList();
                yield WorkloadDescriber.job(j, pods, now)
                        .withEvents(EventLines.from(source.events("Job", name)));
            }
            case CRONJOB -> {
                var cron = ours(source.cronJob(name), kind, name);
                List<Job> runs = source.jobs().stream().filter(j -> ownedBy(j, name)).toList();
                yield WorkloadDescriber.cronJob(cron, runs, now)
                        .withEvents(EventLines.from(source.events("CronJob", name)));
            }
        };
    }

    private static <T extends HasMetadata> T ours(Optional<T> found, ObjectKind kind, String name) {
        return found.filter(Scope::ours)
                .orElseThrow(() -> new ObjectNotFound(kind.path() + " " + name + " does not exist here"));
    }

    private static boolean ownedBy(HasMetadata object, String owner) {
        var refs = object.getMetadata().getOwnerReferences();
        return refs != null && refs.stream().anyMatch(r -> owner.equals(r.getName()));
    }
}
```

The `Clock` it takes is the bean `ConsoleConfiguration.clock()` already provides (`Clock.systemUTC()`); add nothing.

- [ ] **Step 4: Run to see them pass, then the whole suite**

Run: `cd console && ./mvnw -q test -Dtest=ObjectsProviderTest && ./mvnw -q test`
Expected: exit 0 for both. The full suite still passes, so nothing existing was disturbed.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java console/src/test/java
git commit -m "Read the inspector's objects with a scope rule, a two second cache, and no exceptions"
```

---

### Task 6: The endpoints

**Files:**
- Create: `console/src/main/java/dev/marwan/console/objects/ObjectsController.java`
- Test: `console/src/test/java/dev/marwan/console/objects/ObjectsControllerTest.java`

**Interfaces:**
- Consumes: `ObjectsProvider.describe`, `ObjectsProvider.recentJobs`, `ObjectNotFound`.
- Produces: `GET /api/objects/{kind}/{name}` returning `ObjectDetail` JSON or 404; `GET /api/objects?kind=job` returning `ObjectSummary[]`; any other `kind` value there is 404.

- [ ] **Step 1: Write the failing test**

```java
package dev.marwan.console.objects;

import dev.marwan.console.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ObjectsController.class)
@Import(ObjectsControllerTest.Properties.class)
class ObjectsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ObjectsProvider objects;

    /** Public, unkeyed: a GET, like every other read on this console. */
    @Test
    void anObjectIsDescribedWithoutAKey() throws Exception {
        given(objects.describe("pod", "queue-gate-x")).willReturn(new ObjectDetail("pod", "queue-gate-x",
                true, null, ObjectDetail.OK, "Running, ready", List.of(), List.of(), List.of()));

        mvc.perform(get("/api/objects/pod/queue-gate-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Running, ready"))
                .andExpect(jsonPath("$.tone").value("ok"));
    }

    @Test
    void somethingNotFoundIs404() throws Exception {
        given(objects.describe("pod", "gone")).willThrow(new ObjectNotFound("pod gone does not exist here"));

        mvc.perform(get("/api/objects/pod/gone")).andExpect(status().isNotFound());
    }

    @Test
    void recentJobsAreListed() throws Exception {
        given(objects.recentJobs()).willReturn(List.of(
                new ObjectSummary("job", "load-d-3fa951d5", "ok", "rush: Complete in 110s", "2026-09-25T07:23:00Z")));

        mvc.perform(get("/api/objects").param("kind", "job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("load-d-3fa951d5"));
    }

    @Test
    void listingAnyOtherKindIs404() throws Exception {
        mvc.perform(get("/api/objects").param("kind", "secret")).andExpect(status().isNotFound());
    }

    static class Properties {
        @Bean
        ConsoleProperties consoleProperties() {
            return new ConsoleProperties("http://booking-service:8081", "http://queue-gate:8080",
                    "default", 1, Duration.ofSeconds(2), Duration.ofSeconds(1), "marwanbukhori-dev",
                    "s3cret-demo-key", "compute-deploy", "grafana/k6:0.53.0",
                    new ConsoleProperties.Pool("booking-service", 5, 20));
        }
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `cd console && ./mvnw -q test -Dtest=ObjectsControllerTest`
Expected: compilation failure, `cannot find symbol: class ObjectsController`.

- [ ] **Step 3: Implement**

```java
package dev.marwan.console.objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The inspector's endpoints. GET only, so public under KeyFilter: nothing an
 * object description carries is more sensitive than the pods table already
 * shows. Raw logs, which are, arrive in a later step behind the key.
 */
@RestController
public class ObjectsController {

    private final ObjectsProvider objects;

    public ObjectsController(ObjectsProvider objects) {
        this.objects = objects;
    }

    @GetMapping("/api/objects/{kind}/{name}")
    public ObjectDetail describe(@PathVariable String kind, @PathVariable String name) {
        return objects.describe(kind, name);
    }

    /** Only Jobs are listed: they are the one kind the graph has no fixed box for. */
    @GetMapping("/api/objects")
    public List<ObjectSummary> list(@RequestParam String kind) {
        if (!ObjectKind.JOB.path().equals(kind)) {
            throw new ObjectNotFound("only jobs are listed");
        }
        return objects.recentJobs();
    }

    @ExceptionHandler(ObjectNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(ObjectNotFound e) {
        return e.getMessage();
    }
}
```

- [ ] **Step 4: Run to see it pass**

Run: `cd console && ./mvnw -q test -Dtest=ObjectsControllerTest && ./mvnw -q test`
Expected: exit 0; `ObjectsControllerTest` 4 tests, 0 failures; full suite green.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/objects/ObjectsController.java console/src/test/java/dev/marwan/console/objects/ObjectsControllerTest.java
git commit -m "Serve object descriptions and recent runs to the page"
```

---

### Task 7: UI types and the inspector service

**Files:**
- Modify: `console/ui/src/app/state.ts` (append)
- Create: `console/ui/src/app/inspector.service.ts`

**Interfaces:**
- Consumes: `GET /api/objects/{kind}/{name}`, `GET /api/objects?kind=job` (Task 6).
- Produces:
  - Types `ObjectRef { kind: string; name: string }`, `ObjectFact`, `ObjectLink`, `ObjectEvent`, `ObjectDetail`, `ObjectSummary` mirroring the Java records
  - `InspectorService` with `selected: Signal<ObjectRef | null>`, `detail: Signal<ObjectDetail | null>`, `gone: Signal<boolean>`, `recentJobs: Signal<ObjectSummary[]>`, `select(ref: ObjectRef | null): void`

- [ ] **Step 1: Add the types to `state.ts`**

```ts
/** One object the inspector can show, as it appears in a URL: `?inspect=pod/queue-gate-x`. */
export interface ObjectRef {
  kind: string;
  name: string;
}

export type Tone = 'ok' | 'warn' | 'bad' | 'neutral';

export interface ObjectFact {
  label: string;
  value: string;
  tone: Tone | null;
}

export interface ObjectLink {
  kind: string;
  name: string;
  label: string;
  tone: Tone | null;
}

export interface ObjectEvent {
  at: string | null;
  type: string;
  reason: string;
  message: string;
  count: number;
}

export interface ObjectDetail {
  kind: string;
  name: string;
  available: boolean;
  detail: string | null;
  tone: Tone;
  headline: string;
  facts: ObjectFact[];
  related: ObjectLink[];
  events: ObjectEvent[];
}

export interface ObjectSummary {
  kind: string;
  name: string;
  tone: Tone;
  headline: string;
  at: string | null;
}
```

- [ ] **Step 2: Create `inspector.service.ts`**

```ts
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ObjectDetail, ObjectRef, ObjectSummary } from './state';

/** Matches the console's two-second cache; faster would only re-read it. */
const POLL_MILLIS = 2000;
const PARAM = 'inspect';

/**
 * Which object the inspector shows, and what it currently says about it.
 *
 * The selection lives in the URL, so a link can point at one pod. It is read
 * and written with history.replaceState rather than a router, because the app
 * has none: pages are switched with a signal.
 *
 * A 404 means the object is gone - usually a pod replaced by a rollout between
 * two polls. That is a state to show, with a way back to its owner, not an
 * error to retry forever.
 */
@Injectable({ providedIn: 'root' })
export class InspectorService {
  private readonly http = inject(HttpClient);

  readonly selected = signal<ObjectRef | null>(InspectorService.fromUrl());
  readonly detail = signal<ObjectDetail | null>(null);
  readonly gone = signal(false);
  readonly recentJobs = signal<ObjectSummary[]>([]);

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  select(ref: ObjectRef | null): void {
    this.selected.set(ref);
    this.detail.set(null);
    this.gone.set(false);
    const url = new URL(window.location.href);
    if (ref) {
      url.searchParams.set(PARAM, `${ref.kind}/${ref.name}`);
    } else {
      url.searchParams.delete(PARAM);
    }
    history.replaceState(history.state, '', url);
    this.poll();
  }

  private poll(): void {
    const ref = this.selected();
    if (!ref) {
      this.http.get<ObjectSummary[]>('/api/objects', { params: { kind: 'job' } }).subscribe({
        next: (jobs) => this.recentJobs.set(jobs),
        error: () => {}
      });
      return;
    }
    const path = `/api/objects/${encodeURIComponent(ref.kind)}/${encodeURIComponent(ref.name)}`;
    this.http.get<ObjectDetail>(path).subscribe({
      next: (detail) => {
        // A response for an object no longer selected must not overwrite the new one.
        if (this.selected() === ref) {
          this.detail.set(detail);
          this.gone.set(false);
        }
      },
      error: (e: HttpErrorResponse) => {
        if (this.selected() === ref && e.status === 404) {
          this.gone.set(true);
        }
        // Anything else: keep the last good reading on screen, as ClusterService does.
      }
    });
  }

  private static fromUrl(): ObjectRef | null {
    const raw = new URL(window.location.href).searchParams.get(PARAM);
    const slash = raw?.indexOf('/') ?? -1;
    return raw && slash > 0 ? { kind: raw.slice(0, slash), name: raw.slice(slash + 1) } : null;
  }
}
```

- [ ] **Step 3: Build**

Run: `cd console/ui && npx ng build`
Expected: `Application bundle generation complete.` with no errors.

- [ ] **Step 4: Commit**

```bash
git add console/ui/src/app/state.ts console/ui/src/app/inspector.service.ts
git commit -m "Keep the inspected object in the URL and poll it like the rest of the page"
```

---

### Task 8: The inspector panel

**Files:**
- Create: `console/ui/src/app/inspector.ts`

**Interfaces:**
- Consumes: `InspectorService` (Task 7).
- Produces: `<rb-inspector />` standalone component, with no inputs.

- [ ] **Step 1: Create the component**

```ts
import { Component, computed, inject, signal } from '@angular/core';
import { InspectorService } from './inspector.service';
import { ObjectLink } from './state';

/**
 * The right-hand column of the cluster page: whatever object was clicked.
 *
 * One template for every kind. The server sends a headline, a tone and
 * label/value facts, so a Route and a CronJob render the same way and a new
 * kind needs no new component.
 *
 * Under 900px it becomes a drawer over the page, with a close button, because
 * a third of a phone is too narrow to read an event message in.
 */
@Component({
  selector: 'rb-inspector',
  template: `
    <aside class="inspector" [class.open]="!!inspector.selected()">
      @if (inspector.selected(); as ref) {
        <header class="head">
          <div>
            <div class="kind mono">{{ ref.kind }}</div>
            <div class="name mono">{{ ref.name }}</div>
          </div>
          <button class="close" (click)="inspector.select(null)" aria-label="Close inspector">✕</button>
        </header>

        @if (inspector.gone()) {
          <p class="gone">
            This {{ ref.kind }} no longer exists. Pods are replaced on every rollout and when the
            autoscaler scales in.
          </p>
          @if (owner(); as o) {
            <button class="link" (click)="open(o)">Open {{ o.label }} {{ o.name }}</button>
          }
        } @else if (inspector.detail(); as d) {
          @if (!d.available) {
            <p class="pill bad">{{ d.headline }}: {{ d.detail }}</p>
          } @else {
            <p class="pill" [class]="'pill ' + d.tone">{{ d.headline }}</p>

            <div class="tabs" role="tablist">
              <button role="tab" [class.on]="tab() === 'overview'" (click)="tab.set('overview')">Overview</button>
              <button role="tab" [class.on]="tab() === 'events'" (click)="tab.set('events')">
                Events ({{ d.events.length }})
              </button>
            </div>

            @if (tab() === 'overview') {
              <dl class="facts">
                @for (f of d.facts; track f.label) {
                  <dt>{{ f.label }}</dt>
                  <dd class="mono" [class]="f.tone ? 'mono t-' + f.tone : 'mono'">{{ f.value }}</dd>
                }
              </dl>
              @if (d.related.length) {
                <div class="related">
                  @for (l of d.related; track l.kind + l.name) {
                    <button class="chip" [class]="'chip t-' + (l.tone ?? 'neutral')" (click)="open(l)">
                      <span class="chip-kind">{{ l.label }}</span> {{ l.name }}
                    </button>
                  }
                </div>
              }
            } @else {
              @if (d.events.length === 0) {
                <p class="quiet">No recent events. Kubernetes keeps them for about an hour.</p>
              }
              <ol class="events">
                @for (e of d.events; track $index) {
                  <li [class.warn]="e.type === 'Warning'">
                    <span class="mono when">{{ time(e.at) }}</span>
                    <span class="reason">{{ e.reason }}@if (e.count > 1) { ×{{ e.count }} }</span>
                    <span class="msg">{{ e.message }}</span>
                  </li>
                }
              </ol>
            }
          }
        } @else {
          <p class="quiet">Reading…</p>
        }
      } @else {
        <div class="empty">
          <p class="quiet">Click any Route, Service, Deployment, autoscaler or the CronJob in the graph.</p>
          <div class="kind mono">Recent runs</div>
          @for (j of inspector.recentJobs(); track j.name) {
            <button class="row" (click)="open({ kind: 'job', name: j.name, label: 'Job', tone: j.tone })">
              <span class="dot" [class]="'dot t-' + j.tone"></span>
              <span class="mono">{{ j.name }}</span>
              <span class="quiet">{{ j.headline }}</span>
            </button>
          } @empty {
            <p class="quiet">No runs in the last hour.</p>
          }
        </div>
      }
    </aside>
  `,
  styles: `
    .inspector { border-left: 1px solid var(--line); padding: 0 0 0 20px; min-width: 0; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
    .kind { font-size: 11px; letter-spacing: .1em; text-transform: uppercase; color: var(--muted); }
    .name { font-size: 15px; font-weight: 700; word-break: break-all; }
    .mono { font-family: var(--mono); }
    .close { display: none; background: none; border: 0; font-size: 18px; cursor: pointer; color: var(--ink); }
    .pill { display: inline-block; margin: 12px 0; padding: 4px 11px; border-radius: 999px; font-size: 13px;
            background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .pill.ok { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .pill.warn { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .pill.bad { background: var(--chip-bad-bg); color: var(--chip-bad-fg); }
    .tabs { display: flex; gap: 14px; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
    .tabs button { background: none; border: 0; padding: 6px 0; cursor: pointer; font-size: 14px;
                   color: var(--muted); border-bottom: 2px solid transparent; }
    .tabs button.on { color: var(--ink); font-weight: 700; border-color: var(--chip-bad-fg); }
    .facts { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; font-size: 13px; }
    .facts dt { color: var(--muted); }
    .facts dd { margin: 0; word-break: break-word; }
    .t-ok { color: var(--chip-ok-fg); } .t-warn { color: var(--chip-warn-fg); } .t-bad { color: var(--chip-bad-fg); }
    .related { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
    .chip { font-family: var(--mono); font-size: 12px; border: 1px solid var(--line); background: var(--white);
            border-radius: 6px; padding: 3px 8px; cursor: pointer; }
    .chip-kind { color: var(--muted); }
    .chip.t-bad { border-color: var(--chip-bad-fg); } .chip.t-warn { border-color: var(--chip-warn-fg); }
    .events { list-style: none; padding: 0; margin: 0; font-size: 13px; display: grid; gap: 8px; }
    .events li { display: grid; grid-template-columns: 70px 1fr; gap: 2px 10px; }
    .events .msg { grid-column: 2; color: var(--ink-soft); }
    .events li.warn .reason { color: var(--chip-bad-fg); font-weight: 700; }
    .when { color: var(--muted); font-size: 12px; }
    .quiet { color: var(--muted); font-size: 13px; }
    .gone { font-size: 14px; }
    .link { background: none; border: 0; color: var(--chip-info-fg); cursor: pointer; padding: 0; font-size: 14px; }
    .empty .row { display: grid; grid-template-columns: 10px 1fr; gap: 2px 8px; width: 100%; text-align: left;
                  background: none; border: 0; border-bottom: 1px solid var(--line); padding: 8px 0; cursor: pointer; }
    .empty .row .quiet { grid-column: 2; }
    .dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; background: var(--muted); }
    .dot.t-ok { background: var(--chip-ok-fg); } .dot.t-warn { background: var(--chip-warn-fg); }
    .dot.t-bad { background: var(--chip-bad-fg); }

    @media (max-width: 899px) {
      .inspector { position: fixed; inset: 0 0 0 auto; width: min(420px, 100vw); z-index: 20;
                   background: var(--white); padding: 16px; overflow-y: auto; border-left: 1px solid var(--line);
                   box-shadow: -8px 0 24px rgba(0, 0, 0, .12); transform: translateX(100%);
                   transition: transform .2s ease; }
      .inspector.open { transform: none; }
      .close { display: block; }
    }
  `
})
export class Inspector {
  protected readonly inspector = inject(InspectorService);
  protected readonly tab = signal<'overview' | 'events'>('overview');

  /** Where a vanished pod came from, guessed from its name, so the reader has somewhere to go. */
  protected readonly owner = computed<ObjectLink | null>(() => {
    const ref = this.inspector.selected();
    if (ref?.kind !== 'pod') {
      return null;
    }
    const parts = ref.name.split('-');
    return parts.length > 2
      ? { kind: 'deployment', name: parts.slice(0, -2).join('-'), label: 'Deployment', tone: null }
      : null;
  });

  protected open(link: ObjectLink): void {
    this.tab.set('overview');
    this.inspector.select({ kind: link.kind, name: link.name });
  }

  protected time(at: string | null): string {
    return at ? at.slice(11, 19) : '—';
  }
}
```

- [ ] **Step 2: Build**

Run: `cd console/ui && npx ng build`
Expected: bundle generation complete, no errors. (The component is not yet on a page; Task 9 places it.)

- [ ] **Step 3: Commit**

```bash
git add console/ui/src/app/inspector.ts
git commit -m "Show one object at a time: its state, facts, neighbours and events"
```

---

### Task 9: A clickable graph beside the inspector

**Files:**
- Modify: `console/ui/src/app/object-graph.ts`
- Modify: `console/ui/src/app/cluster-page.ts`

**Interfaces:**
- Consumes: `InspectorService.select`, `InspectorService.selected` (Task 7), `<rb-inspector />` (Task 8).

- [ ] **Step 1: Give boxes a `ref` and make them clickable**

In `object-graph.ts`:

1. Inject the service: `private readonly inspector = inject(InspectorService);` and import it (`import { InspectorService } from './inspector.service';`) plus `ObjectRef` from `./state`.
2. In `boxes`, add a `ref` to each box that maps to an object, and `ref: null` to the rest:

| Box id | ref |
|---|---|
| `r-console` | `{ kind: 'route', name: 'console' }` |
| `r-gate` | `{ kind: 'route', name: 'queue-gate' }` |
| `s-console`, `s-gate`, `s-booking`, `s-redis` | `{ kind: 'service', name: 'console' \| 'queue-gate' \| 'booking-service' \| 'redis' }` |
| `d-console`, `d-gate`, `d-booking`, `d-redis` | `{ kind: 'deployment', name: … }` (same four names) |
| `g-gate` | `{ kind: 'hpa', name: 'queue-gate' }` |
| `g-booking` | `{ kind: 'hpa', name: 'booking-service' }` |
| `cj` | `{ kind: 'cronjob', name: 'keepalive' }` |
| `anyone`, `g-console`, `g-redis`, `cm`, `sm`, `pr` | `null` |

   Declare the array's element type so TypeScript accepts the mix: `ref: ObjectRef | null`.
3. Replace the box `<g>` in the template with:

```html
@for (box of boxes(); track box.id) {
  <g [class]="'box ' + box.tone + (box.ref ? ' clickable' : '') + (isSelected(box.ref) ? ' selected' : '')"
     [attr.tabindex]="box.ref ? 0 : null"
     [attr.role]="box.ref ? 'button' : null"
     [attr.aria-label]="box.ref ? 'Inspect ' + box.ref.kind + ' ' + box.ref.name : null"
     (click)="inspect(box.ref)"
     (keydown.enter)="inspect(box.ref)"
     (keydown.space)="inspect(box.ref); $event.preventDefault()">
    <rect [attr.x]="box.x" [attr.y]="box.y" [attr.width]="box.w" [attr.height]="box.h" rx="6" />
    <text class="label" [attr.x]="box.x + 12" [attr.y]="box.y + 24">{{ box.label }}</text>
    @if (box.sub) {
      <text class="sub" [attr.x]="box.x + 12" [attr.y]="box.y + 44">{{ box.sub }}</text>
    }
  </g>
}
```

4. Add the methods:

```ts
  protected inspect(ref: ObjectRef | null): void {
    if (ref) {
      this.inspector.select(ref);
    }
  }

  protected isSelected(ref: ObjectRef | null): boolean {
    const s = this.inspector.selected();
    return !!ref && !!s && s.kind === ref.kind && s.name === ref.name;
  }
```

5. Add to the styles:

```css
    .box.clickable { cursor: pointer; }
    .box.clickable:hover rect, .box.clickable:focus rect { stroke: var(--ink); stroke-width: 2; }
    .box.clickable:focus { outline: none; }
    .box.selected rect { stroke: var(--chip-bad-fg); stroke-width: 3; }
```

6. Lower the SVG's minimum width so it shares the row with the inspector: `svg { width: 100%; min-width: 640px; ... }`.

- [ ] **Step 2: Put the inspector beside the graph**

In `cluster-page.ts`, import `Inspector` from `./inspector` and add it to `imports`. Replace the "How these objects connect" card with:

```html
      <div class="card">
        <div class="why">How these objects connect</div>
        <p class="note">
          The list above is what is running. This is why: which Route publishes what, which
          Service fronts which Deployment, and what governs each one. Click any of them to inspect
          it live.
        </p>
        <div class="graph-and-inspector">
          <rb-object-graph />
          <rb-inspector />
        </div>
      </div>
```

and add to the styles:

```css
    .graph-and-inspector { display: grid; grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr); gap: 20px; align-items: start; }
    @media (max-width: 899px) { .graph-and-inspector { grid-template-columns: 1fr; } }
```

- [ ] **Step 3: Build**

Run: `cd console/ui && npx ng build`
Expected: bundle generation complete, no errors.

- [ ] **Step 4: Check it locally against the cluster's shape**

The console runs locally with no cluster (`KubernetesAccess` tolerates that), so run it and confirm the UI states, not the data:

The UI is only bundled into the jar by the Docker build, so run the two halves separately. `console/ui/proxy.conf.json` already sends `/api` to port 8082:

Run, in two terminals:
`cd console && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=8082`
`cd console/ui && npx ng serve`
Then open `http://localhost:4200/?inspect=deployment/queue-gate` and go to the cluster page.
Expected: the cluster page's graph shows `queue-gate`'s Deployment box outlined in red, and the inspector shows "not readable right now" with a reason. That is correct off-cluster. Clicking another box changes the URL's `inspect=` and the inspector. Under 900px wide, the inspector is a drawer with a close button.

- [ ] **Step 5: Commit**

```bash
git add console/ui/src/app/object-graph.ts console/ui/src/app/cluster-page.ts
git commit -m "Make the object graph clickable and put the inspector beside it"
```

---

### Task 10: Deploy and verify on the cluster

**Files:** none changed unless a check fails.

- [ ] **Step 1: Confirm the human has applied the RBAC from Task 1**

Run: `oc auth can-i list replicasets.apps --as=system:serviceaccount:marwanbukhori-dev:console`
Expected: `yes`. If `no`, stop and ask the human to run the Task 1 Step 4 block.

- [ ] **Step 2: Push, and wait for CD**

Ask the human before pushing. Then:

```bash
git push origin main
gh run watch "$(gh run list --workflow CD --limit 1 --json databaseId -q '.[0].databaseId')"
```

Expected: CD succeeds. Then `deploy/scripts/status.sh` prints `everything is up`.

- [ ] **Step 3: Check each kind through the public Route**

```bash
C=https://console-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com
for ref in route/queue-gate service/booking-service deployment/booking-service hpa/queue-gate cronjob/keepalive; do
  printf "%-28s " "$ref"; curl -s "$C/api/objects/$ref" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["available"], d["tone"], "-", d["headline"])'
done
POD=$(oc get pods -l app=queue-gate -o jsonpath='{.items[0].metadata.name}')
curl -s "$C/api/objects/pod/$POD" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["tone"], d["headline"])'
curl -s -o /dev/null -w "secret -> %{http_code}\n" "$C/api/objects/secret/splunk-hec"
curl -s -o /dev/null -w "gone pod -> %{http_code}\n" "$C/api/objects/pod/queue-gate-does-not-exist"
curl -s "$C/api/objects?kind=job" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)), "recent jobs")'
```

Expected: every line `True ok - ...` (the Route says `app answering in N ms`, the Service says `2 ready endpoints`, the HPA `2 of 2-10`, the CronJob `next run ...`); the pod `ok Running, ready`; `secret -> 404`; `gone pod -> 404`; a job count of 0 or more.

- [ ] **Step 4: Watch a rush in the browser**

Open the console's cluster page, start a rush from the console, and click `queue-gate`'s HPA while it runs.
Expected: its headline climbs above `2 of 2-10` as the HPA scales; clicking the Deployment lists new pods as chips; clicking a pod that the HPA later removes shows "This pod no longer exists" with a link to the Deployment (Review Focus 1).

- [ ] **Step 5: Record the outcome**

If any check failed, fix it in the task that owns the code and repeat this task. When all pass, the step is done; nothing further to commit.

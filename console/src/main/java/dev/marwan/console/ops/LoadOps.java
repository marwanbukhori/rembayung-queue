package dev.marwan.console.ops;

import dev.marwan.console.ConsoleProperties;
import dev.marwan.console.cluster.KubernetesAccess;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sending real customers at a drop, from inside the cluster.
 *
 * <h2>Why a Job and not a thread pool in this process</h2>
 * A stranger cannot install k6 on their laptop, and the console generating the
 * load itself would measure the console. A {@code Job} running
 * {@code grafana/k6} is the same tool the Phase 3 measurements used, it shows
 * up in {@code oc get jobs} where anyone can check it, and — this is the part
 * that matters — <b>it has to ask the scheduler for CPU like everything else,
 * so it can be refused</b>. A run that will not schedule is not a bug in this
 * feature; it is the feature.
 *
 * <h2>What this deliberately does not exercise</h2>
 * The Job talks to {@code queue-gate} over the ClusterIP Service, so it
 * <b>skips the public Route entirely</b>. It exercises the queue, the admission
 * rate and the seat invariant. It does not exercise the ingress path, and the
 * page says so — a clean run here is not evidence that the edge would have
 * carried the same traffic. The Phase 3 ladder measured that the edge would
 * not:
 *
 * <pre>
 *   offered   arrived   failed
 *      200       200       0%
 *     1000       662      75%
 *     3000       818      92%
 * </pre>
 *
 * Which is why 200 is the default: it is the measured ceiling of usefulness,
 * not a resource compromise. Higher counts are offered rather than forbidden,
 * because hiding the option would hide the finding.
 *
 * <h2>Bounds, and what they are for</h2>
 * {@code activeDeadlineSeconds} is set on the <b>Job</b> and not on the pod
 * template, and that placement is load-bearing twice over. It stops a run
 * outliving the person who started it, and it keeps the pod out of the
 * {@code Terminating} quota scope: this namespace carries two CPU quotas,
 * {@code compute-build} (Terminating) and {@code compute-deploy}
 * (NotTerminating), and a pod is Terminating only when its <em>own</em> spec
 * carries the deadline. On the pod template the run would spend a separate,
 * empty budget and could never be refused — which would quietly delete the
 * whole point of the constraints panel.
 *
 * One run per drop is enforced by naming the Job after the drop, so a second
 * create is refused by the API server rather than by a counter this console
 * would have to keep correct. There is no global cap on how many drops may run
 * at once: if the namespace fills, the queue and the reason are what a visitor
 * is meant to see.
 */
@RestController
@RequestMapping("/api/drops/{dropId}/load")
public class LoadOps {

    private static final Logger log = LoggerFactory.getLogger(LoadOps.class);

    /** The measured ceiling of usefulness. Above it the edge sheds, not the app. */
    /**
     * Sixty, so the default run finishes while somebody is still watching it.
     *
     * At one admission a second - the rate this database can actually commit at
     * - two hundred customers take over three minutes to get through, and the
     * k6 script gives up polling after ninety seconds. Sixty drains in a minute,
     * inside the poll window, and fills about half the sitting.
     */
    public static final int DEFAULT_VUS = 60;

    /** Far past the point the finding is visible; a typo should not book the cluster for an hour. */
    private static final int MAX_VUS = 5000;

    /** Nothing runs forever. Set on the Job, deliberately: see the class comment. */
    private static final int DEADLINE_SECONDS = 300;

    /** Long enough to read the outcome on the page, short enough not to hold the budget. */
    private static final int TTL_AFTER_FINISHED_SECONDS = 900;

    /** How long a re-run waits for the previous Job's tombstone to clear. */
    private static final Duration REPLACE_TIMEOUT = Duration.ofSeconds(5);

    private static final String SCRIPT_CONFIG_MAP = "console-k6-drop";
    private static final String SCRIPT_FILE = "drop.js";
    private static final String SCRIPT_MOUNT = "/scripts";

    private final ConsoleProperties properties;
    private final KubernetesAccess kubernetes;
    private final RestClient gate;
    private final Clock clock;

    public LoadOps(ConsoleProperties properties, KubernetesAccess kubernetes,
                   RestClient gateClient, Clock clock) {
        this.properties = properties;
        this.kubernetes = kubernetes;
        this.gate = gateClient;
        this.clock = clock;
    }

    /**
     * What the current run for this drop is doing, or why there is none.
     *
     * 200 whatever the answer, including when the cluster cannot be read at
     * all: the page polls this beside the queue numbers, and a 500 here would
     * take the drop panel down with it.
     */
    @GetMapping
    public LoadRun status(@PathVariable String dropId) {
        String jobName = jobName(dropId);
        try {
            Job job = kubernetes.client().batch().v1().jobs()
                    .inNamespace(properties.namespace())
                    .withName(jobName)
                    .get();
            return job == null ? LoadRun.none(dropId, jobName) : describe(dropId, job);
        } catch (Throwable e) {
            kubernetes.invalidate();
            return LoadRun.unavailable(dropId, "cluster not readable: " + KubernetesAccess.summarise(e));
        }
    }

    /**
     * Start a run.
     *
     * Unlike {@link #status}, this answers with a failure when it fails: a read
     * that cannot see the cluster still has a page to draw, but a button that
     * reported a run nobody started would be lying.
     */
    @PostMapping
    public LoadRun start(@PathVariable String dropId, @RequestBody(required = false) SendLoad request) {
        int vus = vusOf(request);
        long slotId = slotOf(dropId);
        String jobName = jobName(dropId);
        try {
            replaceFinishedRun(jobName);
            applyScript();
            Job created = kubernetes.client().batch().v1().jobs()
                    .inNamespace(properties.namespace())
                    .resource(job(jobName, dropId, slotId, vus))
                    .create();
            log.info("Started load job {} for drop {} on slot {} with {} VUs asking {}m",
                    jobName, dropId, slotId, vus, cpuMillis(vus));
            return describe(dropId, created);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Throwable e) {
            kubernetes.invalidate();
            log.warn("could not start load job {}: {}", jobName, e.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "could not start a load run: " + KubernetesAccess.summarise(e));
        }
    }

    private int vusOf(SendLoad request) {
        if (request == null || request.vus() == null) {
            return DEFAULT_VUS;
        }
        int asked = request.vus();
        if (asked < 1 || asked > MAX_VUS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "vus must be between 1 and " + MAX_VUS + " but was " + asked);
        }
        return asked;
    }

    /**
     * Which slot this drop sells, asked of the gate rather than assumed.
     *
     * Also the only check that the drop exists: pointing a thousand virtual
     * users at a drop that expired half an hour ago would produce a wall of
     * 404s and no explanation.
     */
    private long slotOf(String dropId) {
        DropState state;
        try {
            state = gate.get()
                    .uri("/internal/drops/{id}/state", dropId)
                    .retrieve()
                    .body(DropState.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "queue-gate could not describe drop " + dropId + ": " + e.getMessage());
        }
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no drop " + dropId + ": it may have expired");
        }
        return state.slotId() == null ? properties.canonicalSlot() : state.slotId();
    }

    /**
     * A finished run is cleared so the next one can take the name; a live one is
     * refused.
     *
     * Refusing is right rather than queueing behind it: two runs against one
     * drop would interleave into numbers neither of them explains, and the
     * person pressing the button wants to know that.
     */
    private void replaceFinishedRun(String jobName) {
        Job existing = kubernetes.client().batch().v1().jobs()
                .inNamespace(properties.namespace()).withName(jobName).get();
        if (existing == null) {
            return;
        }
        if (isLive(existing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a load run for this drop is already in flight; it stops by itself within "
                            + DEADLINE_SECONDS + " seconds");
        }
        kubernetes.client().batch().v1().jobs()
                .inNamespace(properties.namespace()).withName(jobName).delete();
        Instant deadline = clock.instant().plus(REPLACE_TIMEOUT);
        while (clock.instant().isBefore(deadline)) {
            if (kubernetes.client().batch().v1().jobs()
                    .inNamespace(properties.namespace()).withName(jobName).get() == null) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "the previous run for this drop is still being cleared; try again in a moment");
    }

    /**
     * The script, applied from the console's own image on every run.
     *
     * Shipping it in the image and writing it out here rather than keeping a
     * ConfigMap in deploy/ means the script and the console that describes it
     * cannot drift apart across a deploy — there is one copy, and it is the one
     * that was built.
     */
    private void applyScript() throws IOException {
        String script;
        try (var stream = new ClassPathResource("k6/" + SCRIPT_FILE).getInputStream()) {
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        ConfigMap map = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(SCRIPT_CONFIG_MAP)
                .withNamespace(properties.namespace())
                .addToLabels("app.kubernetes.io/managed-by", "rembayung-console")
                .endMetadata()
                .withData(Map.of(SCRIPT_FILE, script))
                .build();
        kubernetes.client().configMaps()
                .inNamespace(properties.namespace())
                .resource(map)
                .createOr(NonDeletingOperation::update);
    }

    private Job job(String jobName, String dropId, long slotId, int vus) {
        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(properties.namespace())
                .addToLabels("app", "rembayung-load")
                .addToLabels("rembayung.dev/drop", label(dropId))
                .addToAnnotations("rembayung.dev/vus", String.valueOf(vus))
                .endMetadata()
                .withNewSpec()
                // On the Job, not the pod template. See the class comment: this
                // placement is what keeps the run inside the same CPU budget as
                // the Deployments, which is what lets it be refused.
                .withActiveDeadlineSeconds((long) DEADLINE_SECONDS)
                // One attempt. A load run that failed because the cluster
                // refused it should say so, not silently try again while the
                // person watching wonders why the numbers have not moved.
                .withBackoffLimit(0)
                .withTtlSecondsAfterFinished(TTL_AFTER_FINISHED_SECONDS)
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", "rembayung-load")
                .addToLabels("rembayung.dev/drop", label(dropId))
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("k6")
                .withImage(properties.k6Image())
                .withCommand("k6", "run", SCRIPT_MOUNT + "/" + SCRIPT_FILE)
                .addNewEnv().withName("GATE").withValue(properties.gateBaseUrl()).endEnv()
                .addNewEnv().withName("DROP_ID").withValue(dropId).endEnv()
                .addNewEnv().withName("SLOT_ID").withValue(String.valueOf(slotId)).endEnv()
                .addNewEnv().withName("VUS").withValue(String.valueOf(vus)).endEnv()
                .withNewResources()
                .withRequests(Map.of(
                        "cpu", new Quantity(cpuMillis(vus) + "m"),
                        "memory", new Quantity(memoryMebibytes(vus) + "Mi")))
                .withLimits(Map.of(
                        "cpu", new Quantity("1"),
                        "memory", new Quantity((memoryMebibytes(vus) * 2) + "Mi")))
                .endResources()
                .addNewVolumeMount()
                .withName("script").withMountPath(SCRIPT_MOUNT).withReadOnly(true)
                .endVolumeMount()
                .endContainer()
                .addNewVolume()
                .withName("script")
                .withNewConfigMap().withName(SCRIPT_CONFIG_MAP).endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * What a run of this size costs the namespace.
     *
     * A ladder rather than a formula, because the three rungs are the three
     * offers on the page and each was sized against a measured run rather than
     * interpolated. It matters that a bigger run costs more: if every size
     * asked for the same CPU, the largest one could never be the thing that
     * gets refused, and the panel would have nothing to explain.
     */
    static int cpuMillis(int vus) {
        // Thresholds stated, not keyed to DEFAULT_VUS. They were the same number
        // by coincidence, so lowering the default from 200 to 60 silently moved
        // every 200-VU run up a step and asked the quota for twice the CPU. What
        // a run costs should not change because a different button's default
        // moved.
        if (vus <= 200) {
            return 200;
        }
        return vus <= 1000 ? 400 : 800;
    }

    /** k6 holds a few kilobytes of state per VU, plus the runtime. */
    static int memoryMebibytes(int vus) {
        if (vus <= DEFAULT_VUS) {
            return 256;
        }
        return vus <= 1000 ? 512 : 1024;
    }

    /**
     * The Job's name, which is the whole concurrency control.
     *
     * DNS-1123: lowercase alphanumerics and dashes, and it must not end in one.
     */
    static String jobName(String dropId) {
        return "load-" + label(dropId);
    }

    private static String label(String dropId) {
        String cleaned = dropId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        cleaned = cleaned.length() <= 45 ? cleaned : cleaned.substring(0, 45);
        cleaned = cleaned.replaceAll("^-+", "").replaceAll("-+$", "");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    /**
     * A Job that has neither succeeded nor failed is still live — including one
     * with no pod at all.
     *
     * That last case is the one worth being explicit about: a Job sitting
     * Pending on the quota has {@code active: 0} and looks finished if you only
     * count running pods. Treating it as replaceable would let a visitor stack
     * a second run behind the first and never see why either was refused, which
     * is the exact explanation this whole feature exists to give them.
     */
    private static boolean isLive(Job job) {
        if (job.getStatus() == null) {
            return true;
        }
        Integer succeeded = job.getStatus().getSucceeded();
        Integer failed = job.getStatus().getFailed();
        return (succeeded == null || succeeded == 0) && (failed == null || failed == 0);
    }

    /**
     * Turn a Job into the sentence the page shows, going to whichever object
     * actually holds the explanation.
     *
     * There are two distinct ways a run does not start and they are recorded in
     * different places, which is why this looks in three:
     *
     * <ul>
     *   <li><b>The quota refuses the pod outright.</b> No pod is ever created,
     *       so there is no pod condition to read. The Job controller records a
     *       {@code FailedCreate} <em>Event</em> carrying
     *       "exceeded quota: compute-deploy, requested: ...".</li>
     *   <li><b>The pod is created and cannot be placed.</b> The pod exists in
     *       {@code Pending} with a {@code PodScheduled=False} condition saying
     *       "0/6 nodes are available: 3 Insufficient cpu".</li>
     * </ul>
     *
     * A console that only looked at one of them would report "Pending" with no
     * reason for exactly the case a visitor most wants explained.
     */
    private LoadRun describe(String dropId, Job job) {
        String jobName = job.getMetadata().getName();
        int vus = annotatedVus(job);
        long elapsed = elapsedSeconds(job);
        List<Pod> pods = kubernetes.client().pods()
                .inNamespace(properties.namespace())
                .withLabel("job-name", jobName)
                .list().getItems();

        Optional<Pod> running = pods.stream()
                .filter(p -> p.getStatus() != null && "Running".equals(p.getStatus().getPhase()))
                .findFirst();
        if (running.isPresent()) {
            return run(dropId, jobName, LoadRun.Phase.RUNNING, vus, null, null, elapsed);
        }

        Integer succeeded = job.getStatus() == null ? null : job.getStatus().getSucceeded();
        if (succeeded != null && succeeded > 0) {
            return run(dropId, jobName, LoadRun.Phase.SUCCEEDED, vus, null, null, elapsed);
        }

        Optional<JobCondition> failure = conditions(job).stream()
                .filter(c -> "True".equals(c.getStatus()) && "Failed".equals(c.getType()))
                .findFirst();
        if (failure.isPresent()) {
            return run(dropId, jobName, LoadRun.Phase.FAILED, vus,
                    failure.get().getReason(), failure.get().getMessage(), elapsed);
        }
        Integer failed = job.getStatus() == null ? null : job.getStatus().getFailed();
        if (failed != null && failed > 0) {
            return run(dropId, jobName, LoadRun.Phase.FAILED, vus,
                    "PodFailed", latestWarning(jobName, pods), elapsed);
        }

        // Pending: the interesting case. The reason lives on the pod when one
        // was created, and on an Event about the Job when the quota refused it.
        String[] why = pods.stream()
                .map(LoadOps::unschedulable)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> {
                    String event = latestWarning(jobName, pods);
                    return event == null ? null : new String[] { "FailedCreate", event };
                });
        return why == null
                ? run(dropId, jobName, LoadRun.Phase.PENDING, vus, null, null, elapsed)
                : run(dropId, jobName, LoadRun.Phase.PENDING, vus, why[0], why[1], elapsed);
    }

    private LoadRun run(String dropId, String jobName, LoadRun.Phase phase, int vus,
                        String reason, String message, long elapsed) {
        return new LoadRun(true, null, dropId, jobName, phase, vus, cpuMillis(vus),
                reason, message, elapsed);
    }

    /** {@code PodScheduled=False} is where "Insufficient cpu" is written down. */
    private static String[] unschedulable(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return null;
        }
        return pod.getStatus().getConditions().stream()
                .filter(c -> "PodScheduled".equals(c.getType()) && "False".equals(c.getStatus()))
                .map(c -> new String[] { c.getReason(), c.getMessage() })
                .findFirst()
                .orElse(null);
    }

    /**
     * The most recent Warning event about the Job or one of its pods, verbatim.
     *
     * Best effort: a ServiceAccount that cannot list events should cost the
     * caller a missing sentence, not a failed request.
     */
    private String latestWarning(String jobName, List<Pod> pods) {
        try {
            List<String> names = new java.util.ArrayList<>();
            names.add(jobName);
            pods.stream().map(p -> p.getMetadata().getName()).forEach(names::add);
            return kubernetes.client().v1().events()
                    .inNamespace(properties.namespace())
                    .list().getItems().stream()
                    .filter(e -> e.getInvolvedObject() != null
                            && names.contains(e.getInvolvedObject().getName()))
                    .filter(e -> "Warning".equals(e.getType()))
                    .max(Comparator.comparing(LoadOps::eventStamp))
                    .map(Event::getMessage)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("could not read events for {}: {}", jobName, e.toString());
            return null;
        }
    }

    private static String eventStamp(Event event) {
        if (event.getLastTimestamp() != null) {
            return event.getLastTimestamp();
        }
        if (event.getEventTime() != null) {
            return event.getEventTime().getTime();
        }
        return event.getMetadata() == null || event.getMetadata().getCreationTimestamp() == null
                ? "" : event.getMetadata().getCreationTimestamp();
    }

    private static List<JobCondition> conditions(Job job) {
        return job.getStatus() == null || job.getStatus().getConditions() == null
                ? List.of() : job.getStatus().getConditions();
    }

    private int annotatedVus(Job job) {
        String annotated = job.getMetadata() == null || job.getMetadata().getAnnotations() == null
                ? null : job.getMetadata().getAnnotations().get("rembayung.dev/vus");
        try {
            return annotated == null ? DEFAULT_VUS : Integer.parseInt(annotated);
        } catch (NumberFormatException e) {
            return DEFAULT_VUS;
        }
    }

    private long elapsedSeconds(Job job) {
        String started = job.getStatus() == null ? null : job.getStatus().getStartTime();
        if (started == null) {
            return 0;
        }
        try {
            return Math.max(0, Duration.between(Instant.parse(started), clock.instant()).toSeconds());
        } catch (Exception e) {
            return 0;
        }
    }

    /** {@code {"vus": 200}}, or an empty body for the measured default. */
    public record SendLoad(Integer vus) { }

    /** The part of the gate's drop state this needs: which slot to book into. */
    record DropState(String dropId, Long slotId) { }
}

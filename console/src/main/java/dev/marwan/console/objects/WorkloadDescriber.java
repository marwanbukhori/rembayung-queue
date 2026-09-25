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
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

        String next;
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
                .map(j -> new ObjectDetail.Link("job", j.getMetadata().getName(), "Run",
                        job(j, List.of(), now).tone()))
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
                .filter(v -> v != null)
                .findFirst().orElse(null);
        Integer current = h.getStatus() == null || h.getStatus().getCurrentMetrics() == null ? null
                : h.getStatus().getCurrentMetrics().stream()
                    .filter(m -> m.getResource() != null && "cpu".equals(m.getResource().getName()))
                    .map(MetricStatus::getResource)
                    .map(r -> r.getCurrent() == null ? null : r.getCurrent().getAverageUtilization())
                    .filter(v -> v != null)
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

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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                ? t.get("termination") + ", http "
                    + String.valueOf(t.get("insecureEdgeTerminationPolicy")).toLowerCase(Locale.ROOT)
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
                .flatMap(slice -> slice.getEndpoints() == null ? Stream.empty() : slice.getEndpoints().stream())
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
                .flatMap(rule -> rule.getFrom() == null ? Stream.empty() : rule.getFrom().stream())
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

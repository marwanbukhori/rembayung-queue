# Stack notes

Working notes on how this project is wired, written as it was built. Notes 01
and 02 follow the first plan's tasks
(`docs/superpowers/plans/2026-09-03-booking-domain-core.md`); from 03 on, each
covers a phase or a feature, with its own spec and plan in
`docs/superpowers/specs/` and `docs/superpowers/plans/`. Where a later change
made a note untrue, the note says so in place or in a closing "Since then"
section, rather than being rewritten as if it had always been right.

Each note records not just what a component does, but why it was configured the
way it was — particularly where a default was rejected on correctness grounds.

| Note | Covers | Commit |
|---|---|---|
| [01 — Project skeleton and Spring Boot basics](01-project-skeleton-and-spring-boot-basics.md) | Maven, `pom.xml`, dependency injection, auto-configuration, profiles, Testcontainers wiring | `4c1b8aa` |
| [02 — Database schema and Flyway](02-database-schema-and-flyway.md) | Versioned migrations, the anti-oversell CHECK constraint, idempotency at the database, Oracle type notes | `a553c86`, `e140dcf` |
| [03 — How this runs](03-how-this-runs.md) | What triggers the code, why there was no HTTP layer or UI after Phase 1 and what the console later became, and how to run the concurrency proof by hand | — |
| [04 — OpenShift deployment](04-openshift-deployment.md) | Cluster topology, boundary enforcement via Routes and NetworkPolicies, liveness vs. readiness split and why readiness later dropped the database, image architecture and tagging, the load test undercount, and dev/prod version skew | Phase 3 |
| [05 — Debugging on OpenShift](05-debugging-openshift.md) | The method, and the eight real failures this project hit — including the two first diagnoses that were wrong — plus three that came later | — |
| [06 — Continuous integration](06-continuous-integration.md) | What CI runs and why it uses real Oracle, native amd64 builds, and no registry secret | — |
| [07 — Continuous delivery](07-continuous-delivery.md) | How CI, Ansible and OpenShift connect; why Ansible and not Jenkins or AAP; the scoped ServiceAccount; and the measured live-cluster rollback test, including wall-clock timing and zero-downtime evidence | — |
| [08 — Observability](08-observability.md) | Why Prometheus, Dynatrace and Splunk split by job; the SCC and quota evidence behind each choice; why Prometheus outlives two trial accounts, and what happened when both ended; the seven plan defects execution found and the two production bugs debugging found; the connection-pool arithmetic; and the one-computation rule Phase 8 inherits | — |
| [09 — The demo console](09-demo-console.md) | Why every visitor gets their own drop; why the queue token carries its own drop id and what that kept unchanged; one shared key and no tiers, and why reads were later opened and the key made shareable; why the cluster's limits are drawn rather than hidden; why the CPU quota cannot bind and the database does; the measured deployment; and the two bugs only running things could find | — |
| [10 — The simulation traffic path](10-simulation-traffic-path.md) | Every hop from the button to the row lock; the two `drops` surfaces and why they are confused; what enforces each boundary; why `activeDeadlineSeconds` sits on the Job and not the pod; what the run deliberately does not exercise; the k6 threshold correction; and the NetworkPolicy claim the Sandbox overrides | — |
| [11 — The cluster inspector and the live page](11-cluster-inspector-and-live-page.md) | The object graph and inspector, pod logs, charts from Prometheus through the Thanos tenancy port, and the banded simulation page they sit on | — |
| [12 — The run agent](12-run-agent.md) | The bounded agent that analyses every load run with the sandbox's Qwen3 8B model, the facts its reports must cite, and why they live in one ConfigMap | — |
| [13 — The MCP server](13-mcp-server.md) | The console served over MCP at `/mcp`: its tools, which one needs the key, and why the run agent asks its questions through it | — |
| [14 — Chaos drills and the incident commander](14-chaos-and-incidents.md) | Three self-ending faults, two booking SLOs, incidents that open and resolve on their own, and an agent that diagnoses and proposes while only a person can approve; the grants applied by hand | — |

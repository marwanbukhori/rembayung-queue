# Stack notes

Working notes on how this project is wired, written as it was built. One file
per implementation task, numbered to match
`docs/superpowers/plans/2026-09-03-booking-domain-core.md`.

Each note records not just what a component does, but why it was configured the
way it was — particularly where a default was rejected on correctness grounds.

| Note | Covers | Commit |
|---|---|---|
| [01 — Project skeleton and Spring Boot basics](01-project-skeleton-and-spring-boot-basics.md) | Maven, `pom.xml`, dependency injection, auto-configuration, profiles, Testcontainers wiring | `4c1b8aa` |
| [02 — Database schema and Flyway](02-database-schema-and-flyway.md) | Versioned migrations, the anti-oversell CHECK constraint, idempotency at the database, Oracle type notes | `a553c86`, `e140dcf` |
| [03 — How this runs](03-how-this-runs.md) | What triggers the code, why there is no HTTP layer or UI yet, and how to run the concurrency proof by hand | — |
| [04 — OpenShift deployment](04-openshift-deployment.md) | Cluster topology, boundary enforcement via Routes and NetworkPolicies, liveness vs. readiness split, image architecture and tagging, the load test undercount, and dev/prod version skew | Phase 3 |

# Observability secrets

The observability integrations talk to two SaaS tenants — Splunk Cloud and
Dynatrace — and both authenticate with a bearer token. **No token is ever
committed to this repository, echoed into a log, or placed in a ConfigMap.**
Every one of them lives only in an OpenShift Secret created by hand by the
repository owner, and the manifests reference those Secrets by key.

Both Secrets are referenced with `optional: true`. A missing Secret leaves the
corresponding environment variable unset, the integration switches itself off,
and the pods start normally. Observability is not allowed to be a startup
dependency of the booking path.

## `splunk-hec` — structured logs over HTTP Event Collector

| Key | Meaning | Consumed by |
|---|---|---|
| `url` | HEC **base** URL — scheme, host, port, and nothing else, e.g. `https://http-inputs-<tenant>.splunkcloud.com:8088`. Do **not** append `/services/collector`; see below | `SPLUNK_HEC_URL` in both Deployments |
| `token` | The HEC token value | `SPLUNK_HEC_TOKEN` in both Deployments |
| `profile` | The literal string `splunk` — the switch, see below | `SPRING_PROFILES_INCLUDE` in both Deployments |

Create the token first, in Splunk Cloud: **Settings → Data Inputs → HTTP Event
Collector → New Token**, named `rembayung`, sourcetype `_json`, index `main`.

```bash
oc create secret generic splunk-hec \
  --from-literal=url=https://http-inputs-<tenant>.splunkcloud.com:8088 \
  --from-literal=token=<the HEC token> \
  --from-literal=profile=splunk
```

`profile` is the switch, and it is not a secret — it is the fixed literal
`splunk`. It lives in the Secret so that its presence tracks the credentials'
presence exactly: one `optional: true` reference, one thing to create, and no
way to end up with the appender enabled but unconfigured.

`logback-spring.xml` in both services puts the Splunk appender inside
`<springProfile name="splunk">`, with a plain stdout-only `<root>` under
`<springProfile name="!splunk">`. With no Secret the profile is never activated,
the appender is never built, and the services log JSON to stdout and nothing
else — which is what local runs and CI do.

The obvious-looking alternative, logback's own
`<if condition='isDefined("SPLUNK_HEC_URL")'>`, does not work: logback 1.5.38
(the version Spring Boot 4.1.1 pulls in) parses the `<if>`, evaluates the
condition, and then executes neither branch. It fails silently — the
configuration starts cleanly and simply ships nothing. Measured against both the
Janino `condition="..."` attribute and the newer `<condition class="..."/>`
element. This is why Janino is not a dependency of either service.

## `dynatrace` — application-only APM (Task 6)

| Key | Meaning |
|---|---|
| `tenant` | Tenant id, the `abc12345` in `abc12345.live.dynatrace.com` |
| `paas-token` | PaaS token (Settings → Integration → Platform as a Service), used by the initContainer to download the agent |
| `api-url` | `https://<tenant-id>.live.dynatrace.com/api` |

```bash
oc create secret generic dynatrace \
  --from-literal=tenant=<tenant-id> \
  --from-literal=paas-token=<PaaS token> \
  --from-literal=api-url=https://<tenant-id>.live.dynatrace.com/api
```

## Rotation

Replace a Secret in place and restart the workloads that read it:

```bash
oc delete secret splunk-hec
oc create secret generic splunk-hec --from-literal=url=... --from-literal=token=...
oc rollout restart deploy/booking-service deploy/queue-gate
```

Neither service re-reads the Secret while running: the values are injected as
environment variables at pod start, so a rotation needs a rollout.

## The `url` is the base URL, with no path

`splunk-library-javalogging` hardcodes the collector path and appends it to
whatever this key holds — `/services/collector/event/1.0`, from
`HttpEventCollectorSender`. Write the path in here as well and the appender
POSTs to:

    https://<host>:8088/services/collector/services/collector/event/1.0

which the collector answers with a 404. This key held that second
`/services/collector` for the whole of the observability work, and the cost of
it was the entire log pipeline: **not one application event was ever indexed.**

Nothing reported it. The appender is wrapped in an AsyncAppender with
`neverBlock` and `discardingThreshold 0`, so a 404 on every batch is discarded
without an application log line; the pods stayed healthy, the profile was
active, `oc get secret` looked right, and the console's panel showed the
collector reachable — because a *health* probe on the correct path really was
returning 200. Six events existed in `main` across all time, and they were all
hand-posted with curl while debugging.

Worth noting that `ObservabilityProbe.healthUrl` already defends against exactly
this doubling, and its Javadoc describes hitting it. The lesson that did not
transfer: the same trap was one field away, in the field that actually carried
the logs. A reachable collector is not a working pipeline — the only proof is an
indexed event.

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
| `url` | Full HEC collector endpoint, e.g. `https://http-inputs-<tenant>.splunkcloud.com/services/collector` | `SPLUNK_HEC_URL` in both Deployments |
| `token` | The HEC token value | `SPLUNK_HEC_TOKEN` in both Deployments |
| `profile` | The literal string `splunk` — the switch, see below | `SPRING_PROFILES_INCLUDE` in both Deployments |

Create the token first, in Splunk Cloud: **Settings → Data Inputs → HTTP Event
Collector → New Token**, named `rembayung`, sourcetype `_json`, index `main`.

```bash
oc create secret generic splunk-hec \
  --from-literal=url=https://http-inputs-<tenant>.splunkcloud.com/services/collector \
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

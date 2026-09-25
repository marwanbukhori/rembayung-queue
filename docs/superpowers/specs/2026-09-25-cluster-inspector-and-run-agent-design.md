# Cluster Inspector and Run Agent — Design

**Date:** 2026-09-25
**Status:** Draft, awaiting review
**Parent spec:** [`2026-09-02-rembayung-booking-queue-design.md`](2026-09-02-rembayung-booking-queue-design.md)
**Builds on:** [`2026-09-04-observability-design.md`](2026-09-04-observability-design.md) and the demo console (plan `2026-09-05-demo-console.md`)

---

## 1. Purpose

The console's simulation page shows what a rush does to the *business*: seats,
queue depth, oversold. It does not show what the rush does to the *platform*.
On 2026-09-25 a 200-VU rush left 4 bookings unclean, and finding out why took a
human with `oc` reading two pods' logs by hand: one booking-service pod ran
out of its 5 database connections while the other sat idle, and 19 of its 26
pool timeouts were health probes competing with bookings for connections.

Nothing on the console could have shown that. This design adds two things:

1. **A cluster inspector.** Every OpenShift object behind the simulation,
   clickable, with its status, events, its own logs, and four Prometheus charts
   over the same timeline. What the human did with `oc` on 2026-09-25 becomes
   something a visitor can watch.
2. **A run agent.** After every rush, a bounded AI agent reads the facts,
   investigates what looks wrong using read-only tools, and writes a short
   analysis: what went well, what it caught, what to look at. It runs on a free
   LLM that the Developer Sandbox already serves inside the cluster.

The audience is a hiring manager opening the hosted demo. Success is someone
starting a rush and *watching* it land: pods appear, the HPA scales, the pool
chart climbs, logs scroll, and a minute after it ends the agent explains what
happened and shows its working.

### Out of scope

- Replacing the OpenShift web console. This shows the objects this project
  owns, not the cluster.
- Arbitrary PromQL, arbitrary log search, or any write action on cluster
  objects from the inspector.
- Fixing the pool saturation and uneven load found on 2026-09-25. Those are
  separate changes; this design makes them visible.

---

## 2. Decisions already made

| Decision | Choice | Why |
|---|---|---|
| Where it runs | Inside the existing `console` Spring Boot app | No new Deployment, image, Route, CI or CD step. Quota barely moves; the LLM runs elsewhere. |
| Layout | Charts strip on top, object graph left, permanent inspector right. On a phone the inspector becomes a drawer. | A rush is watched; charts, graph and the selected pod's logs stay on screen together. |
| Raw pod logs | Access key only | Logs carry load-test phone numbers, internal hostnames and stack traces. |
| Public log view | Our structured app events only, phone numbers masked | The public still sees the system move. |
| Log transport | Polling every 2s with `sinceTime` | Matches the rest of the page, works through the Route unchanged, no WebSockets. |
| Metrics source | Thanos tenancy port, `thanos-querier.openshift-monitoring.svc:9092` | Namespace-scoped; the public route needs cluster-wide rights this sandbox does not grant. |
| PromQL | A fixed, named set on the server | A public endpoint must not run arbitrary queries. |
| Agent style | Bounded agent: fixed baseline facts, then up to 5 read-only tool calls, then a validated report | The interviewer values agents; an 8B model needs a budget and a fallback to stay trustworthy. |
| Agent framework | None. Plain HTTP to an OpenAI-compatible endpoint from Java. | One to seven model calls per run do not need LangChain or a Python service on a 3-CPU quota. |
| Model | `isvc-qwen3-8b-fp8` by default, configurable (`isvc-granite-31-8b-fp8` as alternative) | Both verified reachable from this namespace on 2026-09-25, HTTP 200, 40k / 64k context. |
| Report storage | One ConfigMap per run, last 20 kept | No database; the console already writes ConfigMaps. |

---

## 3. Verified facts this design depends on

Each was checked on 2026-09-25 rather than assumed.

- **LLMs:** `sandbox-shared-models` serves `isvc-qwen3-8b-fp8`,
  `isvc-granite-31-8b-fp8` and `isvc-nemotron-nano-9b-v2-fp8` via vLLM. From a
  pod running as the `console` service account,
  `GET https://isvc-qwen3-8b-fp8-predictor.sandbox-shared-models.svc.cluster.local:8443/v1/models`
  returned 200.
- **Prometheus:** the tenancy port answered 403,
  `Forbidden (user=system:serviceaccount:marwanbukhori-dev:console, verb=get, resource=pods)`.
  The console Role lacks `get` on `pods` in `metrics.k8s.io`. §7 adds it.
  **Still unverified:** that our ServiceMonitor is scraped. First task of
  implementation.
- **Histograms:** neither queue-gate nor booking-service enables
  `percentiles-histogram` for `http.server.requests`, so p95 cannot be computed
  in Prometheus yet. §6.3 adds it.
- **k6 summary:** `loadtest/drop.js` has no `handleSummary`. §8.2 adds one.
- **Load Job lifetime:** `LoadOps` sets `ttlSecondsAfterFinished: 900`. A load
  Job and its logs are gone 15 minutes after it finishes, so the agent must
  capture everything it needs at analysis time.
- **No scheduler:** the console has no `@EnableScheduling` yet.
- **Redis logs:** redis logs only its lifecycle (start, ready), never commands.
  A rush produces no redis log lines at all.
- **Native tool calling:** unknown whether the shared vLLM server has
  `--enable-auto-tool-choice` for Qwen3. §8.4 works either way.

---

## 4. Architecture

```
Browser (Angular, cluster page)
   │  polls every 2s
   ▼
console (Spring Boot, existing Deployment)
   ├─ ObjectsService    → Kubernetes API: routes, services, endpointslices, deployments,
   │                      replicasets, pods, hpa, jobs, cronjobs, events            [public]
   ├─ PodLogsService    → Kubernetes API: pods/log                               [raw: key]
   │                      └─ EventFilter: structured app events, masked         [public]
   ├─ MetricsService    → Thanos tenancy :9092, four fixed queries              [public]
   └─ RunAgent          → watches load Jobs → tools → Qwen3 → validate → ConfigMap [public read]
                          └─ tools are the same services above, read-only
```

The browser never talks to Kubernetes, Prometheus or the LLM. The console holds
the only credentials and decides what is public, as it does today.

### Caching and load

Every source is cached server-side for 2 seconds, following the existing
`ObservabilityProbe` pattern. Ten viewers cost the cluster what one does.

### Failure isolation

Each source fails alone. Prometheus down means the charts say "metrics
unavailable" while the graph and logs keep working. No endpoint returns 500
because one upstream failed; it returns the parts it has and says which part is
missing. This is the rule the existing panels already follow.

---

## 5. UI

### 5.1 Layout

- **Top:** charts strip, four charts, last 15 minutes, shared time axis.
- **Left:** the existing object graph, every node clickable.
- **Right:** a permanent inspector showing the selected object.
- **Under 900px wide:** the inspector becomes a drawer from the right.

The selected object is kept in the URL (`?inspect=pod/booking-service-874f94d9-mfnlb`),
so a link can point at a specific pod.

### 5.2 Charts strip

| Chart | Shows |
|---|---|
| Requests/s | queue-gate, split 2xx / 4xx / 5xx |
| Latency p95 | queue-gate and booking-service |
| DB pool in use | `hikaricp_connections_active` per booking-service pod, against the pool size of 5 |
| Replicas | HPA desired vs current, both services |

A rush's start and end are drawn as markers across all four. Selecting a pod
highlights its line where the chart is per pod.

### 5.3 Inspector by kind

Tabs appear only where they mean something.

| Kind | Overview | Other tabs |
|---|---|---|
| **Route** | Host, TLS termination and redirect, target Service and port, live answer check with latency (as `status.sh` does) | Events |
| **Service** | Selector, ports, ready endpoints (red when 0), the NetworkPolicy that fences it | Events |
| **Deployment** | Ready/desired, image tag, HPA position, ReplicaSets (current and kept), probes, its pods | Events |
| **Pod** | Phase, ready, restarts, node, age, owning ReplicaSet | Logs, Events |
| **HPA** | min / current / max, CPU vs target, last scale and its reason, ScalingActive / AbleToScale | Events |
| **Job** (a rush) | VUs, duration, booked / rejected / not clean, p50 / p95 / max | Logs (while the Job exists), Analysis |
| **CronJob** (keepalive) | Schedule, last run and result, next run, recent runs | Events |

ReplicaSets appear inside the Deployment view, not as graph nodes, to keep the
graph readable.

**Redis** gets the standard Deployment and Pod views. Its Overview adds what it
holds (tickets issued, admitted, queue depth) from queue-gate's
`/internal/drops/{id}/state`, which the console already reads. The console
cannot reach redis directly; the NetworkPolicy admits queue-gate only. Its Logs
tab states plainly that redis logs only lifecycle events.

**Past rushes:** the Job inspector lists runs from the stored analysis
ConfigMaps, not from Jobs, because Jobs are deleted after 15 minutes.

### 5.4 Logs tab

- Filters: **all** · **warn and above** · **app events**. Without the key the
  filter is locked to app events and the other two are shown disabled with a
  one-line reason.
- Newest at the bottom, auto-scroll unless the reader has scrolled up.
- Structured JSON lines are rendered as `time LEVEL message`. Anything else is
  shown verbatim.

---

## 6. Data side

### 6.1 Endpoints

All GET, so public by default under the existing `KeyFilter` rule. Exceptions
are enforced on the server, not the UI.

| Endpoint | Returns |
|---|---|
| `GET /api/objects` | All objects of the kinds in §5.3 with status and owner links, for the graph |
| `GET /api/objects/{kind}/{name}` | Inspector detail for one object, including its events |
| `GET /api/pods/{name}/logs?since=&filter=all\|warn\|events` | Lines since `since`. Without the key, `filter` is forced to `events`. |
| `GET /api/metrics/{chart}?minutes=15` | One chart's series. `chart` must be one of the four names in §6.3; anything else is 404. |
| `GET /api/runs` | Stored analyses, newest first |
| `GET /api/runs/{job}` | One analysis: facts, trail, report |
| `POST /api/runs/{job}/reanalyse` | Key only (POST). Re-runs the agent on the stored facts. |

### 6.2 Log rules

- Only pods in this namespace carrying `app.kubernetes.io/part-of=rembayung-queue`
  or a load Job's labels. Any other name is 404.
- At most 500 lines per request.
- **App events** are JSON lines whose logger belongs to our packages
  (`dev.marwan.*`) at INFO or above, plus WARN and ERROR from anywhere. The
  exact list is fixed in `EventFilter` and unit-tested.
- Phone numbers matching `\+?60\d{7,10}` are masked to `+6012••••` for every
  reader, key holders included. The demo never needs them.

### 6.3 Fixed queries

All scoped by the tenancy port's `namespace` parameter.

| Name | PromQL |
|---|---|
| `requests` | `sum by (status) (rate(http_server_requests_seconds_count{job="queue-gate"}[1m]))`, status bucketed 2xx/4xx/5xx |
| `latency` | `histogram_quantile(0.95, sum by (le, job) (rate(http_server_requests_seconds_bucket{job=~"queue-gate\|booking-service"}[1m])))` |
| `pool` | `hikaricp_connections_active{job="booking-service"}` by pod |
| `replicas` | `kube_horizontalpodautoscaler_status_desired_replicas` and `…_current_replicas` by HPA |

The `latency` query needs, in both services' `application.yml`:

```yaml
management.metrics.distribution.percentiles-histogram.http.server.requests: true
```

Label names (`job`, `status`) are to be confirmed against the scraped series in
the first implementation task and corrected here if they differ.

---

## 7. RBAC

Added to the `console` Role in `deploy/base/console/rbac.yaml`. CD withholds
RBAC, so this is applied by hand once, and `RUN-THESE.md` gets the command.

| apiGroup | Resource | Verbs | For |
|---|---|---|---|
| `""` | `pods/log` | get | Logs tab, agent tools |
| `metrics.k8s.io` | `pods` | get | Thanos tenancy port authorisation |
| `discovery.k8s.io` | `endpointslices` | get, list | Service endpoints |
| `apps` | `replicasets` | get, list | Deployment view |
| `apps` | `deployments` | get, list | Graph and Deployment view |
| `batch` | `cronjobs` | get, list | CronJob view |
| `batch` | `jobs` | list (adds to existing get, create, delete) | Past and current rushes |
| `""` | `configmaps` | list, delete (adds to existing get, create, update) | Storing and pruning analyses |

Nothing here grants secrets, exec, or any write on workloads.

---

## 8. The run agent

### 8.1 Trigger

A scheduled task in the console (`@EnableScheduling`, every 10s) lists load
Jobs that are Complete or Failed and have no `analysis-<job>` ConfigMap, and
analyses each, one at a time, off the request path.

It reconciles "finished without a report" rather than reacting to an event, so
a console restart mid-analysis loses nothing: the next tick picks the Job up.
If a Job is already gone when the console comes back, the run gets no report,
and that is logged.

### 8.2 Baseline facts

Gathered for the window from Job start to Job end plus 30 seconds. Each fact
has a stable id (`F1`, `F2`, …), a label and a value.

| Source | Facts |
|---|---|
| k6 | VUs, duration, booked, rejected, not clean, p50 / p95 / max. `drop.js` gains a `handleSummary` that prints one line, `K6_SUMMARY {json}`, which is parsed instead of the text table. |
| Prometheus | Peak pool in use per booking-service pod, 5xx count, peak p95, peak HPA replicas and when scaling began |
| Kubernetes | Warning events in the window, pod restarts, pods that went unready |
| Logs | Pool-timeout count per booking-service pod, split by request threads (`http-nio-8081`) and probe threads (`http-nio-9090`) |
| Invariant | Oversold count from `/api/state`, which must be 0 |

### 8.3 Tools

Read-only, the same services the inspector uses, each with bounded output:

| Tool | Returns |
|---|---|
| `pod_logs(pod, level, contains?)` | Up to 40 matching lines in the run window, masked |
| `metric(chart, pod?)` | The series for one of the four charts in the run window, downsampled to 30 points |
| `events(kind, name)` | Events for one object in the window |
| `pod_status(pod)` | Phase, ready, restarts, node, owning ReplicaSet |
| `endpoints(service)` | Ready endpoint pods now. Kubernetes keeps no endpoint history, so readiness during the run comes from `events` (probe failures) instead. |

Every result is appended as a new fact (`F14`, …) so the report can cite it.

### 8.4 The loop

1. **Investigate.** The model receives the baseline facts and the tool menu and
   may call up to **5** tools. Native tool calling is used if the server
   supports it; otherwise the model returns `{"call": "<tool>", "args": {…}}` or
   `{"done": true}`, and the console runs the call. The first implementation
   task makes one test call to find out which applies.
2. **Report.** The model returns JSON: `went_well[]`, `caught[]`, `look_at[]`,
   each item `{ "text": "...", "facts": ["F3", "F14"] }`.
3. **Validate.**
   - Every cited fact id exists.
   - Every number in `text` appears in the cited facts' values.
   - On failure, one retry with the validation errors fed back.
   - On a second failure, store a **deterministic report built from the facts
     alone**, labelled "model unavailable". A run always gets a report.

Model settings: Qwen3 thinking disabled (`chat_template_kwargs.enable_thinking: false`),
temperature 0.2, 60s timeout per call, 3 minutes per run in total. When the
budget runs out, it goes straight to the report.

### 8.5 Storage

ConfigMap `analysis-<job>`, labelled `app.kubernetes.io/component=run-analysis`:

- `facts.json`: baseline and tool facts
- `trail.json`: each tool call, the reason the model gave, and the result's fact id
- `report.json`: the validated report
- `meta.json`: model, prompt version, timings, validation outcome

The newest 20 are kept; older ones are deleted by the same scheduled task.
Each is well under the 1 MiB ConfigMap limit because tool outputs are bounded.

### 8.6 Analysis tab

- The three lists, each item with its fact ids as chips. Clicking a chip shows
  the fact's label and value.
- **Trail:** "looked at X because Y → found Z", one line per tool call.
- A footer with model, duration, and whether the report is model-written or the
  deterministic fallback.
- Key holders get **Re-analyse**, which reruns the agent on the stored facts,
  so a prompt or model change can be compared on the same run.

---

## 9. Build order

Each step ships and is useful alone.

1. **Graph and inspector.** `/api/objects`, the clickable graph, the inspector
   for all kinds in §5.3, without logs or charts. RBAC applied.
2. **Logs.** `/api/pods/{name}/logs`, `EventFilter`, masking, the Logs tab.
3. **Charts.** Confirm scraping and label names, enable histograms,
   `/api/metrics/{chart}`, the charts strip.
4. **Run agent.** `handleSummary`, the scheduler, baseline facts, tools, the
   loop, validation, storage, the Analysis tab.

---

## 10. Testing

- **Unit:** `EventFilter` and masking; the key rule forcing `filter=events`;
  chart names outside the fixed set returning 404; fact validation (unknown
  ids, numbers not in facts, retry, fallback); `K6_SUMMARY` parsing; the tool
  budget stopping at 5; pruning keeping 20.
- **Agent against a fake model:** a stub OpenAI-compatible server returning
  scripted tool calls and reports, including a looping model, one that invents
  a number, and one that times out. Each must end in a stored, valid report.
- **Against the cluster, once per step:** a rush, then check the graph moves,
  the pool chart shows both pods, the Logs tab refuses raw logs without the
  key, and an analysis appears within a minute of the Job finishing.
- **Replay of 2026-09-25:** the agent, run on facts matching that rush, should
  catch the pool saturation on one pod and the probe-thread share. If it does
  not, the prompt or the baseline facts are wrong.

---

## 11. Risks

| Risk | Mitigation |
|---|---|
| The shared models are a platform service we do not control and may change or vanish | Model name is config; the deterministic fallback means a run always gets a report |
| An 8B model writes plausible but wrong analysis | Fact citation and number validation; the fact chips let a reader check every claim |
| Log polling from many viewers loads the API server | 2s server-side cache per pod; 500-line cap |
| RBAC drift, since CD does not apply it | `check-drift.sh` already diffs RBAC; `status.sh` gains a line checking the tenancy port answers |
| Our ServiceMonitor turns out not to be scraped | First task of step 3 checks; if so, the console scrapes `/actuator/prometheus` on the pods directly for the four series, and the design notes the change |

---

## 12. Amendments, 2026-09-25 (after step 1 shipped)

### 12.1 One live page (supersedes §5.1's placement)

The graph and inspector move from the cluster page to the simulation page, so
a rush and what it does to the platform are watched on one screen. On desktop
(≥1280px) the simulation page becomes two columns, chosen as layout A in the
companion. Graph and inspector sit side by side from 1650px, the width at
which the graph keeps its 640px; between 1280 and 1649px the inspector sits
under the graph. Measured, not estimated, on 2026-09-25:

- **Left, the business side:** run panel, seat map and queue, live traffic.
- **Right, the platform side:** the charts strip (step 3) on top, then the
  object graph and the inspector side by side.

Under 1280px it is one column and the inspector is a drawer, as in step 1.
The cluster page keeps what explains rather than moves: the architecture
diagram, the workloads table, the monitoring panel, the log scenarios and the
CPU budget note. `?inspect=` deep links open the simulation page.

### 12.2 Splunk and Dynatrace trials have ended

Dynatrace's tenant stopped serving on 2026-09-24; Splunk Cloud's host
`prd-p-2d10o.splunkcloud.com` stopped resolving by 2026-09-25. Both are shown
by name with a "Trial ended" state and nothing else: no feeds, links, searches
or screenshots. Splunk gains `SPLUNK_DISABLED_REASON`, mirroring
`DYNATRACE_DISABLED_REASON`; when set, the console does not probe the
collector. Prose that names them (home page, pipeline diagram) says their
trials ended.

### 12.3 Build order, revised

Step 2 is split: **2a** is §12.1 and §12.2; **2b** is the Logs tab (§5.4,
§6.1, §6.2) and redis's "what it holds" line.

### 12.4 Log rules as built (supersedes parts of §6.2)

- **App events** are JSON lines carrying an `event` field (`queue.arrival`,
  `booking.claimed`, …), not "our loggers at INFO plus any WARN/ERROR". Every
  business moment in both services already logs one, and excluding WARN/ERROR
  from the public view keeps raw exception text - which can carry anything -
  behind the key.
- **Masking** covers `+60` followed by 3–11 digits (the load test's short
  numbers included) and bare `60` runs of 8–10 digits; a match keeps its first
  five characters then `••••`. The spec's `\+?60\d{7,10}` missed the load
  test's numbers.
- **Redis pods** are shown whole to everyone: redis logs only its lifecycle.
- The log read is capped by line count (500), not bytes: the API applies
  `limitBytes` from the start of the tail, which dropped the newest lines.

### 12.5 Charts read two sources (supersedes §6.3's single source)

History comes from Prometheus through the tenancy port, verified working on
2026-09-25 once the console could get `pods.metrics.k8s.io` and booking-service's
NetworkPolicy admitted the monitoring namespace on 9090 - before that change
booking-service had never been scraped. Each chart also shows a "now" reading
taken straight from the pods' `/actuator/prometheus` every two seconds, fresher
than the 15-second scrape. Either source can fail alone; the chart says which.
Latency has no live reading: a quantile needs Prometheus's windowed histogram.

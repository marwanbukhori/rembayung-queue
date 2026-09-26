# 11 — The cluster inspector and the live page

**Covers:**
- The clickable object graph and its inspector.
- Pod logs and who may read them raw.
- The four charts, and why they read Prometheus two ways.
- Merging the simulation and cluster views into one banded page.
- The demo key visitors can fetch for themselves.
- Malaysia time everywhere.
- The four live-cluster problems this surfaced, and what fixed each.

---

## What a visitor sees

The simulation page is one page, laid out in full-width bands. That way nothing important waits at the bottom of a tall column.

1. **The rush.** The run panel, the seats-and-queue card and the live traffic feed side by side. When a rush is in flight, a banner sits above them.
2. **The platform.** Four charts in one row: requests per second, p95 latency, database pool in use per booking-service pod, and replicas per autoscaler.
3. **The objects.** A graph of every object behind the demo: Routes, Services, Deployments, the HPAs, the NetworkPolicies, the CronJob. Clicking one opens the inspector beside the graph. The graph shrinks to make room rather than being covered.
4. **The cluster, right now.** One square per pod against the 3000m CPU budget, and the live pod table.

The inspector shows, for any object:
- a headline and its facts;
- the related objects, as chips that open them;
- its Kubernetes events;
- for a pod, its logs;
- for a load run's Job, the run agent's report (note 12).

---

## One backend, reading on behalf of the visitor

Everything comes from the console's own ServiceAccount, scoped to this namespace and denied Secrets.
- `ObjectsProvider` reads the Kubernetes API through fabric8.
- `MetricsService` reads Prometheus.
- `PodLogs` reads logs.

Every read is a `GET`, so `KeyFilter` lets it through without the key (`console.public-reads`). Anything that changes the cluster is a `POST` and needs the key.

### Logs: public events, key-holder raw lines

A pod's raw log can hold things a stranger should not read: stack traces, SQL errors, a customer's phone number.
- Without the key, the Logs tab shows only the application's structured `event` lines, with phone numbers masked (`+6012••••`).
- With the key, it shows raw lines, also masked.

The key rule lives on the server, in `PodLogs.read(…, keyHolder)`. The UI cannot ask its way around it. Fetches are capped at 500 lines and cached for two seconds per pod, so many viewers polling the same pod cost the API server one read.

### Charts: Prometheus for history, the pods for "now"

History comes from Prometheus through the **Thanos tenancy port** (`thanos-querier.openshift-monitoring.svc:9092`).
- The port scopes every query to the `namespace` parameter.
- It authorises the caller by asking whether it may `get pods.metrics.k8s.io`. The Role had to gain exactly that verb, and without it the port answered 403.

Prometheus scrapes every 15 seconds. That is too slow for the bold "now" figure above each chart, which is read straight from the pods' `/actuator/prometheus`.

Windows snap to 5, 15, 30 or 60 minutes, and reads are single-flight on virtual threads. A dozen viewers therefore cause one query, not twelve.

---

## The demo key

The console key used to be something you had to be sent. For a hosted demo, that meant a hiring manager who opened the link cold could look but not start a rush.
- `GET /api/demo-key` returns the key while `CONSOLE_SHARE_KEY=true` is set on the Deployment, and 404 otherwise.
- The header shows **Get the demo key** to a visitor without it.

This is deliberately public: the guards that matter are the one live run at a time, the CPU quota and the expiring sandboxes, not the key. Turning the setting off makes the key private again with no code change.

---

## Time

Every time on the site is Malaysia time (GMT+8, `Asia/Kuala_Lumpur`), through one formatter, `malaysiaTime`. The audience and the 21:00 drop are in Malaysia, and a page that showed UTC to some readers and local time to others would be wrong for someone.

The formatter also trims the kubelet's nanosecond timestamps, which `Date` cannot parse as they come.

---

## Four things the live cluster taught

**1. booking-service at zero replicas.** An init container fetched the Dynatrace OneAgent. When the Dynatrace trial ended, that download returned 404, so every new pod failed, and a rollout left the Deployment at 0.
- Dynatrace was disabled, not removed: the component is kept in `deploy/base/dynatrace`, switched off.
- The console shows Dynatrace and Splunk as "trial ended".

**2. The ReplicaSet quota.** The Developer Sandbox allows 30 ReplicaSets. Kubernetes keeps ten old ones per Deployment by default, so after enough deploys new rollouts could not create theirs. `revisionHistoryLimit` is 5 (2 for redis), well inside the limit.

**3. Autoscalers do not scale up from zero.** A Deployment someone scaled to 0 stayed at 0 however much traffic arrived. The keepalive CronJob now restores replicas as well as keeping the sandbox awake.

**4. A metrics scrape that starved bookings.** The slot gauges read Oracle at scrape time: four gauges on each of five slots, twenty round trips to a database in another region. One `/actuator/prometheus` took about 4.6 seconds, and every Prometheus scrape borrowed connections from the same five-connection pool the bookings use.
- They now read one snapshot of every slot, loaded with one query and reused for five seconds.
- A scrape takes about 0.8 s.
- A failed read is remembered for five seconds too, rather than retried by every gauge in turn.

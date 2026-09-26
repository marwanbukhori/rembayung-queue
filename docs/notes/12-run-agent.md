# 12 — The run agent

**Covers:**
- The agent that analyses every rush: what it knows before the model is asked anything, how the model is bounded, and the rule that makes its reports trustworthy.
- The customer funnel.
- The two-wave rush and its before-and-after.
- The time-to-seat facts.
- Where reports are kept.
- What the live cluster taught it: six bugs, each found only by running it.

---

## What it does

About 45 seconds after a load Job finishes, a reconciler in the console notices the run has no report. The agent then:

1. **Gathers baseline facts.** These are fixed and gathered the same way every time: k6's outcome counts, the oversold invariant from each sitting, Prometheus peaks (pool, p95, 5xx, replicas), Warning events, restarts, and pool timeouts per pod. Each fact gets an id, F1, F2, and so on.
2. **Lets the model investigate.** Qwen3 8B, served free inside the Developer Sandbox, may make up to **five** read-only tool calls: logs, a chart, events, a pod's status, a Service's endpoints. Each answer becomes a new fact. The calls go through the console's own MCP server (note 13).
3. **Asks for a report.** Five sections: Summary, Where the customers went, Capacity and scaling, Errors, Look at next. A two-wave run adds a sixth, **Before and after**. Every claim lists the fact ids it rests on.
4. **Validates it.** Every cited id must exist, and every number in a claim must appear in a cited fact. On failure the model gets one retry, with the problems quoted back to it.
5. **Falls back if needed.** If the second answer also fails, or the model is down or too slow, the run gets a report **built from the facts by rules**, labelled as such. A run always gets a report.

It is bounded on every side: five calls, 60 seconds a call, three minutes a run, one retry. An 8B model on a shared server needs a budget, and a reader needs to know that anything they see was checked.

### The rule that makes it worth reading

A small model writes fluent sentences whether or not it read the facts correctly. The validator does not judge the prose. It checks the numbers.

"4 bookings failed with 503" passes only if some cited fact contains 4. Numbers are matched as whole tokens:
- "55" inside a pod name does not count;
- "2400ms." at the end of a sentence does.

Numbers that appear in fact labels, such as status codes like `503` or `p95`, are vocabulary and allowed anywhere. The live cluster forced that distinction (below).

The model is told not to compute. Products, differences and percentages would be new numbers no fact contains, so they would be rejected. Where a derived number matters, the console computes it and states it as a fact the model can cite, for example "Admissions possible within patience: 90".

---

## The funnel: where every customer went

The question that started this was a visitor's: "I sent 200 customers and got 186 seats. Where did the rest go?"

k6 now counts each customer's path. Two steps, **joined** and **admitted**, then exactly one outcome each:
- booked (201);
- gave up waiting (403);
- sold out at the queue or at booking (409);
- admitted but refused (403);
- overloaded (503);
- a fault at the queue or at booking;
- did not finish.

It prints them on one line, `K6_SUMMARY {json}`, which the agent reads instead of k6's human table. The outcomes add up to the customers who arrived, and a test harness checks that they do.

A live run at 1 admission per second and 90 seconds of patience answered the question:

> 200 arrived, 200 joined, 92 admitted and booked (184 seats); 108 gave up waiting and were refused (403). At 1 per second, 90 seconds of patience admits about 90.

Each customer books a party of 2, which is why 60 customers take 120 seats.

The page draws the funnel from these facts, never from the model's sentences.

---

## What the database allows

Every report now says how the database limits the queue as a whole, as facts:
- the booking connections available (pool size × booking-service pods);
- the peak rate bookings actually committed;
- how long seating every customer would take at that rate;
- how many rounds of the pool that is.

A live 200-customer rush: 10 connections, 0.5 bookings committed per second at best, **400 s** to seat everyone, 20 rounds. That is why a rush books about 30 of 200 however many queue-gate pods there are.

---

## The two-wave rush

A one-wave rush is over in about two minutes, before an added pod is ready. The optional two-wave rush sends the same wave twice, three minutes apart. Each wave goes to **its own fresh sitting**: a sitting has 250 seats and 250 tickets, so sharing one would let wave 1 sell out and leave wave 2 testing "sold out" instead of the pods.

The report compares the waves. The live run:
- queue-gate went from 2 to 10 pods between the waves;
- booking-service stayed at 2;
- both waves booked about 33 of 200, with the same p95 and 503s.

The verdict, "No clear difference: more pods did not bring a lower p95", is right. The autoscaler scaled the service that was not the bottleneck. booking-service's autoscaler watches CPU, and its limit is database connections.

Each wave was also given its own `maxDuration`. Otherwise k6 holds both waves' virtual users at once, twice the memory the pod is sized for, and wave 2 can outlast the Job's 600-second deadline before k6 prints its summary. `k6 inspect` showed both problems and then their fixes: peak VUs 6 → 3, total duration 11m30s → 6m10s.

---

## Where reports live

One ConfigMap, `run-analyses`, keeps the newest 12 runs.
- **One ConfigMap, not one per run:** the console may get, create and update ConfigMaps but not list or delete them, and CD cannot widen that. One object needs neither.
- **Keyed by job and start time:** every rush on a drop reuses the Job name `load-<drop>`, so the name alone would give a second rush the first one's report.
- **Updates carry the resourceVersion they read,** so two writers cannot overwrite each other silently.

---

## What only running it found

1. **The model answered HTTP 302.** The shared model sits behind a login that accepts a Kubernetes token. The console now sends its own ServiceAccount token, but only to an `https://*.svc.cluster.local` URL, and it never follows redirects.
2. **Reports could not be saved after the first.** fabric8's serialiser throws on the `managedFields` the API returns with every object, so a create worked and every update failed. Every write now sends a clean object: name, labels, resourceVersion, data. This was reproduced locally against the live ConfigMap before it was fixed.
3. **An empty `{}` in the model's JSON failed the whole report.** Empty items are now dropped; they make no claim.
4. **"503 errors" failed validation.** The status code is vocabulary, not a measurement, which is why numbers in fact labels are now allowed anywhere.
5. **The pool size was stated but not a fact.** "Pool at 5 of 5" was rejected until the pool size became a baseline fact. The prompt now also says a full pool is saturated, not efficient.
6. **Booked exceeded admitted.** Admission is by time, so a customer admitted in the second after their last poll booked successfully but was never counted as admitted. k6 now counts that booking as an admission.

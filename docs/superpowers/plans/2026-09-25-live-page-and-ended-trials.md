# Step 2a: One Live Page, and Splunk and Dynatrace Shown as Ended — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The simulation page becomes the one live page (business left, platform right, full desktop width), and Splunk and Dynatrace appear everywhere by name only, marked "Trial ended".

**Architecture:** Splunk gains a `SPLUNK_DISABLED_REASON` setting that mirrors the Dynatrace one already shipped: the console stops probing the collector and reports a `disabled` reason. The Angular pages read that reason (monitoring panel, log scenarios) or state it in prose (home page, pipeline diagram, evidence strip). The object graph and inspector move from the cluster page into a right-hand column on the simulation page, which gets a wider page cap than the rest of the site.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5 + AssertJ; Angular standalone components with signals.

**Spec:** [`docs/superpowers/specs/2026-09-25-cluster-inspector-and-run-agent-design.md`](../specs/2026-09-25-cluster-inspector-and-run-agent-design.md), §12 (amendments of 2026-09-25): §12.1 one live page, §12.2 trials ended, §12.3 step 2a.

## Global Constraints

- Everything stays in the existing `console` app; no new Deployment, image, Route, CI or CD step.
- Nothing returns 500 because something upstream is down: a disabled vendor is a reason, not an error.
- Turning a vendor back on is configuration only: remove its `*_DISABLED_REASON` env var from `deploy/base/console/deployment.yaml` (and, for Dynatrace, include the component, as that component's README says).
- Desktop two-column layout from 1280px; graph and inspector side by side from 1500px; one column and the inspector as a drawer below 1280px.
- The graph's SVG keeps `min-width: 640px` inside its own horizontal-scroll frame, and the page itself must never scroll sideways.
- Commit messages: plain sentences in the repository's existing style, with no AI attribution and no `Co-Authored-By` or `Generated with` trailers.
- Java builds need `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`.

## Review Focus

1. **Only one vendor ended.** Splunk ended but Dynatrace re-enabled, or the reverse. Expected: each block decides for itself; the header pill says which is off, and nothing claims "both online". Pinned by the header logic in Task 2 and the probe test in Task 1.
2. **A deep link to an object.** Someone opens `/?inspect=pod/…`. Expected: the app lands on the simulation page with that object selected, not on the home page with an invisible selection. Pinned in Task 3.
3. **The width in between.** 1280–1499px, where two columns fit but graph and inspector do not sit side by side. Expected: inspector below the graph, no sideways page scroll. Pinned by the Task 3 browser check at 1366px.
4. **No sandbox yet.** A visitor who has not started a simulation. Expected: the platform column still shows the live cluster (it is not the visitor's; it is the namespace), and the left column shows the run panel as today. Pinned in Task 3.
5. **Evidence images that no longer have a meaning.** The Splunk and Dynatrace screenshots on the docs page. Expected: not shown; the two tools are listed by name as ended. Pinned in Task 2.

---

## File Structure

| File | Change |
|---|---|
| `console/src/main/java/dev/marwan/console/observability/ObservabilityStatus.java` | `Splunk` record gains `disabled` |
| `console/src/main/java/dev/marwan/console/observability/ObservabilityProbe.java` | Reads `SPLUNK_DISABLED_REASON`; skips the collector probe when set |
| `console/src/test/java/dev/marwan/console/observability/ObservabilityProbeTest.java` | New test; constructor helper updated |
| `deploy/base/console/deployment.yaml` | `SPLUNK_DISABLED_REASON` env var |
| `console/ui/src/app/state.ts` | `SplunkStatus.disabled` |
| `console/ui/src/app/observability-panel.ts` | Each vendor collapses to name + "Trial ended" when disabled |
| `console/ui/src/app/log-scenarios.ts` | Collapses to its title + "Trial ended" when Splunk is disabled |
| `console/ui/src/app/evidence-strip.ts` | Splunk/Dynatrace shots removed; the two named as ended |
| `console/ui/src/app/public-home.ts` | Prose and tool list say the trials ended |
| `console/ui/src/app/pipeline-diagram.ts` | Label and summary say the trials ended |
| `console/ui/src/app/app.ts` | Wider page cap on the simulation page; `?inspect=` opens it |
| `console/ui/src/app/visitor.ts` | Two columns: business left, platform right |
| `console/ui/src/app/cluster-page.ts` | Graph and inspector removed |
| `console/ui/src/app/object-graph.ts` | Nothing, unless the Task 3 check needs a size tweak |

---

### Task 1: Splunk can be switched off like Dynatrace

**Files:**
- Modify: `console/src/main/java/dev/marwan/console/observability/ObservabilityStatus.java`
- Modify: `console/src/main/java/dev/marwan/console/observability/ObservabilityProbe.java`
- Modify: `console/src/test/java/dev/marwan/console/observability/ObservabilityProbeTest.java`
- Modify: `deploy/base/console/deployment.yaml`

**Interfaces:**
- Produces: `record Splunk(String endpoint, boolean reachable, String detail, long latencyMs, List<Feed> shippers, String disabled)`; JSON gains `splunk.disabled` (string or null). `ObservabilityProbe` constructor becomes `(KubernetesAccess, String hecUrl, String hecToken, String dynatraceTenant, String dynatraceDisabled, String splunkDisabled, boolean trustSelfSigned)`; `splunk(List<Pod>)` becomes package-private.

- [ ] **Step 1: Write the failing test**

In `ObservabilityProbeTest.java`, change the helper so every existing call keeps working, and add the new test:

```java
    /**
     * An ended trial is a decision, not an outage. With a reason configured the
     * collector is not probed at all - its host no longer resolves, and asking
     * it every fifteen seconds only produced "Could not reach the collector",
     * which reads as broken.
     */
    @Test
    void aDisabledSplunkSaysWhyAndIsNotProbed() {
        ObservabilityProbe probe = new ObservabilityProbe(null, "https://splunk.invalid:8088", "t",
                "https://abc12345.apps.dynatrace.com", "", "Trial ended 2026-09-25", true);

        ObservabilityStatus.Splunk splunk = probe.splunk(List.of(pod("queue-gate", "spring-boot")));

        assertThat(splunk.disabled()).isEqualTo("Trial ended 2026-09-25");
        assertThat(splunk.reachable()).isFalse();
        assertThat(splunk.detail()).isEqualTo("Trial ended 2026-09-25");
        assertThat(splunk.latencyMs()).isEqualTo(-1);
    }

    private static ObservabilityProbe probe(String disabledReason) {
        return new ObservabilityProbe(null, "", "", "https://abc12345.apps.dynatrace.com",
                disabledReason, "", true);
    }
```

(Replace the existing `probe(String)` helper with the one above; it gains the empty `splunkDisabled` argument.)

- [ ] **Step 2: Run it to see it fail**

Run: `cd console && ./mvnw -q test -Dtest=ObservabilityProbeTest`
Expected: compilation failure on the 7-argument constructor and `splunk(...)` not visible / `disabled()` undefined.

- [ ] **Step 3: Implement**

`ObservabilityStatus.java`, the `Splunk` record and its Javadoc:

```java
     * @param shippers  one entry per workload, saying whether it is configured to ship
     * @param disabled  why Splunk is switched off, or null when it is meant to be on
     */
    public record Splunk(String endpoint, boolean reachable, String detail,
                         long latencyMs, List<Feed> shippers, String disabled) { }
```

`ObservabilityProbe.java`:

1. Field beside `dynatraceDisabled`: `private final String splunkDisabled;`
2. Constructor parameter after `dynatraceDisabled`:
   `@Value("${SPLUNK_DISABLED_REASON:}") String splunkDisabled,` and in the body
   `this.splunkDisabled = splunkDisabled == null || splunkDisabled.isBlank() ? null : splunkDisabled.trim();`
3. `splunk(List<Pod> pods)` loses `private`, and gains this block right after `shippers` is built:

```java
        // Switched off on purpose: say why and do not ask. The Splunk Cloud
        // trial's host stopped resolving on 2026-09-25, and probing a name that
        // no longer exists every fifteen seconds only reported "Could not reach
        // the collector", which reads as an outage rather than a decision.
        if (splunkDisabled != null) {
            return new ObservabilityStatus.Splunk(hecUrl.isEmpty() ? "not configured" : hostOf(hecUrl),
                    false, splunkDisabled, -1, shippers, splunkDisabled);
        }
```

4. Every other `new ObservabilityStatus.Splunk(...)` in the method gains a trailing `, null`.

`deploy/base/console/deployment.yaml`, directly after the `DYNATRACE_DISABLED_REASON` entry:

```yaml
            # Splunk Cloud's trial host stopped resolving by 2026-09-25. Set, the
            # console stops probing it and the panel shows it as ended. Remove
            # this, with a fresh splunk-hec Secret, to turn it back on.
            - name: SPLUNK_DISABLED_REASON
              value: "Trial ended 2026-09-25. The Splunk Cloud host no longer resolves, so the console stopped probing it."
```

- [ ] **Step 4: Run the tests**

Run: `cd console && ./mvnw -q test -Dtest=ObservabilityProbeTest && ./mvnw -q test`
Expected: both exit 0; `ObservabilityProbeTest` has 8 tests, 0 failures; the full suite is 98 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add console/src/main/java/dev/marwan/console/observability console/src/test/java/dev/marwan/console/observability deploy/base/console/deployment.yaml
git commit -m "Let Splunk be switched off with a reason, as Dynatrace already can"
```

---

### Task 2: Both vendors shown by name, as ended

**Files:**
- Modify: `console/ui/src/app/state.ts`, `observability-panel.ts`, `log-scenarios.ts`, `evidence-strip.ts`, `public-home.ts`, `pipeline-diagram.ts`

**Interfaces:**
- Consumes: `splunk.disabled` from Task 1; `dynatrace.disabled` (already shipped).

- [ ] **Step 1: The type**

In `state.ts`, `SplunkStatus` gains, after `shippers: Feed[];`:

```ts
  /** Why Splunk is switched off on purpose, or null when it is meant to be on. */
  disabled: string | null;
```

- [ ] **Step 2: The monitoring panel**

In `observability-panel.ts`:

1. Add, beside `dynatraceOff`:

```ts
  protected readonly splunkOff = computed(() => !!this.status()?.splunk.disabled);

  /** Both switched off: the panel is a record of what was here, not a monitor. */
  protected readonly allOff = computed(() => this.splunkOff() && this.dynatraceOff());
```

2. Replace `bothUp` with:

```ts
  /** Everything that is meant to be on, is. A vendor switched off does not count against it. */
  protected readonly bothUp = computed(() =>
    (this.splunkOff() || (this.status()?.splunk.reachable ?? false))
    && (this.dynatraceOff() || this.agentCount() > 0));
```

3. Header pill text becomes:

```html
          <span class="pill" [class.ok]="bothUp() && !allOff()" [class.bad]="!bothUp()" [class.off]="allOff()">
            <span class="dot" [class.beat]="bothUp() && !allOff()"></span>
            {{ allOff() ? 'Trials ended' : bothUp() ? headline() : 'Check the rows below' }}
          </span>
```

   with

```ts
  protected headline(): string {
    if (this.splunkOff()) {
      return 'Dynatrace online, Splunk off';
    }
    return this.dynatraceOff() ? 'Splunk online, Dynatrace off' : 'Both monitors online';
  }
```

4. Wrap the probe bar in `@if (!allOff()) { … }`.
5. Wrap the Splunk block's contents: when `splunkOff()`, render only the name row:

```html
        <div class="vendor-block">
          @if (splunkOff()) {
            <div class="vendor-name-row">
              <span class="vendor-name">Splunk</span>
              <span class="pill sm off"><span class="dot"></span>Trial ended</span>
            </div>
          } @else {
            <!-- the existing .vendor-grid, unchanged -->
          }
        </div>
```

6. The Dynatrace block gets the same shape (`@if (dynatraceOff())` → name row with `Trial ended`; `@else` the existing grid). The existing disabled rendering inside the grid (the "Why" fact, the hidden link) stays for the `@else` path and becomes unreachable only while disabled, which is intended.
7. Wrap the closing `<p class="caveat">…</p>` in `@if (!allOff()) { … }`.
8. `.pill.off` already exists from the Dynatrace change; keep it.

- [ ] **Step 3: The log scenarios card**

In `log-scenarios.ts`, inject the status: `private readonly observability = inject(ObservabilityService);` (import from `./observability.service`) and `protected readonly ended = computed(() => !!this.observability.status()?.splunk.disabled);` (add `computed`, `inject` to the Angular import). Wrap the template's body so that when `ended()` it renders only the card's existing heading element followed by:

```html
<span class="pill-ended">Trial ended</span>
<p class="ended-note">These were saved Splunk searches over one rush. The trial has ended, so they are no longer shown.</p>
```

with styles:

```css
    .pill-ended { display: inline-block; margin-top: 6px; padding: 3px 10px; border-radius: 999px; font-size: 12px;
                  background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .ended-note { margin: 8px 0 0; font-size: 14px; color: var(--muted); }
```

(Read the component's template first: keep its outer card and heading markup exactly, and put the rest under `@else`.)

- [ ] **Step 4: The evidence strip**

In `evidence-strip.ts`, delete the `splunk.png` and `dynatrace.png` entries from `all`, and add under the grid, inside `<section class="evidence">`:

```html
      <p class="ended">
        <strong>Splunk</strong> and <strong>Dynatrace</strong>: trials ended. Their captures were
        retired with them.
      </p>
```

with `.ended { margin: 0; font-size: 14px; color: var(--muted); }`. Change the `.sub` paragraph's first sentence to "The pipeline that ships this, captured rather than embedded." and drop the sentence about the two monitors.

- [ ] **Step 5: Home page and pipeline diagram prose**

`public-home.ts`:
- Hero: `watched by <strong>Prometheus</strong> (Dynatrace and Splunk were wired in too; both trials have since ended).`
- Tool list: `{ name: 'Dynatrace', what: 'trial ended - application-only OneAgent, now switched off' }` and `{ name: 'Splunk', what: 'trial ended - JSON events over HEC, now switched off' }`.

`pipeline-diagram.ts`:
- Line 149 label: `'Prometheus (Dynatrace, Splunk: trials ended)'`
- Line 176 summary tail: `'… Prometheus observes what results; Dynatrace and Splunk did until their trials ended.'`

- [ ] **Step 6: Build**

Run: `cd console/ui && npx ng build`
Expected: `Application bundle generation complete.`, no errors.

- [ ] **Step 7: Commit**

```bash
git add console/ui/src/app/state.ts console/ui/src/app/observability-panel.ts console/ui/src/app/log-scenarios.ts console/ui/src/app/evidence-strip.ts console/ui/src/app/public-home.ts console/ui/src/app/pipeline-diagram.ts
git commit -m "Show Splunk and Dynatrace by name only, now both trials have ended"
```

---

### Task 3: One live page, business left and platform right

**Files:**
- Modify: `console/ui/src/app/app.ts`, `visitor.ts`, `cluster-page.ts`

**Interfaces:**
- Consumes: `<rb-object-graph />`, `<rb-inspector />`, `InspectorService.selected` (step 1).

- [ ] **Step 1: A wider page for the simulation, and deep links land on it**

In `app.ts`:
1. `<main>` becomes `<main [class.wide]="surface() === 'visitor'">`.
2. Styles, after the `main { … }` rule: `main.wide { max-width: 1680px; }`
3. The initial surface honours a deep link:

```ts
  /** A link to an object (`?inspect=kind/name`) opens where the inspector lives. */
  readonly surface = signal<Surface>(
    new URL(window.location.href).searchParams.has('inspect') ? 'visitor' : 'home');
```

- [ ] **Step 2: The two columns**

In `visitor.ts`, import `Inspector` (`./inspector`) and `ObjectGraph` (`./object-graph`) and add both to `imports`. Restructure the template body (below the crumbs, above the exit buttons) as:

```html
      <div class="live">
        <div class="business">
          <rb-run-panel />
          @if (sandbox()) {
            <rb-run-banner />
            <rb-canonical-drop heading="Your simulation, live" />
            <rb-traffic-log />
            <rb-pod-pulse />
            <p class="reason"><!-- the existing paragraph, unchanged --></p>
          }
        </div>

        <!--
          The platform side, always shown: it is the namespace, not the visitor's
          sandbox, so there is something to look at before a rush as well as
          during one. The charts strip (step 3) lands at the top of this column.
        -->
        <section class="platform card">
          <div class="why">The platform, live</div>
          <p class="note">
            Every object behind the simulation. Click one to inspect it; start a rush and watch the
            autoscalers and pods move.
          </p>
          <div class="graph-and-inspector">
            <rb-object-graph />
            <rb-inspector />
          </div>
        </section>
      </div>
```

Keep the existing HTML comments that explain each child, moving each with its element.

Add styles:

```css
    .live { display: grid; grid-template-columns: minmax(0, 1fr); gap: 24px; align-items: start; }
    .business { display: flex; flex-direction: column; gap: 24px; min-width: 0; }
    .platform { min-width: 0; }
    .why { font-size: 19px; font-weight: 700; margin-bottom: 8px; }
    .graph-and-inspector { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; align-items: start; }
    @media (min-width: 1280px) {
      .live { grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); }
      /* The platform column follows the reader down the page during a rush. */
      .platform { position: sticky; top: 72px; }
    }
    @media (min-width: 1500px) {
      .graph-and-inspector { grid-template-columns: minmax(0, 1.5fr) minmax(300px, 1fr); }
    }
```

In `inspector.ts`, the drawer breakpoint changes from `max-width: 899px` to `max-width: 1279px` so it matches the page's one-column range, and the non-drawer `.inspector` loses its left border when stacked: add `@media (min-width: 1280px) and (max-width: 1499px) { .inspector { border-left: 0; padding: 16px 0 0; border-top: 1px solid var(--line); } }`.

- [ ] **Step 3: The cluster page keeps what explains**

In `cluster-page.ts`, delete the "How these objects connect" card (graph and inspector), remove `Inspector` and `ObjectGraph` from `imports` and their import lines, and delete the `.graph-and-inspector` styles. Add, where the card was:

```html
      <p class="note">
        The objects themselves, live and clickable, are on the simulation page beside the rush.
      </p>
```

- [ ] **Step 4: Build**

Run: `cd console/ui && npx ng build`
Expected: bundle generation complete, no errors.

- [ ] **Step 5: Check the widths in a browser**

Run the backend on a free port (8082/9090 may be held by an old local jar) and the dev server with a matching proxy:

```bash
cd console && CONSOLE_ACCESS_KEY=localdev ./mvnw -q spring-boot:run '-Dspring-boot.run.arguments=--server.port=8083 --management.server.port=9093' &
printf '{ "/api": { "target": "http://localhost:8083", "secure": false } }\n' > /tmp/proxy-8083.json
cd console/ui && npx ng serve --port 4200 --proxy-config /tmp/proxy-8083.json &
```

Open `http://localhost:4200/?inspect=deployment/queue-gate` and check, at each width:

| Width | Expected |
|---|---|
| 1680 | Two columns; graph and inspector side by side; queue-gate outlined red; inspector shows it |
| 1366 | Two columns; inspector below the graph; no sideways page scroll (`document.documentElement.scrollWidth <= innerWidth`) |
| 390 | One column; the inspector is a drawer, open because of `?inspect=`, and closes |
| any | The page opened on the simulation page, not the home page (Review Focus 2) |
| any | Without a sandbox, the left shows the run panel and the right shows the graph (Review Focus 4) |

Then the cluster page: the graph and inspector are gone, the monitoring panel shows **Splunk — Trial ended** and **Dynatrace — Trial ended** only if the local backend sets both reasons. Locally they are unset, so start the backend once more with `SPLUNK_DISABLED_REASON=x DYNATRACE_DISABLED_REASON=y` to see the ended state, and check the header pill reads "Trials ended".

Stop both servers when done.

- [ ] **Step 6: Commit**

```bash
git add console/ui/src/app/app.ts console/ui/src/app/visitor.ts console/ui/src/app/cluster-page.ts console/ui/src/app/inspector.ts
git commit -m "Put the platform beside the rush on one live page, using the full desktop width"
```

---

### Task 4: Deploy and verify

- [ ] **Step 1: Ask the human before merging and pushing**, then:

```bash
git switch main && git merge --ff-only <branch> && git push origin main
gh run watch "$(gh run list --workflow CD --limit 1 --json databaseId -q '.[0].databaseId')"
deploy/scripts/status.sh
```

Expected: CD success; `everything is up`.

- [ ] **Step 2: Check the live console**

```bash
C=https://console-marwanbukhori-dev.apps.rm3.7wse.p1.openshiftapps.com
curl -s $C/api/observability | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["splunk"]["disabled"]); print(d["dynatrace"]["disabled"])'
```

Expected: both lines print their "Trial ended …" reasons.

Open the console in a browser at 1680px and 390px and repeat the Task 3 Step 5 table against the live site, then start a rush and confirm the seat map (left) and the HPA box and pods (right) move in the same view.

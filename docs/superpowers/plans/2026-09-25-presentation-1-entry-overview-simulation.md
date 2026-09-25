# Presentation 1: Demo Key, Overview, and the Banded Simulation Page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Any visitor can get the demo key in one click; the Overview is short (headline, live system diagram, tiles); the simulation page is laid out in full-width bands with the graph full width and the inspector as a panel over it; long lists show 5 and load more; every time is Malaysia time.

**Architecture:** One small backend endpoint (`GET /api/demo-key`, off unless `CONSOLE_SHARE_KEY=true`). Everything else is Angular: a shared `malaysiaTime` formatter, a header button, the Overview trimmed to hero + diagram + tile grid, the visitor page restructured into bands, and a small `LoadMore` helper for lists.

**Tech Stack:** Spring Boot 4.1 + JUnit/MockMvc; Angular standalone components with signals.

**Spec:** [`docs/superpowers/specs/2026-09-25-site-presentation-design.md`](../specs/2026-09-25-site-presentation-design.md) §3, §4, §7 (build order §8 step 1).

## Global Constraints

- The demo key is served only while `CONSOLE_SHARE_KEY=true`; otherwise `GET /api/demo-key` is 404 `{"error":"NOT_FOUND"}`.
- Headline, verbatim: "A virtual queue and booking service, deployed on OpenShift and load-tested live".
- Every time shown uses `Asia/Kuala_Lumpur`, 24-hour, and each section that shows times says "GMT+8" once.
- Lists show the latest 5 and add 5 when the reader scrolls to the end.
- Simulation layout A: bands from 1280px; below 1280px stacked with the inspector as a drawer (as now). No sideways page scroll at 1680, 1366, 390.
- The CI/CD and AI Agent pages, and their nav links and tiles, arrive in plans 2 and 3; this plan's tile grid has Run a simulation, Cluster, Build notes.
- Commits: plain sentences, no AI attribution, no trailers. `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`.

## Review Focus

1. **A keyed visitor clicking an old bookmark without `?key=`.** Expected: the stored key still applies; the button does not show.
2. **The share setting off.** Expected: no button anywhere, the read-only notice says to ask for an invite, and `/api/demo-key` is 404 without echoing anything.
3. **Times near midnight in GMT+8 while the browser is elsewhere.** Expected: the Malaysia time shown, not the browser's.
4. **The inspector panel open while the graph is narrow (1280–1400px).** Expected: the panel covers at most the right 40%, has a close button, and the graph stays usable.
5. **A list with fewer than 5 items, or new items arriving while scrolled down.** Expected: no "load more" sentinel firing forever; new items appear at the top without jumping the reader's position.

---

### Task 1: The demo-key endpoint

**Files:** `console/src/main/java/dev/marwan/console/auth/AccessKey.java` (add `value()`), create `console/src/main/java/dev/marwan/console/web/DemoKeyController.java`, test `console/src/test/java/dev/marwan/console/web/DemoKeyControllerTest.java`, `deploy/base/console/deployment.yaml` (env).

- [ ] **Step 1: Failing test** — `DemoKeyControllerTest` (`@WebMvcTest(DemoKeyController.class)`, same `Properties` bean pattern as `StateControllerTest`, plus an `AccessKey("s3cret-demo-key")` bean):
  - with `@TestPropertySource(properties = "CONSOLE_SHARE_KEY=true")` on a nested/second test class, `GET /api/demo-key` → 200 `{"key":"s3cret-demo-key"}`;
  - without it → 404, body `{"error":"NOT_FOUND"}`, and the body does not contain the key.
  Use two test classes: `DemoKeyControllerTest` (shared on) and `DemoKeyControllerOffTest` (default off).
- [ ] **Step 2: Run, see it fail** (no controller).
- [ ] **Step 3: Implement.** `AccessKey.value()` returns the configured string (or null when unset). `DemoKeyController`:

```java
@RestController
public class DemoKeyController {
    private final AccessKey key;
    private final boolean share;

    public DemoKeyController(AccessKey key, @Value("${CONSOLE_SHARE_KEY:false}") boolean share) {
        this.key = key;
        this.share = share;
    }

    /**
     * The key, handed to anyone, while CONSOLE_SHARE_KEY is on: the demo has to
     * work for whoever opens it. The guards on a run (one per drop, the CPU
     * quota, expiring sandboxes) are what limit a stranger, not the key.
     */
    @GetMapping("/api/demo-key")
    public ResponseEntity<Map<String, String>> demoKey() {
        if (!share || !key.configured()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "NOT_FOUND"));
        }
        return ResponseEntity.ok(Map.of("key", key.value()));
    }
}
```

  In `deploy/base/console/deployment.yaml`, add beside `SPLUNK_DISABLED_REASON`:

```yaml
            # Hands the console key to any visitor through a button, so whoever
            # opens the demo can start a rush. Set to false to make it private.
            - name: CONSOLE_SHARE_KEY
              value: "true"
```

- [ ] **Step 4: Run, see pass; full suite.** **Step 5: Commit** "Let the console hand its key to visitors while sharing is switched on".

---

### Task 2: One time zone everywhere

**Files:** create `console/ui/src/app/time.ts`; modify `charts-strip.ts`, `inspector.ts`, `traffic-log.ts`.

- [ ] **Step 1:** `time.ts`:

```ts
const FORMAT = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kuala_Lumpur', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
});
const SHORT = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kuala_Lumpur', hour: '2-digit', minute: '2-digit', hour12: false
});

/**
 * Every time on the site, in Malaysia time: the audience and the 21:00 drop
 * are there, and a reader abroad should see the same clock as the logs.
 * Accepts ISO strings (nanosecond ones are trimmed to what Date parses),
 * epoch milliseconds, or a Date.
 */
export function malaysiaTime(at: string | number | Date | null | undefined, withSeconds = true): string {
  if (at === null || at === undefined) {
    return '—';
  }
  const d = typeof at === 'string' ? new Date(at.length > 23 && at.endsWith('Z') ? at.slice(0, 23) + 'Z' : at)
    : at instanceof Date ? at : new Date(at);
  return isNaN(d.getTime()) ? '—' : (withSeconds ? FORMAT : SHORT).format(d);
}

export const TIME_ZONE_LABEL = 'GMT+8';
```

- [ ] **Step 2:** Replace: `charts-strip.ts` both `new Date(t * 1000).toLocaleTimeString(...)` → `malaysiaTime(t * 1000)`; the x-axis "now" label stays, and the strip's note gains "Times GMT+8". `inspector.ts` `localTime(at)` and `time(at)` → `malaysiaTime(at)`. `traffic-log.ts` `{{ event.at | date: 'HH:mm:ss' }}` → `{{ time(event.at) }}` with `protected time = malaysiaTime`, and drop `DatePipe`; its subtitle gains "· GMT+8".
- [ ] **Step 3:** `npx ng build` → complete. Check in the browser console: `malaysiaTime('2026-09-25T16:30:00Z')` → `00:30:00` (review focus 3).
- [ ] **Step 4: Commit** "Show every time in Malaysia time".

---

### Task 3: The key button and the header

**Files:** `console/ui/src/app/key.ts`, `app.ts`, `run-panel.ts`.

- [ ] **Step 1:** In `key.ts` add:

```ts
/**
 * Fetches the shared demo key and reloads the page keyed. Null when sharing is
 * off (the server answers 404), so the caller can hide the button.
 */
export async function fetchDemoKey(): Promise<string | null> {
  try {
    const r = await fetch('/api/demo-key');
    if (!r.ok) {
      return null;
    }
    return (await r.json()).key ?? null;
  } catch {
    return null;
  }
}

export function openWithKey(key: string): void {
  const url = new URL(window.location.href);
  url.searchParams.set('key', key);
  window.location.assign(url.toString());
}
```

- [ ] **Step 2:** `app.ts`: a `shareable = signal(false)` set once at start by `fetchDemoKey()` when `!hasConsoleKey()` (keep the key in a private field). In the header, when `!hasConsoleKey() && shareable()`, render `<button class="get-key" (click)="getKey()">Get the demo key</button>` beside the key badge, styled as the red primary action at header size. `getKey()` calls `openWithKey`.
- [ ] **Step 3:** Header alignment: the header `.inner` gets `[class.wide]="surface() === 'visitor'"` and `.inner.wide { max-width: 1680px; }`, matching `main.wide`.
- [ ] **Step 4:** `run-panel.ts`: the read-only notice (lines ~91–94) keeps its first sentence; its last sentence becomes, when shareable, a **Get the demo key** button (reuse `fetchDemoKey`/`openWithKey`), and when not shareable, "Ask for an invite link to start one." Expose shareability to the panel through a small root `DemoKeyService { shareable: Signal<boolean>; open(): void }` in `key.ts` that both `app.ts` and `run-panel.ts` inject, so the key is fetched once.
- [ ] **Step 5:** Build; browser check at 1440 without a key: the button shows; clicking it reloads with `?key=` and the badge reads keyed; with a key stored, no button (review focus 1). **Commit** "Give visitors without the key a button that gets it".

---

### Task 4: The short Overview

**Files:** `console/ui/src/app/public-home.ts`, `architecture-diagram.ts`, `docs-page.ts`, `app.ts` (tile outputs).

- [ ] **Step 1:** `public-home.ts`:
  - headline → the Global Constraints text; eyebrow tag stays "Simulation".
  - hero actions: remove both buttons (the grid replaces them).
  - delete the `toc` nav, the `#pipeline` band and the `#stack` band (keep the `#system` band with `<rb-architecture-diagram />`), and `contents`, `toolCount`, `PipelineDiagram` import if unused.
  - add, after the system band, a **tile grid**: `display:grid; grid-template-columns: repeat(4, 1fr); gap: 16px;` with a **Run a simulation** tile spanning 2 columns (red background, white text, one line: "Start a rush against the live cluster and watch it land") and secondary tiles **Cluster** ("The objects behind it: pods, autoscalers, the CPU budget") and **Build notes** ("How each part was built, and why"). Tiles are `<button>`s emitting `visitor`, `cluster`, `docs`. Below 900px: 2 columns; below 600px: 1.
  - add output `cluster = output<void>()`; wire it in `app.ts`: `<rb-public-home (visitor)=... (docs)=... (cluster)="show('cluster')" />`.
- [ ] **Step 2:** `architecture-diagram.ts`: the two `'—'` returns become `'reading…'`.
- [ ] **Step 3:** `docs-page.ts`: move the "Everything used, and what for" tools list here, as the first section under the page lede (move the `tools` data and its template/styles from `public-home.ts`; keep the `Reveal` directive only if `docs-page` already imports it, otherwise render without it).
- [ ] **Step 4:** Build; browser check at 1440 and 390: the Overview is hero + diagram + tiles, the diagram shows real pod counts (or "reading…" for the first second), tiles navigate. **Commit** "Shorten the Overview to what was built, the system running, and where to go next".

---

### Task 5: The banded simulation page

**Files:** `console/ui/src/app/visitor.ts`, `inspector.ts`, `object-graph.ts`.

- [ ] **Step 1:** `visitor.ts` template becomes, in order:
  1. crumbs;
  2. `@if (sandbox()) { <rb-run-banner /> }` — directly under the crumbs;
  3. `.band-top` grid (`minmax(0,1fr) minmax(0,1.5fr) minmax(0,1.1fr)` from 1280px, one column below): `<rb-run-panel />` · `@if (sandbox()) { <rb-canonical-drop heading="Seats and queue" /> } @else { <div class="card placeholder">Seats appear here once a rush starts.</div> }` · `@if (sandbox()) { <rb-traffic-log /> }`;
  4. `<section class="card">` "The platform, live" with its note, then `<rb-charts-strip />`;
  5. `<section class="card graph-card">` with the object graph note, then a `position: relative` wrapper holding `<rb-object-graph />` and `<rb-inspector />`.
  Remove `<rb-pod-pulse />` from this page (it remains on Cluster; the charts and graph cover pods here). Keep the `.reason` paragraph under the top band when a sandbox exists. Delete `.live`, `.business`, `.platform` sticky rules and the old `.graph-and-inspector` rules.
- [ ] **Step 2:** `charts-strip.ts`: `.strip` grid is 4 columns from 1280px, 2 below, 1 under 600px.
- [ ] **Step 3:** `object-graph.ts`: the SVG loses its `min-width: 640px` cap in favour of `width: 100%` with `min-width: 860px` only below 1280px (its frame already scrolls sideways there).
- [ ] **Step 4:** `inspector.ts`: from 1280px, `.inspector` is `position: absolute; top: 0; right: 0; bottom: 0; width: min(40%, 460px); overflow-y: auto; background: var(--white); border-left: 1px solid var(--line); box-shadow: -8px 0 24px rgba(0,0,0,.08); padding: 16px;` and hidden (`display: none`) when nothing is selected; the close button always shows. Below 1280px the existing drawer rules stand. Remove the 1280–1649 stacked rule.
- [ ] **Step 5:** Build; browser check (backend on 8083/9093 + `ng serve` proxy as in earlier plans): at 1680 and 1366 the banner is under the crumbs, the top band is three columns, charts are one row of four, the graph spans the card width, clicking a box opens the panel over the right side and ✕ closes it (review focus 4); at 390 everything stacks and the drawer works; no sideways scroll. **Commit** "Lay the simulation out in bands, with the graph full width and the inspector over it".

---

### Task 6: Five at a time

**Files:** create `console/ui/src/app/load-more.ts`; modify `traffic-log.ts`, `inspector.ts`.

- [ ] **Step 1:** `load-more.ts` — a directive `rbLoadMore` placed on a sentinel element at the end of a list; it emits `more` when the sentinel enters its scroll container's view (`IntersectionObserver` with `root` = nearest scrollable ancestor, falling back to the viewport), and disconnects on destroy.
- [ ] **Step 2:** `traffic-log.ts`: `shown = signal(5)`; render `feed().slice(0, shown())`; after the list, when `feed().length > shown()`, a sentinel `<li rbLoadMore (more)="shown.update(n => n + 5)">` with text "Loading more…". The list container gets `max-height: 260px; overflow-y: auto`. New events arrive at the top (existing order), so a reader scrolled down keeps their place (review focus 5).
- [ ] **Step 3:** `inspector.ts`: the same for the Events list (`eventsShown`, reset to 5 on selection change) and the Logs view (`logsShown`, showing the **last** `logsShown` lines of `logLines()`; the sentinel sits at the **top** of the log box, since logs read oldest-first, and loading more reveals 5 older lines).
- [ ] **Step 4:** Build; browser check: a busy pod's events show 5, scrolling the list adds 5 at a time and stops at the end; an object with 2 events shows 2 and no sentinel. **Commit** "Show five events, logs and traffic lines at a time, and load more on scroll".

---

### Task 7: Deploy and verify

- [ ] Merge, push, watch CD; `deploy/scripts/status.sh` → everything is up.
- [ ] `curl $C/api/demo-key` → `{"key":…}` (sharing on).
- [ ] Browser, live site, no key stored: Overview shows the new headline, the diagram with pod counts, the tile grid; header shows **Get the demo key**; clicking it lands keyed. Simulation page at 1680 / 1366 / 390 per Task 5 Step 5, times in GMT+8.

import { Component, computed, input, signal } from '@angular/core';
import { Analysis } from './analysis';

interface Fact { id: string; source: string; label: string; value: string }
interface Claim { text: string; facts: string[] }
interface Step { tool: string; why: string; found: string }

/**
 * How the agent works, as one picture first and the detail after.
 *
 * The pipeline graphic carries the selected run's own numbers - how many facts
 * it gathered, how many calls it made over MCP, how many claims survived the
 * validator - so the diagram describes something that happened, not a plan.
 * The loop, the tools, the model and the hand-written example it was designed
 * from sit below, folded, for a reader who wants them.
 */
@Component({
  selector: 'rb-agent-how',
  template: `
    <div class="stack-24">
      <section class="card pad">
        <h2>The pipeline{{ run() ? ', for the selected run' : '' }}</h2>
        <ol class="pipe">
          @for (stage of stages(); track stage.name; let last = $last) {
            <li class="stage" [class.model]="stage.model">
              <span class="stage-n mono">{{ stage.figure }}</span>
              <span class="stage-name">{{ stage.name }}</span>
              <span class="stage-what">{{ stage.what }}</span>
            </li>
            @if (!last) {
              <li class="flow" aria-hidden="true"></li>
            }
          }
        </ol>
        <p class="note">
          Blue stages are the model's; the rest is the console's own code. A report that fails validation twice is
          replaced by one built from the facts alone, so every run is reported.
        </p>
      </section>

      <details class="card pad fold" open>
        <summary><h2>The loop, in detail</h2></summary>
        <ol class="loop">
          @for (box of loop; track box.name; let last = $last) {
            <li class="box" [class.model]="box.model">
              <span class="box-name">{{ box.name }}</span>
              <span class="box-what">{{ box.what }}</span>
            </li>
            @if (!last) {
              <li class="arrow" aria-hidden="true">→</li>
            }
          }
        </ol>
        <p class="note">
          It starts from finished load Jobs that have no report yet, checked every ten seconds, so a
          console restart mid-analysis loses nothing: the next check picks the Job up again. Qwen3 runs
          with thinking off, temperature 0.2, 60 seconds per call and 3 minutes per run; when the budget
          runs out it goes straight to the report.
        </p>
      </details>

      <details class="card pad fold">
        <summary><h2>What it may look at</h2></summary>
        <p class="note">
          The same read-only services the inspector on the simulation page uses, each with bounded
          output. Every result becomes a new fact the report can cite.
        </p>
        <div class="tools">
          @for (t of tools; track t.name) {
            <div class="tool-name mono">{{ t.name }}</div>
            <div class="tool-what">{{ t.what }}</div>
          }
        </div>
      </details>

      <details class="card pad fold">
        <summary><h2>The model</h2></summary>
        <p class="note">
          Qwen3 8B, served free inside the OpenShift Developer Sandbox (<span class="mono">sandbox-shared-models</span>),
          called with a plain HTTP client from the console's Java code. No LangChain and no Python service: a
          run needs one to seven model calls, which does not justify another Deployment on a 3-CPU quota.
          Reaching it from this namespace was verified on 25 September 2026. Granite 3.1 8B is the
          configured alternative.
        </p>
      </details>

      <details class="card pad fold">
        <summary class="report-head">
          <h2>The example this was designed from</h2>
          <span class="badge soft">Example, written by hand</span>
        </summary>
        <p class="note">
          Written by hand from the real rush on 25 September 2026, in the shape the agent will produce.
          Click a fact id to see what the claim rests on.
        </p>

        @for (group of report; track group.title) {
          <h3>{{ group.title }}</h3>
          <ul class="claims">
            @for (claim of group.claims; track claim.text) {
              <li>
                <span>{{ claim.text }}</span>
                @for (id of claim.facts; track id) {
                  <button class="chip mono" [class.on]="openFact() === claim.text + '/' + id" (click)="toggle(claim.text + '/' + id)"
                          [attr.aria-expanded]="openFact() === claim.text + '/' + id">{{ id }}</button>
                }
                @for (id of claim.facts; track id) {
                  @if (openFact() === claim.text + '/' + id) {
                    <div class="fact">
                      <span class="fact-src mono">{{ id }} · {{ fact(id).source }}</span>
                      <span>{{ fact(id).label }}: <b>{{ fact(id).value }}</b></span>
                    </div>
                  }
                }
              </li>
            }
          </ul>
        }

        <h3>Trail</h3>
        <ol class="trail">
          @for (s of trail; track s.tool) {
            <li><span class="mono">{{ s.tool }}</span> because {{ s.why }} → {{ s.found }}</li>
          }
        </ol>
        <p class="note trail-note">
          F6 is not a tool result: a scrape's cost appears in no log, so it was timed by hand while
          diagnosing. The agent's tools would stop at F5, and the report would ask what else was holding
          the pool.
        </p>
        <p class="fixed">
          Fixed since: the slot gauges now read every slot in one query, reused for five seconds, and a
          scrape takes about 0.8 seconds instead of 4.6.
        </p>
      </details>
    </div>
  `,
  styles: `
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    h1 { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; }
    h2 { font-size: 19px; margin: 0 0 10px; }
    h3 { font-size: 15px; margin: 18px 0 8px; }
    .pad { padding: 20px 24px; }
    .badge { font-size: 13px; font-weight: 700; letter-spacing: .02em; padding: 4px 10px; border-radius: 999px;
             background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .runs { display: grid; gap: 2px; }
    .run { display: grid; grid-template-columns: 1.4fr repeat(5, 0.8fr) 1fr; gap: 8px; align-items: center;
           text-align: left; font: inherit; font-size: 14px; background: none; border: 0; border-radius: 4px;
           padding: 8px 10px; color: var(--ink); cursor: pointer; }
    .run.head { cursor: default; font-size: 11px; letter-spacing: .06em; text-transform: uppercase; color: var(--muted); }
    button.run:hover { background: var(--canvas); }
    .run.on { background: var(--canvas); box-shadow: inset 3px 0 0 var(--dhl-red); }
    .run .k { display: none; color: var(--muted); }
    .run .bad { color: var(--chip-bad-fg); font-weight: 700; }
    .pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .pill.model { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .how { font-size: 22px; margin: 16px 0 0; }
    @media (max-width: 699px) {
      .run { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .run.head { display: none; }
      .run .k { display: inline; }
    }
    .badge.live { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .older { margin-top: 12px; }
    .badge.soft { background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); font-weight: 600; }
    .note { font-size: 14px; color: var(--ink-soft); margin: 0 0 8px; text-wrap: pretty; }
    .mono { font-family: var(--mono); }
    .loop { list-style: none; margin: 4px 0 14px; padding: 0; display: flex; flex-wrap: wrap; align-items: stretch; gap: 8px; }
    .box { flex: 1 1 150px; display: flex; flex-direction: column; gap: 4px; padding: 12px 14px;
           border: 1px solid var(--line); border-radius: 6px; background: var(--canvas); min-width: 0; }
    .box.model { border-color: var(--chip-info-fg); background: var(--chip-info-bg); }
    .box-name { font-weight: 700; font-size: 15px; }
    .box-what { font-size: 13px; color: var(--ink-soft); text-wrap: pretty; }
    .arrow { align-self: center; color: var(--muted); font-size: 18px; }
    .tools { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 8px 18px; font-size: 14px; }
    .tool-name { font-size: 13px; }
    .tool-what { color: var(--ink-soft); }
    .report-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
    .report-head h2 { margin: 0; }
    .claims { margin: 0; padding-left: 18px; display: grid; gap: 8px; font-size: 14px; }
    .chip { margin-left: 6px; font-size: 12px; font-weight: 400; border: 1px solid var(--line); background: var(--white); color: var(--ink);
            border-radius: 6px; padding: 1px 7px; cursor: pointer; }
    .chip.on { border-color: var(--ink); background: var(--ink); color: var(--white); }
    .fact { display: flex; flex-direction: column; gap: 2px; margin-top: 6px; padding: 8px 12px; border-left: 3px solid var(--ink);
            background: var(--canvas); font-size: 13px; overflow-wrap: anywhere; }
    .fact-src { font-size: 11px; color: var(--muted); }
    .trail { margin: 0; padding-left: 20px; display: grid; gap: 6px; font-size: 14px; color: var(--ink-soft); }
    .trail .mono { color: var(--ink); font-size: 13px; overflow-wrap: anywhere; }
    .trail-note { margin-top: 12px; }
    .fixed { margin: 16px 0 0; font-size: 14px; padding: 10px 14px; border-radius: 4px;
             background: var(--chip-ok-bg); color: var(--ink); }
    @media (max-width: 599px) {
      .pad { padding: 16px; }
      .arrow { transform: rotate(90deg); flex-basis: 100%; text-align: center; }
      .tools { grid-template-columns: minmax(0, 1fr); gap: 2px; }
      .tool-what { margin-bottom: 8px; }
    }

    .fold > summary { cursor: pointer; list-style: none; display: flex; align-items: center; gap: 10px; }
    .fold > summary::-webkit-details-marker { display: none; }
    .fold > summary h2 { margin: 0; }
    .fold > summary::after { content: '▸'; color: var(--muted); margin-left: auto; }
    .fold[open] > summary::after { content: '▾'; }
    .fold[open] > summary { margin-bottom: 12px; }
    .pipe { list-style: none; margin: 8px 0 12px; padding: 0; display: flex; flex-wrap: wrap; align-items: stretch; gap: 0; }
    .stage { flex: 1 1 140px; display: flex; flex-direction: column; gap: 4px; padding: 14px; min-width: 0;
             border: 1px solid var(--line); border-radius: 8px; background: var(--canvas); }
    .stage.model { background: var(--chip-info-bg); border-color: var(--chip-info-fg); }
    .stage-n { font-size: 26px; font-weight: 700; line-height: 1; }
    .stage-name { font-weight: 700; font-size: 15px; }
    .stage-what { font-size: 12px; color: var(--ink-soft); text-wrap: pretty; }
    .flow { flex: 0 0 22px; align-self: center; height: 2px; background: var(--muted); position: relative; }
    .flow::after { content: ''; position: absolute; right: -1px; top: -4px; border: 5px solid transparent;
                   border-left-color: var(--muted); }
    @media (max-width: 699px) {
      .pipe { flex-direction: column; }
      .flow { flex-basis: 16px; width: 2px; height: 16px; align-self: center; }
      .flow::after { right: -4px; top: auto; bottom: -6px; border-left-color: transparent; border-top-color: var(--muted); }
    }
  `
})
export class AgentHow {
  /** The run the numbers describe; the latest by default. */
  readonly run = input<Analysis | null>(null);

  protected readonly stages = computed(() => {
    const a = this.run();
    const baseline = a ? a.facts.filter(f => !f.source.startsWith('tool:')).length : null;
    const calls = a ? a.trail.length : null;
    const viaMcp = a ? a.trail.filter(s => s.via === 'mcp').length : 0;
    const claims = a ? Object.values(a.report).reduce((n, list) => n + (list?.length ?? 0), 0) : null;
    const show = (n: number | null, otherwise: string) => (n === null ? otherwise : String(n));
    return [
      { name: 'Facts', figure: show(baseline, '~20'), model: false,
        what: 'k6 outcomes, Prometheus peaks, events, pool timeouts, the oversold invariant' },
      { name: 'Investigate', figure: show(calls, '≤5'), model: true,
        what: !a ? 'read-only tool calls over MCP'
          : a.trail.some(s => s.via) ? `tool calls, ${viaMcp} of them over MCP` : 'tool calls (analysed before MCP)' },
      { name: 'Report', figure: show(claims, '6'), model: true,
        what: a ? 'claims, each citing its facts' : 'sections, each claim citing facts' },
      { name: 'Validate', figure: a ? (a.source === 'model' ? '✓' : '↺') : '✓', model: false,
        what: a ? (a.source === 'model' ? 'every number found in a cited fact' : 'rejected; rebuilt from the facts') : 'every number must appear in a cited fact' },
      { name: 'Store', figure: '12', model: false, what: 'runs kept in one ConfigMap' }
    ];
  });

  /** Which chip is open, keyed by claim and fact, so one click opens one place. */
  protected readonly openFact = signal<string | null>(null);

  protected readonly loop = [
    { name: 'Facts', what: 'k6 results, Prometheus peaks, warning events, pool timeouts, the oversold invariant', model: false },
    { name: 'Investigate', what: 'the model may call up to 5 read-only tools; each result becomes a fact', model: true },
    { name: 'Report', what: 'summary, customers, capacity, errors, look at: every item cites fact ids', model: true },
    { name: 'Validate', what: 'ids exist and every number appears in a cited fact; one retry, then a report from the facts alone', model: false },
    { name: 'Store', what: 'facts, trail and report in one ConfigMap, keyed by run; the newest 12 kept', model: false }
  ];

  protected readonly tools = [
    { name: 'pod_logs(pod, level, contains?)', what: 'Up to 40 matching lines from the run window, phone numbers masked' },
    { name: 'metric(chart, pod?)', what: 'One of the four charts over the run window, 30 points' },
    { name: 'events(kind, name)', what: 'Kubernetes events for one object in the window' },
    { name: 'pod_status(pod)', what: 'Phase, readiness, restarts, node, owning ReplicaSet' },
    { name: 'endpoints(service)', what: 'The pods ready behind a Service now' }
  ];

  private readonly facts: Fact[] = [
    { id: 'F1', source: 'k6', label: 'Bookings attempted, clean, not clean', value: '200 attempted, 196 clean, 4 not clean (HTTP 503)' },
    { id: 'F2', source: 'invariant', label: 'Seats oversold', value: '0' },
    { id: 'F3', source: 'Prometheus', label: 'Peak DB pool in use, booking-service pod A', value: '5 of 5' },
    { id: 'F4', source: 'Prometheus', label: 'Peak DB pool in use, booking-service pod B', value: '0 of 5' },
    { id: 'F5', source: 'tool: pod_logs', label: 'WARN lines on pod A during the rush', value: 'Hikari pool timeouts: connection not available within 2 s' },
    { id: 'F6', source: 'measured by hand, commit c3c1e49', label: 'Cost of one /actuator/prometheus scrape', value: 'about 4.6 s; 4 gauges × 5 slots = 20 queries on the booking pool' }
  ];

  protected readonly report: { title: string; claims: Claim[] }[] = [
    { title: 'Went well', claims: [
      { text: 'No seat was oversold; the invariant held at 0.', facts: ['F2'] },
      { text: '196 of 200 bookings completed cleanly.', facts: ['F1'] }
    ] },
    { title: 'Caught', claims: [
      { text: '4 bookings failed with 503 while pod A\'s pool sat at 5 of 5 and pod B\'s at 0 of 5.', facts: ['F1', 'F3', 'F4'] },
      { text: 'Metrics scrapes competed with bookings for pod A\'s pool: one scrape ran 20 queries and took about 4.6 s.', facts: ['F5', 'F6'] }
    ] },
    { title: 'Look at', claims: [
      { text: 'Make a scrape cost one query rather than 20, so it cannot hold the pool.', facts: ['F6'] },
      { text: 'Pod B stayed at 0 of 5 while pod A was full: check how requests spread across the two pods.', facts: ['F3', 'F4'] }
    ] }
  ];

  protected readonly trail: Step[] = [
    { tool: 'pod_logs(pod A, WARN, "Connection is not available")', why: 'the 503s came while pod A\'s pool was full',
      found: 'F5: requests timed out waiting 2 s for a connection' }

  ];

  protected fact(id: string): Fact {
    return this.facts.find(f => f.id === id)!;
  }

  protected toggle(id: string): void {
    this.openFact.update(open => (open === id ? null : id));
  }
}

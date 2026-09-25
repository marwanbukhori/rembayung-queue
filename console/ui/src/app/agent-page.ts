import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { Analysis, AnalysisService, AnalysisSummary } from './analysis';
import { AnalysisReport } from './analysis-report';

interface Fact { id: string; source: string; label: string; value: string }
interface Claim { text: string; facts: string[] }
interface Step { tool: string; why: string; found: string }

/**
 * The run agent, described before it exists.
 *
 * Marked in progress at the top rather than dressed up as a feature: the design
 * is worth showing on its own, and a page that pretended the agent was live
 * would be found out the first time someone ran a rush and looked for its
 * report. The example report is written by hand from a real rush, and says so
 * next to it, so the one thing on this page that looks like output is labelled
 * as not being output.
 *
 * Every claim carries the ids of the facts it rests on, and a chip opens the
 * fact. That is the agent's whole discipline: a number the facts do not contain
 * cannot reach the report.
 */
@Component({
  selector: 'rb-agent-page',
  imports: [AnalysisReport],
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">AI Agent</span>
      </div>
      <div>
        <h1>AI Agent
          @if (live()) {
            <span class="badge live">Live</span>
          } @else {
            <span class="badge">In progress</span>
          }
        </h1>
        <p class="lede">
          After every rush, an agent inside the console gathers the facts, investigates with up to
          five read-only tool calls, and writes a short report on what went well, what it caught, and what
          to look at. Every number in the report must come from a fact it cites; when the model cannot
          manage that, the run still gets a plain report built from the facts alone.
          @if (latest()) {
            It is running: below is the report on the latest rush, then how it works.
          } @else {
            Nothing has been analysed yet, so this page shows the design and a hand-written example;
            start a rush and its report appears here about a minute after it ends.
          }
        </p>
      </div>

      @if (latest(); as a) {
        <section class="card pad">
          <div class="report-head">
            <h2>Latest real report</h2>
            <span class="badge soft mono">{{ a.job }}</span>
          </div>
          <rb-analysis-report [analysis]="a" />
          @if (runs().length > 1) {
            <p class="note older">
              {{ runs().length - 1 }} earlier {{ runs().length === 2 ? 'run is' : 'runs are' }} kept; open any
              load run in the simulation page's inspector to read its report.
            </p>
          }
        </section>
      }

      <section class="card pad">
        <h2>The loop</h2>
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
      </section>

      <section class="card pad">
        <h2>What it may look at</h2>
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
      </section>

      <section class="card pad">
        <h2>The model</h2>
        <p class="note">
          Qwen3 8B, served free inside the OpenShift Developer Sandbox (<span class="mono">sandbox-shared-models</span>),
          called with a plain HTTP client from the console's Java code. No LangChain and no Python service: a
          run needs one to seven model calls, which does not justify another Deployment on a 3-CPU quota.
          Reaching it from this namespace was verified on 25 September 2026. Granite 3.1 8B is the
          configured alternative.
        </p>
      </section>

      <section class="card pad">
        <div class="report-head">
          <h2>{{ latest() ? 'The example this was designed from' : 'An example report' }}</h2>
          <span class="badge soft">Example, written by hand</span>
        </div>
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
      </section>
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
  `
})
export class AgentPage implements OnInit {
  readonly home = output<void>();

  private readonly analyses = inject(AnalysisService);
  protected readonly runs = signal<AnalysisSummary[]>([]);
  protected readonly latest = signal<Analysis | null>(null);
  /** Live once the model itself has written a report that passed validation. */
  protected readonly live = computed(() => this.runs().some(r => r.source === 'model'));

  ngOnInit(): void {
    this.analyses.list().subscribe({
      next: runs => {
        this.runs.set(runs);
        if (runs.length) {
          this.analyses.get(runs[0].job).subscribe({ next: a => this.latest.set(a), error: () => {} });
        }
      },
      error: () => {}
    });
  }

  /** Which chip is open, keyed by claim and fact, so one click opens one place. */
  protected readonly openFact = signal<string | null>(null);

  protected readonly loop = [
    { name: 'Facts', what: 'k6 results, Prometheus peaks, warning events, pool timeouts, the oversold invariant', model: false },
    { name: 'Investigate', what: 'the model may call up to 5 read-only tools; each result becomes a fact', model: true },
    { name: 'Report', what: 'went well, caught, look at: every item cites fact ids', model: true },
    { name: 'Validate', what: 'ids exist and every number appears in a cited fact; one retry, then a report from the facts alone', model: false },
    { name: 'Store', what: 'facts, trail and report in a ConfigMap per run; the newest 20 kept', model: false }
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

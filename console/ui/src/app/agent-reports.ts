import { Component, computed, input, output } from '@angular/core';
import { Analysis, AnalysisSummary } from './analysis';
import { AnalysisReport } from './analysis-report';
import { TIME_ZONE_LABEL, malaysiaTime } from './time';

/**
 * The agent's work at a glance: four numbers across every kept run, the runs
 * down the left, and the chosen run's report beside them - the layout of an
 * operations dashboard rather than a document, because a reader comes here to
 * compare runs and open one.
 */
@Component({
  selector: 'rb-agent-reports',
  imports: [AnalysisReport],
  template: `
    <div class="kpis">
      <div class="kpi"><span class="kpi-n">{{ runs().length }}</span><span class="kpi-l">runs analysed, last {{ runs().length }} kept</span></div>
      <div class="kpi"><span class="kpi-n">{{ modelShare() }}</span><span class="kpi-l">written by the model; the rest from the facts alone</span></div>
      <div class="kpi"><span class="kpi-n">{{ typical() }}</span><span class="kpi-l">typical analysis time</span></div>
      <div class="kpi" [class.good]="oversoldTotal() === 0" [class.bad]="oversoldTotal() > 0">
        <span class="kpi-n">{{ oversoldTotal() === 0 ? '✓ 0' : oversoldTotal() }}</span><span class="kpi-l">seats oversold, across every run</span></div>
    </div>

    <div class="panes">
      <aside class="card list" aria-label="Analysed runs">
        <div class="list-head">Runs · {{ zone }}</div>
        @for (r of runs(); track r.key) {
          <button class="run" [class.on]="r.key === selected()" (click)="select.emit(r.key)"
                  [attr.aria-current]="r.key === selected() ? 'true' : null">
            <span class="run-top">
              <span class="mono when">{{ when(r.end) }}</span>
              <span [class]="'pill ' + r.source">{{ r.source === 'model' ? 'model' : 'facts' }}</span>
            </span>
            <span class="run-mid">
              <b>{{ r.booked ?? '—' }}</b> of {{ r.customers ?? '—' }} booked
              @if (r.waves === 2) { <span class="waves">2 waves</span> }
            </span>
            <span class="bar" aria-hidden="true">
              <span [style.width.%]="share(r)"></span>
            </span>
            <span class="run-bottom" [class.bad]="(r.oversold ?? 0) > 0">
              {{ r.seats ?? '—' }} seats · oversold {{ r.oversold ?? '—' }}
            </span>
          </button>
        }
      </aside>
      <section class="card report">
        @if (latest(); as a) {
          <rb-analysis-report [analysis]="a" />
        } @else {
          <p class="quiet">Choose a run.</p>
        }
      </section>
    </div>
  `,
  styles: `
    :host { display: block; }
    .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
    .kpi { display: flex; flex-direction: column; gap: 4px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 8px;
           background: var(--white); }
    .kpi-n { font-size: 28px; font-weight: 700; line-height: 1; font-family: var(--mono); }
    .kpi-l { font-size: 12px; color: var(--ink-soft); }
    .kpi.good .kpi-n { color: var(--chip-ok-fg); }
    .kpi.bad .kpi-n { color: var(--chip-bad-fg); }
    .panes { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 16px; align-items: start; }
    .list { padding: 8px; display: grid; gap: 4px; max-height: 1100px; overflow-y: auto; position: sticky; top: 120px; }
    .list-head { font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); padding: 6px 8px; }
    .run { display: grid; gap: 6px; text-align: left; font: inherit; background: none; border: 1px solid transparent;
           border-radius: 6px; padding: 10px; cursor: pointer; color: var(--ink); }
    .run:hover { background: var(--canvas); }
    .run.on { background: var(--canvas); border-color: var(--line); box-shadow: inset 3px 0 0 var(--dhl-red); }
    .run-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .when { font-size: 13px; }
    .run-mid { font-size: 14px; }
    .waves { margin-left: 6px; font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--chip-info-bg); color: var(--chip-info-fg); }
    .bar { height: 6px; background: var(--line); border-radius: 3px; overflow: hidden; }
    .bar span { display: block; height: 100%; background: var(--ink); min-width: 2px; }
    .run-bottom { font-size: 12px; color: var(--ink-soft); }
    .run-bottom.bad { color: var(--chip-bad-fg); font-weight: 700; }
    .pill { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .pill.model { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .report { padding: 20px 24px; min-width: 0; }
    .quiet { color: var(--muted); }
    .mono { font-family: var(--mono); }
    @media (max-width: 1099px) {
      .panes { grid-template-columns: minmax(0, 1fr); }
      .list { position: static; max-height: 360px; }
    }
    @media (max-width: 699px) {
      .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .report { padding: 16px; }
    }
  `
})
export class AgentReports {
  readonly runs = input.required<AnalysisSummary[]>();
  readonly latest = input<Analysis | null>(null);
  readonly selected = input<string | null>(null);
  readonly select = output<string>();

  protected readonly zone = TIME_ZONE_LABEL;
  protected readonly modelShare = computed(() => {
    const r = this.runs();
    return r.length ? `${r.filter(x => x.source === 'model').length} of ${r.length}` : '—';
  });
  protected readonly typical = computed(() => {
    const ms = this.runs().map(r => r.millis ?? 0).filter(m => m > 0).sort((a, b) => a - b);
    return ms.length ? `${Math.round(ms[Math.floor(ms.length / 2)] / 1000)} s` : '—';
  });
  protected readonly oversoldTotal = computed(() => this.runs().reduce((n, r) => n + (r.oversold ?? 0), 0));

  protected share(r: AnalysisSummary): number {
    return r.customers ? Math.min(100, ((r.booked ?? 0) / r.customers) * 100) : 0;
  }

  protected when(at: string): string {
    return malaysiaTime(at, false) + ', ' + new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Kuala_Lumpur', day: 'numeric', month: 'short'
    }).format(new Date(at));
  }
}

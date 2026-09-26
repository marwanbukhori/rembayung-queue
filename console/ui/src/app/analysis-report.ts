import { Component, computed, input, signal } from '@angular/core';
import { AgentClaim, AgentFact, Analysis } from './analysis';
import { Funnel } from './funnel';
import { TIME_ZONE_LABEL, malaysiaTime } from './time';

interface Section { key: string; title: string; icon: string; claims: AgentClaim[] }
interface Stat { label: string; value: string; note: string; id: string }
interface Pair { label: string; one: number; two: number; oneText: string; twoText: string; better: 'lower' | 'higher' }

/**
 * One run's report, read top-down like an incident summary: the verdict, the
 * customer funnel, the few numbers that explain it, the comparison for a
 * two-wave run, then the sections and the trail of what the agent asked.
 *
 * Everything drawn - the funnel, the stat cards, the comparison bars - is read
 * from the run's facts, never from the model's prose, so the pictures are right
 * even when a sentence is not. Fact chips beside each claim open the fact the
 * claim rests on.
 */
@Component({
  selector: 'rb-analysis-report',
  imports: [Funnel],
  template: `
    @let a = analysis();
    <header class="verdict" [class.fallback]="a.source === 'fallback'">
      <div class="verdict-top">
        <span class="mono job">{{ a.job }}</span>
        <span class="who">{{ a.source === 'model' ? 'Written by ' + a.model : 'Built from the facts alone' }}</span>
        <span class="mono when">{{ time(a.analysedAt) }} {{ zone }} · {{ seconds(a.millis) }}</span>
      </div>
      <p class="headline">{{ headline() }}</p>
      <div class="checks">
        <span class="check" [class.ok]="oversold() === '0'" [class.bad]="oversold() !== '0' && oversold() !== null">
          {{ oversold() === '0' ? '✓ No seat oversold' : oversold() === null ? 'Oversold: not read' : '✕ ' + oversold() + ' oversold' }}
        </span>
        @if (a.source === 'fallback' && a.note) { <span class="check warn">Model report not used: {{ a.note }}</span> }
        @if (viaMcp()) { <span class="check info">{{ viaMcp() }} tool calls via MCP</span> }
      </div>
    </header>

    @if (twoWaves()) {
      <div class="two">
        <div><h4>Wave 1</h4><rb-funnel [facts]="a.facts" prefix="Wave 1 · " /></div>
        <div><h4>Wave 2</h4><rb-funnel [facts]="a.facts" prefix="Wave 2 · " /></div>
      </div>
    } @else {
      <rb-funnel [facts]="a.facts" />
    }

    @if (stats().length) {
      <div class="stats">
        @for (s of stats(); track s.label) {
          <div class="stat">
            <span class="stat-v mono">{{ s.value }}</span>
            <span class="stat-l">{{ s.label }}</span>
            <span class="stat-n">{{ s.note }} <span class="fid mono">{{ s.id }}</span></span>
          </div>
        }
      </div>
    }

    @if (pairs().length) {
      <section class="block">
        <h4>Before and after scaling</h4>
        <div class="pairs">
          @for (p of pairs(); track p.label) {
            <div class="pair">
              <span class="pair-l">{{ p.label }}</span>
              <span class="pair-row"><span class="w mono">W1</span>
                <span class="track"><span class="fill one" [style.width.%]="width(p, p.one)"></span></span>
                <span class="mono v">{{ p.oneText }}</span></span>
              <span class="pair-row"><span class="w mono">W2</span>
                <span class="track"><span class="fill two" [class.won]="won(p)" [style.width.%]="width(p, p.two)"></span></span>
                <span class="mono v">{{ p.twoText }}</span></span>
            </div>
          }
        </div>
      </section>
    }

    <div class="sections">
      @for (s of sections(); track s.key) {
        <section class="sec" [class]="'sec ' + s.key">
          <h4><span class="icon" aria-hidden="true">{{ s.icon }}</span>{{ s.title }}</h4>
          <ul>
            @for (claim of s.claims; track $index; let c = $index) {
              <li>
                <span>{{ claim.text }}</span>
                @for (id of claim.facts; track id) {
                  <button class="chip mono" [class.on]="open() === s.key + c + id" [attr.aria-expanded]="open() === s.key + c + id"
                          (click)="toggle(s.key + c + id)">{{ id }}</button>
                }
                @for (id of claim.facts; track id) {
                  @if (open() === s.key + c + id) {
                    @if (fact(id); as f) {
                      <div class="fact"><span class="src mono">{{ f.id }} · {{ f.source }}</span>
                        <span>{{ f.label }}: <b>{{ f.value }}</b></span></div>
                    }
                  }
                }
              </li>
            }
          </ul>
        </section>
      }
    </div>

    <section class="block">
      <h4>What the agent asked</h4>
      @if (a.trail.length) {
        <ol class="timeline">
          @for (s of a.trail; track $index; let i = $index) {
            <li>
              <span class="dot mono">{{ i + 1 }}</span>
              <div class="step">
                <span class="mono tool">{{ s.tool }}</span>
                @if (s.via) { <span class="via" [class.local]="s.via !== 'mcp'">{{ s.via === 'mcp' ? 'via MCP' : 'in-process' }}</span> }
                <span class="fid mono">→ {{ s.factId }}</span>
                @if (s.why) { <span class="why">{{ s.why }}</span> }
              </div>
            </li>
          }
        </ol>
      } @else {
        <p class="quiet">It asked nothing beyond the baseline facts.</p>
      }
    </section>

    <details class="all">
      <summary>All {{ a.facts.length }} facts</summary>
      <dl>
        @for (f of a.facts; track f.id) {
          <dt class="mono">{{ f.id }}</dt>
          <dd><span class="quiet">{{ f.source }} · </span>{{ f.label }}: {{ f.value }}</dd>
        }
      </dl>
    </details>
  `,
  styles: `
    :host { display: grid; gap: 16px; font-size: 14px; }
    .mono { font-family: var(--mono); }
    .verdict { border-radius: 10px; padding: 16px 18px; background: var(--chip-ok-bg); border-left: 5px solid var(--chip-ok-fg); }
    .verdict.fallback { background: var(--chip-warn-bg); border-left-color: var(--chip-warn-fg); }
    .verdict-top { display: flex; flex-wrap: wrap; gap: 4px 14px; align-items: baseline; font-size: 12px; color: var(--ink-soft); }
    .job { font-weight: 700; color: var(--ink); }
    .when { margin-left: auto; }
    .headline { margin: 8px 0 10px; font-size: 18px; font-weight: 700; line-height: 1.35; text-wrap: pretty; }
    .checks { display: flex; flex-wrap: wrap; gap: 6px; }
    .check { font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 999px; background: var(--white); color: var(--ink-soft); }
    .check.ok { color: var(--chip-ok-fg); }
    .check.bad { color: var(--chip-bad-fg); }
    .check.warn { color: var(--chip-warn-fg); font-weight: 400; }
    .check.info { color: var(--chip-info-fg); }
    .two { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
    h4 { font-size: 14px; margin: 0 0 8px; display: flex; align-items: center; gap: 8px; }
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 10px; }
    .stat { display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 8px; background: var(--canvas); }
    .stat-v { font-size: 22px; font-weight: 700; }
    .stat-l { font-size: 13px; font-weight: 700; }
    .stat-n { font-size: 12px; color: var(--ink-soft); }
    .fid { font-size: 11px; color: var(--muted); }
    .block { border-top: 1px solid var(--line); padding-top: 14px; }
    .pairs { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 14px 24px; }
    .pair { display: grid; gap: 4px; }
    .pair-l { font-size: 13px; font-weight: 700; }
    .pair-row { display: grid; grid-template-columns: 26px minmax(0, 1fr) max-content; align-items: center; gap: 8px; }
    .w { font-size: 11px; color: var(--muted); }
    .track { height: 10px; background: var(--line); border-radius: 5px; overflow: hidden; }
    .fill { display: block; height: 100%; min-width: 2px; border-radius: 5px; }
    .fill.one { background: var(--muted); }
    .fill.two { background: var(--ink); }
    .fill.two.won { background: var(--chip-ok-fg); }
    .v { font-size: 12px; text-align: right; }
    .sections { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; }
    .sec { border: 1px solid var(--line); border-radius: 8px; padding: 12px 14px; background: var(--white); }
    .sec.summary, .sec.customers { grid-column: 1 / -1; }
    .sec.errors { border-left: 4px solid var(--chip-bad-fg); }
    .sec.lookAt { border-left: 4px solid var(--chip-info-fg); }
    .sec.beforeAfter { border-left: 4px solid var(--chip-ok-fg); grid-column: 1 / -1; }
    .icon { width: 22px; height: 22px; display: inline-grid; place-items: center; border-radius: 6px; background: var(--canvas); font-size: 13px; }
    ul { margin: 0; padding-left: 18px; display: grid; gap: 6px; }
    .chip { margin-left: 5px; font-size: 11px; font-weight: 400; border: 1px solid var(--line); background: var(--white);
            color: var(--ink); border-radius: 6px; padding: 0 6px; cursor: pointer; }
    .chip.on { background: var(--ink); color: var(--white); border-color: var(--ink); }
    .fact { display: flex; flex-direction: column; gap: 2px; margin-top: 5px; padding: 6px 10px; border-left: 3px solid var(--ink);
            background: var(--canvas); font-size: 13px; overflow-wrap: anywhere; }
    .fact b { white-space: pre-wrap; }
    .src { font-size: 11px; color: var(--muted); }
    .timeline { list-style: none; margin: 0; padding: 0; display: grid; gap: 0; }
    .timeline li { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; position: relative; padding-bottom: 12px; }
    .timeline li:not(:last-child)::before { content: ''; position: absolute; left: 13px; top: 26px; bottom: 0; width: 2px; background: var(--line); }
    .dot { width: 26px; height: 26px; border-radius: 50%; display: grid; place-items: center; background: var(--ink); color: var(--white); font-size: 12px; }
    .step { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 8px; min-width: 0; }
    .tool { font-weight: 700; font-size: 13px; }
    .via { font-size: 11px; padding: 0 7px; border-radius: 999px; background: var(--chip-info-bg); color: var(--chip-info-fg); }
    .via.local { background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .why { flex-basis: 100%; color: var(--ink-soft); font-size: 13px; overflow-wrap: anywhere; }
    .quiet { color: var(--muted); font-size: 13px; margin: 0; }
    .all summary { cursor: pointer; color: var(--muted); font-size: 13px; }
    .all dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 10px; margin: 8px 0 0; font-size: 12px; }
    .all dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
    @media (max-width: 699px) {
      .two { grid-template-columns: minmax(0, 1fr); }
      .when { margin-left: 0; }
      .headline { font-size: 16px; }
    }
  `
})
export class AnalysisReport {
  readonly analysis = input.required<Analysis>();

  protected readonly zone = TIME_ZONE_LABEL;
  protected readonly open = signal<string | null>(null);

  private value(label: string): string | null {
    return this.analysis().facts.find(f => f.label === label)?.value ?? null;
  }

  private factOf(label: string): AgentFact | undefined {
    return this.analysis().facts.find(f => f.label === label);
  }

  protected readonly twoWaves = computed(() => this.analysis().facts.some(f => f.label === 'Wave 2 · Arrived'));
  protected readonly oversold = computed(() => this.value('Seats oversold'));
  protected readonly viaMcp = computed(() => this.analysis().trail.filter(s => s.via === 'mcp').length);

  /** The report's first summary line, or what the old reports led with. */
  protected readonly headline = computed(() => {
    const r = this.analysis().report;
    return r.summary?.[0]?.text ?? r.wentWell?.[0]?.text ?? r.caught?.[0]?.text ?? 'Report';
  });

  protected readonly sections = computed<Section[]>(() => {
    const r = this.analysis().report;
    const all: Section[] = [
      { key: 'beforeAfter', title: 'Before and after', icon: '⇄', claims: r.beforeAfter ?? [] },
      { key: 'summary', title: 'Summary', icon: '★', claims: (r.summary ?? []).slice(1) },
      { key: 'customers', title: 'Where the customers went', icon: '⤳', claims: r.customers ?? [] },
      { key: 'capacity', title: 'Capacity and scaling', icon: '▤', claims: r.capacity ?? [] },
      { key: 'errors', title: 'Errors', icon: '!', claims: r.errors ?? [] },
      { key: 'lookAt', title: 'Look at next', icon: '→', claims: r.lookAt ?? [] },
      { key: 'wentWell', title: 'Went well', icon: '✓', claims: r.wentWell ?? [] },
      { key: 'caught', title: 'Caught', icon: '!', claims: r.caught ?? [] }
    ];
    return all.filter(s => s.claims.length);
  });

  /** The database's limit on the queue, as the stat cards Task seat-time added. */
  protected readonly stats = computed<Stat[]>(() => {
    const out: Stat[] = [];
    const add = (label: string, fact: string, note: string, suffix = '') => {
      const f = this.factOf(fact);
      if (f) {
        out.push({ label, value: f.value + suffix, note, id: f.id });
      }
    };
    add('Time to seat everyone', 'Time to seat every customer at that rate', 'at the rate bookings committed');
    add('Commit rate', 'Bookings committed per second (peak)', 'bookings a second, at best', '/s');
    add('Connections', 'Booking connections available', 'database connections for bookings');
    add('Pool rounds', 'Rounds of the connection pool to serve every customer', 'rounds to serve every customer');
    return out;
  });

  /** Wave 1 against wave 2 on the measures the before-and-after compares. */
  protected readonly pairs = computed<Pair[]>(() => {
    if (!this.twoWaves()) {
      return [];
    }
    const num = (v: string | null) => (v === null ? NaN : Number(v.split(/[ /]/)[0]));
    const pair = (label: string, suffix: string, better: 'lower' | 'higher'): Pair | null => {
      const a = this.value('Wave 1 · ' + suffix);
      const b = this.value('Wave 2 · ' + suffix);
      if (a === null || b === null || isNaN(num(a)) || isNaN(num(b))) {
        return null;
      }
      return { label, one: num(a), two: num(b), oneText: a, twoText: b, better };
    };
    const hpa = this.analysis().facts.find(f => f.label.startsWith('Wave 1 · Ready pods at start, queue-gate'));
    return [
      hpa ? pair('queue-gate pods at the wave\'s start', 'Ready pods at start, queue-gate', 'higher') : null,
      pair('booking-service pods at the wave\'s start', 'Ready pods at start, booking-service', 'higher'),
      pair('Latency p95 / max', 'Latency p95 / max', 'lower'),
      pair('Turned away (503)', 'Overloaded (503)', 'lower'),
      pair('Booked', 'Booked', 'higher')
    ].filter((p): p is Pair => p !== null);
  });

  protected width(p: Pair, v: number): number {
    const max = Math.max(p.one, p.two, 1);
    return (v / max) * 100;
  }

  protected won(p: Pair): boolean {
    return p.better === 'lower' ? p.two < p.one : p.two > p.one;
  }

  protected fact(id: string): AgentFact | undefined {
    return this.analysis().facts.find(f => f.id === id);
  }

  protected toggle(key: string): void {
    this.open.update(o => (o === key ? null : key));
  }

  protected time(at: string): string {
    return malaysiaTime(at, false);
  }

  protected seconds(ms: number): string {
    return `${Math.max(1, Math.round(ms / 1000))} s`;
  }
}

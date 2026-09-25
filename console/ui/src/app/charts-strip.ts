import { Component, computed, inject, signal } from '@angular/core';
import { CHARTS, MetricsService } from './metrics.service';
import { ChartData, ChartKey } from './state';

/** The plot's frame, in viewBox units: room on the right for end-of-line labels. */
const W = 320;
const H = 120;
const L = 30;
const R = 50;
const T = 8;
const B = 18;
const PLOT_W = W - L - R;
const PLOT_H = H - T - B;
const WINDOW_S = 15 * 60;
/** A gap wider than two scrape steps is a pod that came or went: the line breaks there. */
const GAP_S = 30;

const TITLES: Record<ChartKey, string> = {
  requests: 'Requests / s',
  latency: 'Latency p95',
  pool: 'DB pool in use',
  replicas: 'Replicas'
};
/**
 * Colour follows the series, never its rank: a 4xx line appearing mid-rush
 * must not repaint 5xx. Pods have no fixed names, so they keep the slot they
 * were first given for as long as they exist (see ChartsStrip.slot).
 */
const FIXED_SLOTS: Partial<Record<ChartKey, Record<string, number>>> = {
  requests: { '2xx': 1, '4xx': 2, '5xx': 3, '1xx': 4, '3xx': 4 },
  latency: { 'queue-gate': 1, 'booking-service': 2 },
  replicas: { 'queue-gate': 1, 'booking-service': 2 }
};

const SOURCES: Record<ChartKey, string> = {
  requests: 'queue-gate, by status',
  latency: 'queue-gate and booking-service, ms',
  pool: 'per booking-service pod, of 5',
  replicas: 'per autoscaler'
};

interface Line { label: string; slot: number; segments: string[]; last: { x: number; y: number; v: number } | null; }
interface EndLabel { label: string; slot: number; y: number; text: string; }
interface Plot {
  key: ChartKey;
  title: string;
  source: string;
  data: ChartData | undefined;
  empty: string | null;
  lines: Line[];
  labels: EndLabel[];
  ticks: { y: number; text: string }[];
  reference: { y: number; text: string } | null;
  start: number;
  end: number;
  yMax: number;
  summary: string;
}

/**
 * Four small multiples above the object graph: fifteen minutes of history
 * from Prometheus, and the live value read straight from the pods.
 *
 * Drawn by hand in SVG rather than with a chart library - four line charts do
 * not earn a dependency. One y axis each, from zero; 2px lines, each series
 * keeping one colour however many others come and go; a label
 * at the end of every line, because two of the four colours are below 3:1 on
 * white; a crosshair tooltip; and a table view for anyone who would rather
 * read numbers than lines.
 */
@Component({
  selector: 'rb-charts-strip',
  template: `
    <div class="strip">
      @for (p of plots(); track p.key) {
        <figure class="chart">
          <figcaption>
            <div class="title">{{ p.title }}</div>
            <div class="source">{{ p.source }}</div>
          </figcaption>

          <div class="now" aria-live="polite">
            @if (p.key === 'latency' || p.key === 'pool') {
              <span class="muted">live: Prometheus only</span>
            } @else if (p.data?.live) {
              <span class="muted">{{ p.data!.live }}</span>
            } @else if (p.data?.now?.length) {
              @for (r of p.data!.now; track r.label) {
                <span class="reading"><b>{{ format(p.key, r.value) }}</b> {{ short(r.label) }}</span>
              }
              <span class="tag">live from pods</span>
            } @else {
              <span class="muted">live: waiting for a second reading</span>
            }
          </div>

          @if (p.empty) {
            <!-- HTML, not SVG text: an SVG scaled to a quarter width shrinks its words past reading. -->
            <p class="empty">{{ p.empty }}</p>
          }
          <div class="plot" [hidden]="!!p.empty" (pointerleave)="hover.set(null)">
            <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" role="img" [attr.aria-label]="p.summary">
              @if (!p.empty) {
                @for (t of p.ticks; track t.y) {
                  <line class="grid" [attr.x1]="L" [attr.x2]="L + PLOT_W" [attr.y1]="t.y" [attr.y2]="t.y" />
                  <text class="tick" [attr.x]="L - 4" [attr.y]="t.y + 3">{{ t.text }}</text>
                }
                @if (p.reference; as ref) {
                  <line class="ref" [attr.x1]="L" [attr.x2]="L + PLOT_W" [attr.y1]="ref.y" [attr.y2]="ref.y" />
                  <text class="ref-text" [attr.x]="L + 2" [attr.y]="ref.y - 3">{{ ref.text }}</text>
                }
                <text class="tick" [attr.x]="L" [attr.y]="H - 4" text-anchor="start">−15m</text>
                <text class="tick" [attr.x]="L + PLOT_W" [attr.y]="H - 4">now</text>
                @for (line of p.lines; track line.label) {
                  @for (d of line.segments; track $index) {
                    <polyline class="series" [attr.points]="d" [style.stroke]="'var(--series-' + line.slot + ')'" />
                  }
                }
                @for (lab of p.labels; track lab.label) {
                  <rect [attr.x]="L + PLOT_W + 6" [attr.y]="lab.y - 4" width="8" height="8" rx="2"
                        [style.fill]="'var(--series-' + lab.slot + ')'" />
                  <text class="end" [attr.x]="L + PLOT_W + 17" [attr.y]="lab.y + 3">{{ lab.text }}</text>
                }
                @if (hover()?.key === p.key) {
                  <line class="crosshair" [attr.x1]="hover()!.x" [attr.x2]="hover()!.x" [attr.y1]="T" [attr.y2]="T + PLOT_H" />
                }
                <rect class="hit" [attr.x]="L" [attr.y]="T" [attr.width]="PLOT_W" [attr.height]="PLOT_H"
                      (pointermove)="track(p, $event)" />
              }
            </svg>
            @if (hover(); as h) {
              @if (h.key === p.key && h.rows.length) {
                <div class="tip" [style.left.%]="h.leftPct">
                  <div class="tip-time">{{ h.time }}</div>
                  @for (row of h.rows; track row.label) {
                    <div class="tip-row">
                      <span class="sw" [style.background]="'var(--series-' + row.slot + ')'"></span>
                      <span>{{ short(row.label) }}</span><b>{{ row.text }}</b>
                    </div>
                  }
                </div>
              }
            }
          </div>

          @if (p.lines.length > 1) {
            <div class="legend">
              @for (line of p.lines; track line.label) {
                <span><span class="sw" [style.background]="'var(--series-' + line.slot + ')'"></span>{{ short(line.label) }}</span>
              }
            </div>
          }

          @if (!p.empty) {
            <button class="table-toggle" (click)="toggleTable(p.key)">
              {{ tables().has(p.key) ? 'Hide table' : 'Table' }}
            </button>
            @if (tables().has(p.key)) {
              <table class="data">
                <thead><tr><th>time</th>@for (line of p.lines; track line.label) { <th>{{ short(line.label) }}</th> }</tr></thead>
                <tbody>
                  @for (row of tableRows(p); track row.t) {
                    <tr><td>{{ row.time }}</td>@for (v of row.values; track $index) { <td>{{ v }}</td> }</tr>
                  }
                </tbody>
              </table>
            }
          }
        </figure>
      }
    </div>
  `,
  styles: `
    .strip {
      --series-1: #2a78d6; --series-2: #eb6834; --series-3: #1baf7a; --series-4: #eda100;
      display: grid; grid-template-columns: repeat(var(--cols, 2), minmax(0, 1fr)); gap: 16px;
    }
    @media (max-width: 599px) { .strip { grid-template-columns: minmax(0, 1fr); } }
    .chart { margin: 0; min-width: 0; border: 1px solid var(--line); border-radius: 6px; padding: 10px 12px; background: var(--white); }
    figcaption { display: flex; flex-direction: column; gap: 1px; }
    .title { font-weight: 700; font-size: 14px; }
    .source { font-size: 11px; color: var(--muted); }
    .now { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 12px; margin: 6px 0 4px; font-size: 13px; min-height: 20px; }
    .now b { font-family: var(--mono); font-size: 16px; }
    .reading { color: var(--ink-soft); }
    .tag { font-size: 11px; color: var(--muted); border: 1px solid var(--line); border-radius: 999px; padding: 0 7px; }
    .muted { color: var(--muted); font-size: 12px; }
    .plot { position: relative; }
    svg { display: block; width: 100%; height: auto; overflow: visible; }
    .grid { stroke: var(--line); stroke-width: 1; }
    .tick { font-size: 9px; fill: var(--muted); text-anchor: end; font-family: var(--mono); }
    .ref { stroke: var(--muted); stroke-width: 1; stroke-dasharray: 3 3; }
    .ref-text { font-size: 9px; fill: var(--muted); }
    .series { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
    .end { font-size: 9.5px; fill: var(--ink-soft); font-family: var(--mono); }
    .empty { margin: 8px 0; min-height: 60px; font-size: 12px; color: var(--muted); display: flex; align-items: center;
             border: 1px dashed var(--line); border-radius: 4px; padding: 8px; }
    .crosshair { stroke: var(--ink); stroke-width: 1; opacity: .5; }
    .hit { fill: transparent; cursor: crosshair; }
    .tip { position: absolute; top: 4px; transform: translateX(-50%); background: var(--white); border: 1px solid var(--line);
           border-radius: 6px; padding: 6px 8px; font-size: 12px; pointer-events: none; box-shadow: 0 4px 12px rgba(0,0,0,.08);
           white-space: nowrap; z-index: 2; }
    .tip-time { color: var(--muted); font-family: var(--mono); font-size: 11px; margin-bottom: 2px; }
    .tip-row { display: flex; align-items: center; gap: 6px; }
    .tip-row b { margin-left: auto; padding-left: 10px; font-family: var(--mono); }
    .sw { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }
    .legend { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 11px; color: var(--ink-soft); margin-top: 4px; }
    .table-toggle { margin-top: 6px; background: none; border: 0; padding: 0; font-size: 12px; color: var(--chip-info-fg); cursor: pointer; }
    .data { width: 100%; border-collapse: collapse; font-size: 11px; font-family: var(--mono); margin-top: 6px; }
    .data th, .data td { text-align: right; padding: 2px 4px; border-bottom: 1px solid var(--line); }
    .data th:first-child, .data td:first-child { text-align: left; }
  `
})
export class ChartsStrip {
  private readonly metrics = inject(MetricsService);
  /** Pod label to colour slot, kept while the pod exists so no other line changes colour. */
  private readonly podSlots = new Map<string, number>();

  protected readonly W = W;
  protected readonly H = H;
  protected readonly L = L;
  protected readonly T = T;
  protected readonly PLOT_W = PLOT_W;
  protected readonly PLOT_H = PLOT_H;

  protected readonly hover = signal<{
    key: ChartKey; x: number; leftPct: number; time: string;
    rows: { label: string; slot: number; text: string }[];
  } | null>(null);
  protected readonly tables = signal<ReadonlySet<ChartKey>>(new Set());

  protected readonly plots = computed<Plot[]>(() => {
    const all = this.metrics.charts();
    return CHARTS.map((key) => this.plot(key, all[key]));
  });

  private plot(key: ChartKey, data: ChartData | undefined): Plot {
    const title = TITLES[key];
    const source = SOURCES[key];
    const series = (data?.series ?? []).slice().sort((a, b) => a.label.localeCompare(b.label)).slice(0, 4);
    const allPoints = series.flatMap((s) => s.points);
    const end = allPoints.length ? Math.max(Date.now() / 1000, ...allPoints.map((p) => p[0])) : Date.now() / 1000;
    const start = end - WINDOW_S;
    const scale = key === 'latency' ? 1000 : 1;
    const dataMax = Math.max(0, ...allPoints.map((p) => p[1] * scale));
    const yMax = key === 'pool' ? Math.max(5, nice(dataMax)) : nice(Math.max(dataMax, key === 'replicas' ? 2 : 1));
    const x = (t: number) => L + ((t - start) / WINDOW_S) * PLOT_W;
    const y = (v: number) => T + PLOT_H - (Math.min(v, yMax) / yMax) * PLOT_H;

    const empty = !data ? 'Reading…'
      : series.length === 0 ? (data.history ?? 'No data in the last 15 minutes.')
      : null;

    const slots = this.slots(key, series.map((s) => s.label));
    const lines: Line[] = series.map((s) => {
      const segments: string[] = [];
      let current: string[] = [];
      let prev: number | null = null;
      for (const [t, v] of s.points) {
        if (t < start) {
          continue;
        }
        if (prev !== null && t - prev > GAP_S && current.length) {
          segments.push(current.join(' '));
          current = [];
        }
        current.push(`${x(t).toFixed(1)},${y(v * scale).toFixed(1)}`);
        prev = t;
      }
      if (current.length) {
        segments.push(current.join(' '));
      }
      const lastPoint = s.points[s.points.length - 1];
      return {
        label: s.label, slot: slots.get(s.label) ?? 4, segments,
        last: lastPoint ? { x: x(lastPoint[0]), y: y(lastPoint[1] * scale), v: lastPoint[1] } : null
      };
    });

    // End labels, nudged apart so two lines ending close together stay legible.
    const labels: EndLabel[] = lines
      .filter((l) => l.last)
      .map((l) => ({ label: l.label, slot: l.slot, y: l.last!.y, text: this.short(l.label) }))
      .sort((a, b) => a.y - b.y);
    for (let i = 1; i < labels.length; i++) {
      if (labels[i].y - labels[i - 1].y < 10) {
        labels[i].y = labels[i - 1].y + 10;
      }
    }

    const ticks = [0, yMax / 2, yMax].map((v) => ({ y: y(v), text: formatTick(v) }));
    const reference = key === 'pool' ? { y: y(5), text: 'pool size 5' } : null;
    const summary = `${title}: ` + (lines.length
      ? lines.map((l) => `${this.short(l.label)} ${l.last ? this.format(key, l.last.v) : 'no data'}`).join(', ')
      : (empty ?? 'no data'));

    return { key, title, source, data, empty, lines, labels, ticks, reference, start, end, yMax, summary };
  }

  protected track(p: Plot, event: PointerEvent): void {
    const svg = (event.currentTarget as SVGRectElement).ownerSVGElement!;
    const box = svg.getBoundingClientRect();
    const vx = ((event.clientX - box.left) / box.width) * W;
    const t = p.start + ((vx - L) / PLOT_W) * WINDOW_S;
    const series = (p.data?.series ?? []).slice().sort((a, b) => a.label.localeCompare(b.label)).slice(0, 4);
    const slots = this.slots(p.key, series.map((s) => s.label));
    const rows = series.map((s) => {
      let best: [number, number] | null = null;
      for (const pt of s.points) {
        if (!best || Math.abs(pt[0] - t) < Math.abs(best[0] - t)) {
          best = pt;
        }
      }
      return best && Math.abs(best[0] - t) <= GAP_S
        ? { label: s.label, slot: slots.get(s.label) ?? 4, text: this.format(p.key, best[1]) }
        : null;
    }).filter((r): r is { label: string; slot: number; text: string } => r !== null);
    this.hover.set({
      key: p.key, x: Math.max(L, Math.min(L + PLOT_W, vx)),
      leftPct: Math.max(15, Math.min(85, (vx / W) * 100)),
      time: new Date(t * 1000).toLocaleTimeString([], { hour12: false }),
      rows
    });
  }

  /** Each label's colour slot: fixed for named series, sticky for pods. */
  private slots(key: ChartKey, labels: string[]): Map<string, number> {
    const fixed = FIXED_SLOTS[key];
    if (fixed) {
      return new Map(labels.map((l) => [l, fixed[l] ?? 4]));
    }
    for (const gone of [...this.podSlots.keys()].filter((l) => !labels.includes(l))) {
      this.podSlots.delete(gone);
    }
    for (const label of labels) {
      if (!this.podSlots.has(label)) {
        const used = new Set(this.podSlots.values());
        this.podSlots.set(label, [1, 2, 3, 4].find((s) => !used.has(s)) ?? 4);
      }
    }
    return new Map(labels.map((l) => [l, this.podSlots.get(l)!]));
  }

  protected toggleTable(key: ChartKey): void {
    this.tables.update((was) => {
      const next = new Set(was);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  /** The last eight timestamps any series has, newest first. */
  protected tableRows(p: Plot): { t: number; time: string; values: string[] }[] {
    const series = (p.data?.series ?? []).slice().sort((a, b) => a.label.localeCompare(b.label)).slice(0, 4);
    const times = [...new Set(series.flatMap((s) => s.points.map((pt) => pt[0])))].sort((a, b) => b - a).slice(0, 8);
    return times.map((t) => ({
      t,
      time: new Date(t * 1000).toLocaleTimeString([], { hour12: false }),
      values: series.map((s) => {
        const pt = s.points.find((q) => q[0] === t);
        return pt ? this.format(p.key, pt[1]) : '—';
      })
    }));
  }

  protected format(key: ChartKey, value: number): string {
    if (key === 'latency') {
      return `${Math.round(value * 1000)} ms`;
    }
    if (key === 'requests') {
      return value.toFixed(1);
    }
    return String(Math.round(value));
  }

  /**
   * Short enough to sit at a line's end: the two services by their first word,
   * and pods by the hash suffix that tells them apart.
   */
  protected short(label: string): string {
    if (label === 'booking-service') {
      return 'booking';
    }
    if (label === 'queue-gate') {
      return 'gate';
    }
    const parts = label.split('-');
    return parts.length > 3 ? parts.slice(-1)[0] : label;
  }
}

/** The smallest of 1, 2 or 5 times a power of ten at or above v. */
function nice(v: number): number {
  if (v <= 0) {
    return 1;
  }
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 2, 5, 10]) {
    if (m * p >= v) {
      return m * p;
    }
  }
  return 10 * p;
}

function formatTick(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}

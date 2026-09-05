import { Component, computed, effect, inject, signal } from '@angular/core';
import { ClusterService } from './cluster.service';
import { StateService } from './state.service';


/**
 * The workload a pod belongs to.
 *
 * Matched against the workloads the cluster reports rather than guessed from
 * the name, because guessing does not survive contact with the names. A
 * Deployment's pod is <deployment>-<replicaset>-<suffix> and a Job's is
 * <job>-<suffix>, so a fixed two-segment trim is wrong for one of them; and
 * trimming a segment that "looks like a ReplicaSet hash" is wrong too, because
 * a drop id looks exactly like one - load-d-00b8f5a3-49tpt collapsed to
 * "load-d" under both rules, folding every load run into a single row.
 *
 * The API gives the real names, so they are used, and anything unrecognised
 * keeps everything but its own suffix instead of being cut to fit a pattern.
 */
function ownerOf(podName: string, known: string[]): string {
  const match = known
    .filter((name) => podName.startsWith(name + '-'))
    .sort((a, b) => b.length - a.length)[0];
  if (match) {
    return match;
  }
  const parts = podName.split('-');
  return parts.length > 1 ? parts.slice(0, -1).join('-') : podName;
}

interface PodGlyph {
  name: string;
  healthy: boolean;
  cpu: string;
  restarts: number;
  fresh: boolean;
}

interface WorkloadRow {
  name: string;
  pods: PodGlyph[];
  millis: number;
  /** Replica bounds, when an autoscaler governs this one. */
  min: number | null;
  max: number | null;
  atCeiling: boolean;
  note: string | null;
  scaled: 'out' | 'in' | null;
}

/**
 * The cluster reacting, drawn as pods.
 *
 * Beside the seats rather than below the fold, and one square per pod rather
 * than a replica count, for the same reason the sitting is drawn as seats: a
 * row that grows from two squares to ten is a thing you see, and "10 / 10
 * replicas" is a thing you have to read and compare against what it said a
 * minute ago.
 *
 * The squares are the real pods from the Kubernetes API, not a count rendered
 * as boxes - each one carries its own name, CPU request and restart count - so
 * a pod appearing here is a pod that exists.
 */
@Component({
  selector: 'rb-pod-pulse',
  template: `
    <div class="card">
      <div class="head">
        <div>
          <div class="title">The cluster, right now</div>
          <p class="sub">One square per running pod, read live from the Kubernetes API</p>
        </div>
        <span class="quota mono">{{ quotaLabel() }}</span>
      </div>

      <!--
        One bar for the whole namespace rather than a bar per workload. The
        budget is shared and fixed, so what matters is how the 3000m is divided
        and how much is left - four separate bars each scaled to their own
        maximum answered neither.
      -->
      <div class="meter">
        <div class="track">
          @for (seg of segments(); track seg.name) {
            <span class="seg" [style.width.%]="seg.percent" [style.background]="seg.colour"
                  [title]="seg.name + ' · ' + seg.millis + 'm'"></span>
          }
        </div>
        <div class="meter-keys">
          @for (seg of segments(); track seg.name) {
            <span class="mkey">
              <i class="swatch" [style.background]="seg.colour"></i>
              <span class="mono">{{ seg.name }}</span>
              <span class="mono dim">{{ seg.millis }}m</span>
            </span>
          }
          <span class="mkey free mono">{{ freeMillis() }}m free</span>
        </div>
      </div>

      @if (rows().length) {
        <div class="rows">
          @for (row of rows(); track row.name) {
            <div class="row" [class.scaled-out]="row.scaled === 'out'"
                 [class.scaled-in]="row.scaled === 'in'">
              <div class="who">
                <span class="name mono">{{ row.name }}</span>
                <span class="count mono">
                  {{ row.pods.length }}@if (row.max) { <span class="of"> / {{ row.max }}</span> }
                  {{ row.pods.length === 1 ? 'pod' : 'pods' }}
                </span>
                @if (row.atCeiling) { <span class="ceiling mono">at the ceiling</span> }
              </div>

              <div class="pods" [class.busy]="row.millis > 0">
                @for (pod of row.pods; track pod.name) {
                  <i class="pod" [class.sick]="!pod.healthy" [class.fresh]="pod.fresh"
                     [title]="pod.name + ' · ' + pod.cpu + ' · ' + pod.restarts + ' restarts'"></i>
                }
              </div>

              <div class="cpu">
                <div class="bar"><span [style.width.%]="share(row.millis)"
                                       [style.background]="colourFor(row.name)"></span></div>
                <span class="millis mono">{{ row.millis }}m</span>
              </div>

              @if (row.note) { <p class="note">{{ row.note }}</p> }
            </div>
          }
        </div>
      } @else {
        <p class="empty">The Kubernetes API is not readable from here just now.</p>
      }
    </div>
  `,
  styles: `
    .card { background: var(--white); border: 1px solid var(--line); border-radius: 4px; overflow: hidden; }
    .head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 8px 16px;
      padding: 16px;
      border-bottom: 1px solid var(--line);
    }
    .title { font-size: 19px; font-weight: 700; }
    .sub { margin: 2px 0 0; font-size: 14px; color: var(--muted); }
    .quota { font-size: 12px; color: var(--muted); }

    .rows { display: flex; flex-direction: column; }
    .row {
      display: grid;
      grid-template-columns: minmax(180px, 1fr) minmax(140px, 2fr) minmax(120px, 1fr);
      align-items: center;
      gap: 8px 16px;
      padding: 12px 16px;
      border-bottom: 1px solid var(--rule);
      transition: background-color 600ms var(--ease);
    }
    .row:last-child { border-bottom: 0; }
    /* A scale is a moment, so the row says so for one read and then settles. */
    .row.scaled-out { background: var(--chip-ok-bg); }
    .row.scaled-in { background: var(--chip-warn-bg); }

    .who { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; min-width: 0; }
    .name { font-size: 13px; font-weight: 700; }
    .count { font-size: 12px; color: var(--muted); }
    .of { color: var(--muted); }
    .ceiling {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: .04em;
      background: var(--chip-warn-bg);
      color: var(--chip-warn-fg);
      border-radius: 999px;
      padding: 2px 8px;
    }

    .pods { display: flex; flex-wrap: wrap; gap: 4px; min-width: 0; }
    .pod {
      width: 14px;
      height: 14px;
      border-radius: 3px;
      background: var(--ink);
      display: inline-block;
    }
    .pod.sick { background: var(--chip-warn-fg); }
    /* A pod that was not here on the last read: the thing worth catching. */
    .pod.fresh { background: var(--chip-ok-fg); animation: pop 900ms var(--ease); }
    @keyframes pop {
      0% { transform: scale(0); opacity: 0; }
      55% { transform: scale(1.35); }
      100% { transform: scale(1); }
    }

    .meter { padding: 14px 16px; border-bottom: 1px solid var(--line); }
    .track {
      display: flex;
      height: 14px;
      border-radius: 999px;
      overflow: hidden;
      background: var(--track);
    }
    .seg { display: block; height: 100%; transition: width 700ms var(--ease); }
    .meter-keys {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 14px;
      margin-top: 8px;
      font-size: 11px;
      color: var(--ink-soft);
    }
    .mkey { display: inline-flex; align-items: center; gap: 5px; }
    .swatch { width: 9px; height: 9px; border-radius: 2px; display: inline-block; }
    .dim { color: var(--muted); }
    .free { color: var(--muted); margin-left: auto; }

    /* Only while the workload is actually spending CPU. */
    .pods.busy .pod { animation: breathe 2.4s ease-in-out infinite; }
    .pods.busy .pod:nth-child(2n) { animation-delay: .3s; }
    .pods.busy .pod:nth-child(3n) { animation-delay: .6s; }
    @keyframes breathe { 50% { opacity: .55; } }

    .cpu { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .bar { flex: 1 1 auto; height: 6px; background: var(--track); border-radius: 999px; overflow: hidden; }
    .bar span {
      display: block;
      height: 100%;
      background: var(--info);
      border-radius: 999px;
      transition: width 700ms var(--ease);
    }
    .millis { font-size: 12px; color: var(--ink-soft); white-space: nowrap; }

    .note { grid-column: 1 / -1; margin: 0; font-size: 12px; color: var(--muted); text-wrap: pretty; }
    .empty { margin: 0; padding: 20px 16px; font-size: 14px; color: var(--muted); }

    @media (prefers-reduced-motion: reduce) {
      .pod.fresh { animation: none; }
      .pods.busy .pod { animation: none; }
      .seg { transition: none; }
      .bar span, .row { transition: none; }
    }
  `
})
export class PodPulse {
  private readonly state = inject(StateService);
  private readonly cluster = inject(ClusterService);

  /** Pod names seen on the previous read, so arrivals can be flashed. */
  private readonly known = signal<Set<string>>(new Set());
  /** Which workloads changed size, and which way. */
  private readonly moved = signal<Map<string, 'out' | 'in'>>(new Map());
  private counts = new Map<string, number>();

  constructor() {
    effect(() => {
      const pods = this.state.view()?.pods;
      if (!pods?.available) {
        return;
      }
      const names = new Set(pods.pods.map((p) => p.name));
      const known = this.knownWorkloads();
      const sizes = new Map<string, number>();
      for (const pod of pods.pods) {
        const owner = ownerOf(pod.name, known);
        sizes.set(owner, (sizes.get(owner) ?? 0) + 1);
      }

      const changes = new Map<string, 'out' | 'in'>();
      for (const [owner, size] of sizes) {
        const before = this.counts.get(owner);
        if (before !== undefined && before !== size) {
          changes.set(owner, size > before ? 'out' : 'in');
        }
      }
      this.counts = sizes;

      if (changes.size) {
        this.moved.set(changes);
        // Long enough to notice, short enough not to become the row's colour.
        setTimeout(() => this.moved.set(new Map()), 2500);
      }
      // Folded in after the pop has played, so it flashes once and not forever.
      setTimeout(() => this.known.set(names), 900);
    });
  }

  /** Workload names as the cluster reports them, not as a name pattern implies. */
  private readonly knownWorkloads = computed(() => {
    const cluster = this.cluster.cluster();
    return [
      ...(cluster?.consumers ?? []).map((c) => c.name),
      ...(cluster?.autoscalers ?? []).map((a) => a.name)
    ];
  });

  protected readonly quotaLabel = computed(() => {
    const quota = this.cluster.cluster()?.quota;
    return quota ? `${quota.usedMillis}m of ${quota.hardMillis}m CPU` : 'quota unreadable';
  });

  private readonly hard = computed(() => this.cluster.cluster()?.quota?.hardMillis ?? 3000);

  /** Stable per workload, so a colour means the same thing between reads. */
  private static readonly COLOURS = ['#D40511', '#00558C', '#B36A00', '#007A33', '#4A4A4A'];

  protected colourFor(name: string): string {
    const names = this.rows().map((r) => r.name);
    const i = names.indexOf(name);
    return PodPulse.COLOURS[(i < 0 ? 0 : i) % PodPulse.COLOURS.length];
  }

  protected readonly segments = computed(() =>
    this.rows()
      .filter((row) => row.millis > 0)
      .map((row) => ({
        name: row.name,
        millis: row.millis,
        percent: this.share(row.millis),
        colour: this.colourFor(row.name)
      })));

  protected readonly freeMillis = computed(() => {
    const quota = this.cluster.cluster()?.quota;
    return quota ? quota.freeMillis : 0;
  });

  protected share(millis: number): number {
    return Math.min(100, Math.round((millis / this.hard()) * 100));
  }

  protected readonly rows = computed<WorkloadRow[]>(() => {
    const pods = this.state.view()?.pods;
    const cluster = this.cluster.cluster();
    if (!pods?.available) {
      return [];
    }
    const seen = this.known();
    const moved = this.moved();
    const names = this.knownWorkloads();

    const byOwner = new Map<string, PodGlyph[]>();
    for (const pod of pods.pods) {
      const owner = ownerOf(pod.name, names);
      const glyphs = byOwner.get(owner) ?? [];
      glyphs.push({
        name: pod.name,
        healthy: pod.healthy,
        cpu: pod.cpu,
        restarts: pod.restarts,
        fresh: seen.size > 0 && !seen.has(pod.name)
      });
      byOwner.set(owner, glyphs);
    }

    // Every load Job is the same thing wearing a different drop id, and their
    // pods linger after the run. Eight rows of them says nothing that one row
    // does not; the CPU is still counted, because they are still spending it.
    const collapsed = new Map<string, PodGlyph[]>();
    for (const [owner, glyphs] of byOwner) {
      const key = owner.startsWith('load-') ? 'load generator' : owner;
      collapsed.set(key, [...(collapsed.get(key) ?? []), ...glyphs]);
    }

    return [...collapsed.entries()]
      .sort((a, b) => b[1].length - a[1].length)
      .map(([name, glyphs]) => {
        const hpa = cluster?.autoscalers.find((a) => a.name === name) ?? null;
        const spend = name === 'load generator'
          ? {
              millis: (cluster?.consumers ?? [])
                .filter((c) => c.name.startsWith('load-'))
                .reduce((sum, c) => sum + c.millis, 0)
            }
          : cluster?.consumers.find((c) => c.name === name) ?? null;
        return {
          name,
          pods: glyphs,
          millis: spend?.millis ?? 0,
          min: hpa?.min ?? null,
          max: hpa?.max ?? null,
          atCeiling: !!hpa && hpa.current >= hpa.max,
          note: hpa?.note ?? null,
          scaled: moved.get(name) ?? null
        };
      });
  });
}

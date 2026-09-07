import { Component, computed, effect, inject, signal } from '@angular/core';
import { ClusterService } from './cluster.service';
import { LoadService } from './load.service';
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
  /** Running but not ready yet - starting up, not broken. */
  starting: boolean;
  phase: string;
  cpu: string;
  restarts: number;
  fresh: boolean;
}

interface WorkloadRow {
  name: string;
  pods: PodGlyph[];
  millis: number;
  /** What the autoscaler is allowed and what it currently wants. */
  min: number | null;
  max: number | null;
  desired: number | null;
  /** The autoscaler's own replica count, which spans ReplicaSets mid-rollout. */
  current: number | null;
  /** Empty slots between the running pods and the ceiling. */
  headroom: number[];
  currentPercent: number | null;
  targetPercent: number | null;
  hasHpa: boolean;
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

      @if (blocked(); as why) {
        <!--
          Above the meter, because the meter is the evidence for it: the run
          cannot be placed, and the next 40px are exactly where the budget went.
        -->
        <div class="blocked">
          <div class="blocked-bar"></div>
          <div class="blocked-body">
            <div class="blocked-title">Your load run has not started: {{ why.reason }}</div>
            <div class="blocked-text mono">{{ why.message }}</div>
            <div class="blocked-text">
              It asks for {{ why.cpuMillis }}m, and the meter below is where the budget went.
              Nothing is broken. The scheduler is refusing to place a pod the namespace cannot
              pay for, and it will place it by itself as soon as the budget frees up. Choosing
              fewer customers is what frees it.
            </div>
          </div>
        </div>
      }

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
        <!--
          Headed, because four unlabelled columns of squares and bars is a
          puzzle. The header names each column once so the rows can stay quiet.
        -->
        <div class="head-row">
          <span class="h-name">SERVICE</span>
          <span class="h-pods">PODS</span>
          <span class="h-target">CPU AGAINST SCALING TARGET</span>
          <span class="h-cpu">CPU</span>
        </div>
        <div class="rows">
          @for (row of rows(); track row.name) {
            <div class="row" [class.scaled-out]="row.scaled === 'out'"
                 [class.scaled-in]="row.scaled === 'in'">
              <div class="who">
                <div class="who-name">
                  <span class="swatch" [style.background]="colourFor(row.name)"></span>
                  <span class="name mono">{{ row.name }}</span>
                </div>
                <div class="who-sub">
                  {{ row.pods.length }} {{ row.pods.length === 1 ? 'pod' : 'pods' }}@if (row.hasHpa) { · autoscales {{ row.min }} to {{ row.max }}} @else { · fixed}
                </div>
              </div>

              <div class="pods" [class.busy]="row.millis > 0">
                @for (pod of row.pods; track pod.name) {
                  <i class="pod" [class.sick]="!pod.healthy && !pod.starting"
                     [class.starting]="pod.starting" [class.fresh]="pod.fresh"
                     [title]="pod.name + ' · ' + pod.phase + ' · ' + pod.cpu + ' · ' + pod.restarts + ' restarts'"></i>
                }
                @for (spare of row.headroom; track spare) {
                  <i class="pod spare" [title]="'headroom to ' + row.max"></i>
                }
              </div>

              <div class="target">
                <div class="bar">
                  <span [style.width.%]="targetPct(row)" [style.background]="colourFor(row.name)"></span>
                </div>
                <div class="target-note">{{ targetNote(row) }}</div>
              </div>

              <div class="cpu-figure mono">{{ row.millis }}m</div>
            </div>
          }
        </div>
        @if (pool(); as p) {
          <div class="pool">
            <div class="pool-head">
              <span class="mono">oracle pool</span>
              <span class="mono muted-fg">{{ p.connections }} / {{ p.cap }} connections</span>
            </div>
            <div class="bar">
              <span [class.info]="!p.saturated" [class.full]="p.saturated"
                    [style.width.%]="p.percent"></span>
            </div>
            <div class="pool-note">
              Each of {{ p.deployment }}'s pods above holds {{ p.perReplica }} connections, against
              an Oracle Always Free cap near {{ p.cap }}.
              {{ p.saturated ? 'Full: further claims get 503 with Retry-After.' : '' }}
            </div>
          </div>
        }
      } @else {
        <p class="empty">{{ detail() ?? 'The Kubernetes API is not readable from here just now.' }}</p>
      }

      <p class="disclosure">
        Load generated inside the cluster reaches queue-gate over the internal Service, so it
        <strong>skips the public Route</strong>. It exercises the queue, the admission rate and the
        seat invariant; it does not exercise the ingress path.
      </p>
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
    /*
      Four columns that stay in step with the header above them: name, pods,
      utilisation against the scaling target, and the CPU it is spending. flex
      with matching bases rather than grid, so a narrow screen wraps a row
      instead of shearing the header off its columns.
    */
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 20px;
      padding: 16px;
      border-bottom: 1px solid var(--rule);
      transition: background-color 600ms var(--ease);
    }
    .row:last-child { border-bottom: 0; }
    /* A scale is a moment, so the row says so for one read and then settles. */
    .row.scaled-out { background: var(--chip-ok-bg); }
    .row.scaled-in { background: var(--chip-warn-bg); }

    .head-row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 20px;
      padding: 12px 16px;
      background: var(--canvas);
      border-bottom: 1px solid var(--line);
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .08em;
      color: var(--muted);
    }
    .h-name { flex: 1 1 180px; min-width: 0; }
    .h-pods { flex: 1 1 150px; min-width: 0; }
    .h-target { flex: 1 1 190px; min-width: 0; }
    .h-cpu { flex: none; width: 68px; text-align: right; }

    .who { flex: 1 1 180px; min-width: 0; }
    .who-name { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .who-sub { font-size: 13px; color: var(--muted); margin-top: 2px; }
    .swatch { width: 10px; height: 10px; border-radius: 2px; flex: none; }

    .target { flex: 1 1 190px; min-width: 0; }
    .target .bar { height: 6px; background: var(--track); border-radius: 2px; overflow: hidden; }
    .target .bar span { display: block; height: 100%; border-radius: 2px; transition: width 700ms var(--ease); }
    .target-note { font-size: 13px; color: var(--muted); margin-top: 6px; text-wrap: pretty; }

    .cpu-figure { flex: none; width: 68px; text-align: right; font-size: 14px; }
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

    .pods { flex: 1 1 150px; display: flex; flex-wrap: wrap; gap: 4px; min-width: 0; align-content: center; }
    .pod {
      width: 14px;
      height: 14px;
      border-radius: 3px;
      background: var(--ink);
      display: inline-block;
    }
    .pod.sick { background: var(--chip-bad-fg); }
    /* Room the autoscaler could still use. */
    .pod.spare { background: none; border: 1px dashed #C9C9C9; }
    /*
      Starting is not sick. A pod coming up during a scale-out or a rollout is
      the normal case on this page, and colouring it like a fault made a healthy
      rollout look like an incident.
    */
    .pod.starting {
      background: var(--track);
      border: 2px solid var(--chip-warn-fg);
      animation: waking 1.6s ease-in-out infinite;
    }
    @keyframes waking { 50% { border-color: var(--chip-ok-fg); } }
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
    .cpu .bar { flex: 1 1 auto; height: 6px; background: var(--track); border-radius: 999px; overflow: hidden; }
    .cpu .bar span {
      display: block;
      height: 100%;
      background: var(--info);
      border-radius: 999px;
      transition: width 700ms var(--ease);
    }
    .millis { font-size: 12px; color: var(--ink-soft); white-space: nowrap; }

    .note { grid-column: 1 / -1; margin: 0; font-size: 12px; color: var(--muted); text-wrap: pretty; }
    .empty { margin: 0; padding: 20px 16px; font-size: 14px; color: var(--muted); }

    /* Carried over with the markup: these were local to the constraints panel. */
    .blocked { display: flex; gap: 12px; padding: 14px 16px; border-bottom: 1px solid var(--line); }
    .blocked-bar { flex: none; width: 4px; border-radius: 999px; background: var(--chip-warn-fg); }
    .blocked-body { min-width: 0; }
    .blocked-title { font-size: 15px; font-weight: 700; text-wrap: pretty; }
    .blocked-text { font-size: 13px; color: var(--ink-soft); margin-top: 4px; text-wrap: pretty; }
    .blocked-text.mono { font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }

    .pool { padding: 14px 16px; border-top: 1px solid var(--line); }
    .pool-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 4px 12px;
      margin-bottom: 8px;
    }
    .pool-note { font-size: 12px; color: var(--muted); margin-top: 8px; text-wrap: pretty; }
    .muted-fg { color: var(--muted); }
    /* The pool bar uses the global .bar (8px); only .full is local. */
    .bar span.full { background: var(--dhl-red); }

    .disclosure {
      margin: 0;
      padding: 14px 16px;
      border-top: 1px solid var(--rule);
      font-size: 13px;
      color: var(--ink-soft);
      text-wrap: pretty;
    }

    @media (prefers-reduced-motion: reduce) {
      .pod.fresh { animation: none; }
      .pods.busy .pod { animation: none; }
      .pod.starting { animation: none; }
      .seg { transition: none; }
      .bar span, .row { transition: none; }
    }
  `
})
export class PodPulse {
  private readonly state = inject(StateService);
  private readonly cluster = inject(ClusterService);
  private readonly loads = inject(LoadService);

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
    return quota
      ? `${quota.name} · ${quota.usedMillis}m of ${quota.hardMillis}m CPU`
      : 'quota unreadable';
  });

  private readonly hard = computed(() => this.cluster.cluster()?.quota?.hardMillis ?? 3000);

  /** Stable per workload, so a colour means the same thing between reads. */
  private static readonly COLOURS = ['#D40511', '#00558C', '#B36A00', '#007A33', '#4A4A4A'];

  protected colourFor(name: string): string {
    const names = this.rows().map((r) => r.name);
    const i = names.indexOf(name);
    return PodPulse.COLOURS[(i < 0 ? 0 : i) % PodPulse.COLOURS.length];
  }

  protected readonly segments = computed(() => {
    const spending = this.rows().filter((row) => row.millis > 0);
    const segments = spending.map((row) => ({
      name: row.name,
      millis: row.millis,
      percent: this.share(row.millis),
      colour: this.colourFor(row.name)
    }));
    // The rows only cover workloads with pods on screen, so without this the
    // meter would not add up to the quota it claims to divide - CPU charged to
    // something with no visible pod would simply vanish. The panel this
    // replaced drew one aggregate bar from quota.percent and could not lose it.
    const used = this.cluster.cluster()?.quota?.usedMillis ?? 0;
    const accounted = spending.reduce((sum, row) => sum + row.millis, 0);
    const other = used - accounted;
    if (other > 0) {
      // A literal colour: colourFor indexes the rows, and this is not one.
      segments.push({ name: 'other', millis: other, percent: this.share(other), colour: 'var(--muted)' });
    }
    return segments;
  });

  protected readonly freeMillis = computed(() => {
    const quota = this.cluster.cluster()?.quota;
    return quota ? quota.freeMillis : 0;
  });

  /**
   * A run the scheduler will not place, in the scheduler's own words.
   *
   * Constraints are content, not failure: a Pending Job is the namespace budget
   * doing its job, and the panel that explains it sits with the meter showing
   * where the budget went rather than on its own elsewhere.
   */
  protected readonly blocked = computed(() => {
    const run = this.loads.run();
    if (!run || run.phase !== 'PENDING' || !run.message) {
      return null;
    }
    return { reason: run.reason ?? 'Pending', message: run.message, cpuMillis: run.cpuMillis };
  });

  protected readonly pool = computed(() => this.cluster.cluster()?.pool ?? null);
  protected readonly detail = computed(() => this.cluster.cluster()?.detail ?? null);

  /** Only meaningful where an autoscaler exists; "no metric yet" is a lie without one. */
  protected utilisation(current: number | null, target: number | null): string {
    if (current === null || target === null) {
      return 'no cpu metric collected yet';
    }
    return `cpu ${current}% of a ${target}% target`;
  }

  /**
   * How close this workload is to the CPU level that would make it scale.
   *
   * The bar the design asks for is utilisation against the autoscaler's target,
   * not CPU against the namespace budget - a workload at 17% of a 60% target is
   * a third of the way to needing another pod, and that is the number worth
   * drawing beside a row of pods. Workloads with no autoscaler get their share
   * of the quota instead, since there is no target to be under.
   */
  protected targetPct(row: WorkloadRow): number {
    if (row.hasHpa && row.currentPercent !== null && row.targetPercent) {
      return Math.min(100, Math.round((row.currentPercent / row.targetPercent) * 100));
    }
    return this.share(row.millis);
  }

  protected targetNote(row: WorkloadRow): string {
    if (!row.hasHpa) {
      return `fixed at ${row.pods.length} ${row.pods.length === 1 ? 'pod' : 'pods'}, never scales`;
    }
    if (row.currentPercent === null || row.targetPercent === null) {
      return 'no cpu metric collected yet';
    }
    const at = `cpu ${row.currentPercent}% against a ${row.targetPercent}% target`;
    if (row.atCeiling) {
      return `${at}. At the ceiling, it cannot add another pod`;
    }
    if (row.desired !== null && row.current !== null && row.desired !== row.current) {
      return `${at}, scaling to ${row.desired}`;
    }
    return `${at}, still under the line`;
  }

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
      // A Completed Job pod is history, not a workload. It reports 0/1 exactly
      // like a crash loop does, so painting it by readiness alone drew every
      // finished load run as an unhealthy pod - four tan squares implying
      // trouble where the trouble was that the run had ended successfully.
      if (pod.phase === 'Succeeded' || pod.phase === 'Failed') {
        continue;
      }
      const owner = ownerOf(pod.name, names);
      const glyphs = byOwner.get(owner) ?? [];
      glyphs.push({
        name: pod.name,
        healthy: pod.healthy,
        starting: !pod.healthy && pod.phase === 'Running',
        phase: pod.phase,
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
        // The API names autoscalers "hpa/queue-gate" while consumers and pods
        // are just "queue-gate", so matching on equality found nothing at all -
        // which is why no row has ever shown a ceiling, a target or what the
        // autoscaler wants, however hard the cluster was scaling.
        const hpa = cluster?.autoscalers
          .find((a) => a.name === name || a.name === `hpa/${name}`) ?? null;
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
          headroom: hpa
            ? Array.from({ length: Math.max(0, hpa.max - glyphs.length) }, (_, i) => i)
            : [],
          desired: hpa?.desired ?? null,
          current: hpa?.current ?? null,
          currentPercent: hpa?.currentPercent ?? null,
          targetPercent: hpa?.targetPercent ?? null,
          hasHpa: !!hpa,
          // Counted from the squares, not from hpa.current. The two disagree
          // during a rollout, and a badge saying "at the ceiling" beside nine
          // squares when the ceiling is ten is the kind of thing that makes a
          // reader stop trusting the whole panel.
          atCeiling: !!hpa && glyphs.length >= hpa.max,
          note: hpa?.note ?? null,
          scaled: moved.get(name) ?? null
        };
      });
  });
}

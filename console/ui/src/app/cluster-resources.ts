import { Component, computed, inject, input } from '@angular/core';
import { PodStatus } from './state';
import { StateService } from './state.service';

/**
 * The workloads running in the namespace, read through a ServiceAccount that
 * can see them and not Secrets.
 *
 * <h2>Why it insists on looking alive</h2>
 * Someone opening this link cold has no way to tell a live console from a
 * screenshot of one, and every number on the page is worth less if they assume
 * the second. So the readiness dot pulses, the clock beside the list carries the
 * time of the last successful read, and a hairline sweeps across the panel on
 * the same two-second cadence as the poll. All three are CSS on data that is
 * genuinely arriving — none of them animates when the reading is stale.
 *
 * `detail` mode is the ordinary state on a laptop and a brief state in the
 * cluster, so it renders as a sentence in the panel rather than an error.
 */
@Component({
  selector: 'rb-cluster-resources',
  template: `
    <div class="resources">
      <div class="live-head">
        <span class="live-flag" [class.stale]="!live()">
          <span class="live-dot"></span>
          {{ live() ? 'Live' : 'Stale' }}
        </span>
        <!--
          What the reading says, in one line. The strip previously carried how
          the read was made - namespace, interval, ServiceAccount - which is
          true, unchanging, and not what anybody looks at a live panel for.
        -->
        <span class="summary mono">{{ summary() }}</span>
        <span class="stamp mono">{{ stamp() }}</span>
      </div>
      <div class="sweep" [class.stopped]="!live()"><span></span></div>

      @if (pods().length) {
        <div [class.headed-scroll]="full()">
        @if (full()) {
          <!--
            Headed columns rather than inline key/value pairs. The same facts
            oc get pods shows, in the same order, so somebody who knows the
            command can read this without learning a new layout.
          -->
          <div class="head-row">
            <span class="h-state">STATUS</span>
            <span class="h-name">NAME</span>
            <span class="h-kind">WORKLOAD</span>
            <span class="h-cell">READY</span>
            <span class="h-cell">CPU REQ</span>
            <span class="h-cell">QOS</span>
            <span class="h-cell">IMAGE</span>
            <span class="h-node">NODE</span>
            <span class="h-cell">RESTARTS</span>
            <span class="h-cell">AGE</span>
          </div>
        }
        <ul class="list">
          @for (pod of pods(); track pod.name) {
            <li class="row" [class.headed]="full()">
              <span class="state" [class.ok]="pod.healthy"
                    [class.warn]="!pod.healthy && !starting(pod)"
                    [class.starting]="starting(pod)">
                <span class="dot" [class.beating]="pod.healthy && live()"></span>
                {{ label(pod) }}
              </span>
              <span class="name mono">{{ pod.name }}</span>
              @if (full()) {
                <span class="kind mono" [title]="pod.ownerKind">{{ pod.workload }}</span>
                <span class="cell mono">{{ pod.ready }}</span>
                <span class="cell mono">{{ pod.cpu }}</span>
                <span class="cell mono">{{ pod.qos }}</span>
                <span class="cell mono">{{ pod.image }}</span>
                <span class="node mono" [title]="pod.podIp">{{ pod.node }}</span>
                <span class="cell mono" [class.bad]="pod.restarts > 0">{{ pod.restarts }}</span>
                <span class="cell mono">{{ pod.age }}</span>
              } @else {
                <span class="facts">
                  <span class="fact"><span class="fact-key">replicas</span> <span class="mono">{{ pod.ready }}</span></span>
                </span>
              }
            </li>
          }
        </ul>
        </div>
      } @else {
        <!--
          The degradation contract: the section still renders, and the reason the
          API gave takes the place of the rows. Never a blank panel.
        -->
        <div class="empty">{{ emptyText() }}</div>
      }
    </div>
  `,
  styles: `
    .resources {
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .live-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      padding: 12px 16px;
    }
    .live-flag {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      font-weight: 700;
      letter-spacing: .04em;
      text-transform: uppercase;
      /*
        Red, not green. Green claims the cluster is healthy; this pill only
        claims the reading is live, and red is the recording convention.
      */
      color: var(--dhl-red);
      background: var(--chip-bad-bg);
      border-radius: 999px;
      padding: 3px 12px;
      flex: none;
    }
    .live-flag.stale { color: var(--muted); background: var(--track); }
    .live-flag.stale { color: var(--chip-neutral-fg); background: var(--chip-neutral-bg); }
    /*
      Red, not the pill's own colour. Green reads as "healthy", which is a claim
      about the cluster; this dot is about the reading being live, which is the
      recording convention everyone already knows.
    */
    .live-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--dhl-red);
      animation: livePulse 2s ease-in-out infinite;
    }
    .live-flag.stale .live-dot { background: var(--muted); }
    .live-flag.stale .live-dot { animation: none; }

    /*
      Ten columns do not wrap usefully - a wrapped row interleaves with the next
      one and the header stops meaning anything. The full table scrolls sideways
      instead, which is what every kubectl output does on a narrow terminal.
    */
    .headed-scroll { overflow-x: auto; overflow-y: hidden; }
    .headed-scroll .head-row, .headed-scroll .row { min-width: 1040px; flex-wrap: nowrap; }
    /* The last column has to clear the scroll gutter or it reads as cut off. */
    .headed-scroll .row > :last-child, .headed-scroll .head-row > :last-child { padding-right: 4px; }

    .head-row {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 8px 16px;
      /* Same horizontal padding as a row, or every column sits 16px off it. */
      padding: 10px 16px;
      border-bottom: 1px solid var(--line);
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .08em;
      color: var(--muted);
    }
    .h-state { flex: none; width: 92px; }
    .h-name { flex: 2 1 240px; min-width: 0; }
    .h-kind { flex: 1 1 140px; min-width: 0; }
    .h-cell { flex: none; width: 76px; text-align: right; }
    .h-node { flex: 1 1 150px; min-width: 0; }

    .row.headed { align-items: baseline; gap: 8px 16px; }
    /*
      Striped, because tracking one pod across ten columns on a table this wide
      is the whole job. Tinted from the namespace canvas rather than grey, so it
      reads as banding and not as a selected row.
    */
    .row.headed:nth-child(odd) { background: var(--canvas); }
    .row.headed:hover { background: var(--highlight); }
    .row.headed .state {
      flex: none;
      width: 92px;
      min-width: 0;
      padding: 0;
      background: none;
      font-weight: 400;
      font-size: 13px;
    }
    .row.headed .name { flex: 2 1 240px; min-width: 0; overflow-wrap: anywhere; }
    .kind { flex: 1 1 140px; min-width: 0; font-size: 13px; color: var(--ink-soft); }
    .cell { flex: none; width: 76px; text-align: right; font-size: 13px; color: var(--ink-soft); }
    .cell.bad { color: var(--chip-bad-fg); font-weight: 700; }
    /* The node is long and the least urgent, so it takes the slack column. */
    .node {
      flex: 1 1 150px;
      min-width: 0;
      font-size: 13px;
      color: var(--muted);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .state.starting { color: var(--chip-warn-fg); }
    .summary { font-size: 13px; color: var(--ink-soft); min-width: 0; }
    .stamp { margin-left: auto; font-size: 12px; color: var(--muted); flex: none; }

    /* The poll made visible: one pass of the hairline is one two-second cycle. */
    .sweep { height: 2px; background: var(--rule); overflow: hidden; }
    .sweep > span {
      display: block;
      height: 100%;
      width: 34%;
      /* Red into yellow: one pass of the pair is one two-second poll. */
      background: linear-gradient(90deg,
        transparent, var(--dhl-red), var(--dhl-yellow), transparent);
      animation: sweepAcross 2s linear infinite;
    }
    .sweep.stopped > span { animation: none; background: var(--line); width: 100%; }

    .list { margin: 0; padding: 0; list-style: none; }
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      padding: 14px 16px;
      border-top: 1px solid var(--rule);
    }
    .state {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      font-weight: 700;
      padding: 4px 12px;
      border-radius: 999px;
      white-space: nowrap;
      flex: none;
      min-width: 104px;
    }
    .state.ok { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .state.warn { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .state .dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    .state .dot.beating { animation: readyBeat 2s ease-in-out infinite; }
    .name { font-size: 14px; min-width: 0; overflow-wrap: anywhere; flex: 1 1 200px; }
    .facts { display: flex; flex-wrap: wrap; gap: 4px 20px; margin-left: auto; }
    /*
     * inline-flex with a gap rather than a literal space: Angular strips the
     * whitespace between the two spans at compile time, which ran the label
     * straight into the value ("replicas2/2").
     */
    .fact { display: inline-flex; gap: 6px; font-size: 13px; color: var(--ink-soft); white-space: nowrap; }
    .fact-key { color: var(--muted); }
    .empty {
      padding: 16px;
      border-top: 1px solid var(--rule);
      font-size: 14px;
      color: var(--ink-soft);
      text-wrap: pretty;
      overflow-wrap: anywhere;
    }
  `
})
export class ClusterResources {
  /**
   * Running but not ready yet. Readiness alone cannot tell that from a crash
   * loop - both read 0/1 - so the phase decides, and a pod coming up during a
   * rollout is not drawn as a fault.
   */
  protected starting(pod: PodStatus): boolean {
    return !pod.healthy && pod.phase === 'Running';
  }

  protected label(pod: PodStatus): string {
    if (pod.healthy) {
      return 'Ready';
    }
    if (pod.phase === 'Succeeded' || pod.phase === 'Failed') {
      return 'Completed';
    }
    return this.starting(pod) ? 'Starting' : 'Not ready';
  }

  /** The overview shows the workload and its replicas; the cluster page shows every column. */
  readonly full = input(false);

  private readonly state = inject(StateService);

  private readonly health = computed(() => this.state.view()?.pods ?? null);

  readonly pods = computed(() => this.health()?.pods ?? []);

  /** Something has answered, so the animations are describing real traffic. */
  readonly live = computed(() => this.state.updatedAt() !== null && !this.state.transportError());

  /** Pods, and how many of them are actually serving. */
  readonly summary = computed(() => {
    const list = this.pods();
    if (!list.length) {
      return '';
    }
    const ready = list.filter((p) => p.healthy).length;
    const starting = list.filter((p) => this.starting(p)).length;
    const parts = [`${list.length} ${list.length === 1 ? 'pod' : 'pods'}`, `${ready} ready`];
    if (starting) {
      parts.push(`${starting} starting`);
    }
    return parts.join(' · ');
  });

  readonly stamp = computed(() => {
    const at = this.state.updatedAt();
    return at ? `read ${clock(at)}` : 'not read yet';
  });


  readonly emptyText = computed(() => {
    const health = this.health();
    if (!health) {
      return 'Reading the namespace…';
    }
    return health.available
      ? 'No workloads in this namespace.'
      : health.detail ?? 'The cluster is not readable from here.';
  });
}

/** Local wall clock, seconds included: it is there to be watched changing. */
function clock(at: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
}

import { Component, computed, inject, input } from '@angular/core';
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
        <span class="live-note">{{ sourceLabel() }}</span>
        <span class="stamp mono">{{ stamp() }}</span>
      </div>
      <div class="sweep" [class.stopped]="!live()"><span></span></div>

      @if (pods().length) {
        <ul class="list">
          @for (pod of pods(); track pod.name) {
            <li class="row">
              <span class="state" [class.ok]="pod.healthy" [class.warn]="!pod.healthy">
                <span class="dot" [class.beating]="pod.healthy && live()"></span>
                {{ pod.healthy ? 'Ready' : 'Not ready' }}
              </span>
              <span class="name mono">{{ pod.name }}</span>
              <span class="facts">
                <span class="fact"><span class="fact-key">replicas</span> <span class="mono">{{ pod.ready }}</span></span>
                @if (full()) {
                  <span class="fact"><span class="fact-key">cpu request</span> <span class="mono">{{ pod.cpu }}</span></span>
                  <span class="fact"><span class="fact-key">restarts</span> <span class="mono">{{ pod.restarts }}</span></span>
                  <span class="fact"><span class="fact-key">age</span> <span class="mono">{{ pod.age }}</span></span>
                }
              </span>
            </li>
          }
        </ul>
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
      color: var(--chip-ok-fg);
      background: var(--chip-ok-bg);
      border-radius: 999px;
      padding: 3px 12px;
      flex: none;
    }
    .live-flag.stale { color: var(--chip-neutral-fg); background: var(--chip-neutral-bg); }
    .live-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: currentColor;
      animation: livePulse 2s ease-in-out infinite;
    }
    .live-flag.stale .live-dot { animation: none; }
    .live-note { font-size: 14px; color: var(--ink-soft); min-width: 0; text-wrap: pretty; }
    .stamp { margin-left: auto; font-size: 12px; color: var(--muted); flex: none; }

    /* The poll made visible: one pass of the hairline is one two-second cycle. */
    .sweep { height: 2px; background: var(--rule); overflow: hidden; }
    .sweep > span {
      display: block;
      height: 100%;
      width: 34%;
      background: linear-gradient(90deg, transparent, var(--dhl-red), transparent);
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
  /** The overview shows the workload and its replicas; the cluster page shows every column. */
  readonly full = input(false);

  private readonly state = inject(StateService);

  private readonly health = computed(() => this.state.view()?.pods ?? null);

  readonly pods = computed(() => this.health()?.pods ?? []);

  /** Something has answered, so the animations are describing real traffic. */
  readonly live = computed(() => this.state.updatedAt() !== null && !this.state.transportError());

  readonly stamp = computed(() => {
    const at = this.state.updatedAt();
    return at ? `read ${clock(at)}` : 'not read yet';
  });

  readonly sourceLabel = computed(() => {
    const health = this.health();
    if (health?.namespace) {
      return `ns/${health.namespace}, read every 2 seconds through a namespace-scoped ServiceAccount`;
    }
    return 'Read every 2 seconds through a namespace-scoped ServiceAccount';
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

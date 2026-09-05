import { Component, computed, inject } from '@angular/core';
import { ClusterService } from './cluster.service';
import { LoadService } from './load.service';

/**
 * Constraints, and why this is a feature rather than error handling.
 *
 * When something cannot run, this panel names the limit and what is consuming
 * it. Never "something went wrong". A visitor whose load Job sits Pending
 * because the namespace is out of CPU has been shown a real quota, a real
 * scheduler decision and a real operational trade — which is worth more than a
 * green tick, and is the most operations-flavoured thing this project can put
 * on a screen.
 *
 * So it ships with the load button rather than after it. A run that cannot
 * schedule has to explain itself the first time it happens, and the sentence it
 * explains itself with is the scheduler's own.
 */
@Component({
  selector: 'rb-constraints',
  template: `
    <section class="stack-16">
      <div class="section-head">
        <div style="min-width: 0;">
          <h2>Constraints</h2>
          <p class="sub">What the cluster will and will not give you, live</p>
        </div>
        @if (quota(); as q) {
          <span class="meta">{{ q.name }}</span>
        }
      </div>

      @if (blocked(); as why) {
        <!--
          The whole point of the panel, so it sits above the numbers rather than
          under them. The wording is the cluster's, not ours.
        -->
        <div class="blocked">
          <div class="blocked-bar"></div>
          <div class="blocked-body">
            <div class="blocked-title">Your load run has not started: {{ why.reason }}</div>
            <div class="blocked-text mono">{{ why.message }}</div>
            <div class="blocked-text">
              It asks for {{ why.cpuMillis }}m. {{ freeText() }} Nothing is broken — the scheduler is
              refusing to place a pod the namespace cannot pay for, and it will place it by itself
              as soon as the budget frees up. Scaling the gate back, or choosing fewer customers,
              is what frees it.
            </div>
          </div>
        </div>
      }

      @if (cluster(); as c) {
        @if (c.available) {
          <div class="grid">
            <div class="card pad">
              <div class="row-head">
                <div class="card-title">Namespace CPU</div>
                <div class="mono muted-fg">{{ c.quota!.usedMillis }}m / {{ c.quota!.hardMillis }}m</div>
              </div>
              <div class="bar tall"><span [style.width.%]="c.quota!.percent"></span></div>
              <div class="rows">
                @for (consumer of c.consumers; track consumer.name) {
                  <div class="row">
                    <span class="row-name">{{ consumer.name }}<span class="muted-fg"> · {{ consumer.pods }} {{ consumer.pods === 1 ? 'pod' : 'pods' }}</span></span>
                    <span class="mono muted-fg">{{ consumer.millis }}m</span>
                  </div>
                }
                <div class="row free">
                  <span class="row-name">free</span>
                  <span class="mono">{{ c.quota!.freeMillis }}m</span>
                </div>
              </div>
            </div>

            <div class="card pad stack-16">
              <div class="card-title">Autoscalers and pool</div>
              @for (hpa of c.autoscalers; track hpa.name) {
                <div>
                  <div class="row-head">
                    <span class="mono">{{ hpa.name }}</span>
                    <span class="mono muted-fg">{{ hpa.current }} / {{ hpa.max }} replicas</span>
                  </div>
                  <div class="bar"><span class="info" [style.width.%]="percentOf(hpa.current, hpa.max)"></span></div>
                  <div class="row-note">
                    {{ utilisation(hpa.currentPercent, hpa.targetPercent) }}<!--
                      -->@if (hpa.desired !== hpa.current) {, wants {{ hpa.desired }}}<!--
                      -->@if (hpa.note) { — {{ hpa.note }}}
                  </div>
                </div>
              }
              @if (c.pool; as pool) {
                <div>
                  <div class="row-head">
                    <span class="mono">oracle pool</span>
                    <span class="mono muted-fg">{{ pool.connections }} / {{ pool.cap }} connections</span>
                  </div>
                  <div class="bar">
                    <span [class.info]="!pool.saturated" [class.full]="pool.saturated"
                          [style.width.%]="pool.percent"></span>
                  </div>
                  <div class="row-note">
                    {{ pool.replicas }} {{ pool.replicas === 1 ? 'replica' : 'replicas' }} of
                    {{ pool.deployment }} at {{ pool.perReplica }} connections each, against an
                    Oracle Always Free cap near {{ pool.cap }}.
                    {{ pool.saturated ? 'Full: further claims get 503 with Retry-After.' : '' }}
                  </div>
                </div>
              }
            </div>
          </div>
        } @else {
          <div class="card pad">
            <div class="reason" style="margin-top: 0;">{{ c.detail }}</div>
          </div>
        }
      } @else {
        <div class="card pad">
          <div class="reason" style="margin-top: 0;">Reading the namespace…</div>
        </div>
      }

      <!--
        Disclosed on the page, not buried in a note. A clean run here is not
        evidence the edge would have carried the same traffic, and the ladder
        that measured it is on the load control beside this.
      -->
      <p class="disclosure">
        Load generated inside the cluster reaches queue-gate over the internal Service, so it
        <strong>skips the public Route</strong>. It exercises the queue, the admission rate and the
        seat invariant; it does not exercise the ingress path. Offering more than two hundred users
        is how you see the edge shed connections instead.
      </p>
    </section>
  `,
  styles: `
    .grid {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr));
      align-items: start;
    }
    .pad { padding: 24px; }
    .card-title { font-size: 17px; font-weight: 700; }
    .row-head {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      justify-content: space-between;
      align-items: baseline;
      margin-bottom: 8px;
      font-size: 14px;
    }
    .bar.tall { height: 10px; }
    .rows { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }
    .row { display: flex; justify-content: space-between; gap: 12px; font-size: 14px; }
    .row-name { min-width: 0; overflow-wrap: anywhere; }
    .row.free { border-top: 1px solid var(--rule); padding-top: 6px; font-weight: 700; }
    .row-note { margin-top: 4px; font-size: 12px; color: var(--muted); text-wrap: pretty; }
    .muted-fg { color: var(--ink-soft); }
    .bar > span.full { background: var(--dhl-red); }
    .blocked {
      display: grid;
      grid-template-columns: 4px 1fr;
      gap: 16px;
      border-radius: 4px;
      background: var(--chip-warn-bg);
      overflow: hidden;
    }
    .blocked-bar { background: var(--chip-warn-fg); }
    .blocked-body { padding: 16px 16px 16px 0; display: flex; flex-direction: column; gap: 6px; }
    .blocked-title { font-weight: 700; font-size: 15px; }
    .blocked-text { font-size: 14px; color: var(--ink-soft); max-width: 74ch; text-wrap: pretty; overflow-wrap: anywhere; }
    .disclosure { margin: 0; font-size: 14px; color: var(--ink-soft); max-width: 74ch; text-wrap: pretty; }
  `
})
export class Constraints {
  private readonly clusters = inject(ClusterService);
  private readonly loads = inject(LoadService);

  readonly cluster = this.clusters.cluster;

  readonly quota = computed(() => this.cluster()?.quota ?? null);

  /**
   * A run that exists, has not started, and has a reason — which is the only
   * state worth interrupting the panel for.
   *
   * A Pending run with no reason yet is not shown here: for the first second or
   * two after a Job is created the scheduler has not written anything down, and
   * a heading saying so with an empty sentence under it would be worse than
   * waiting.
   */
  readonly blocked = computed(() => {
    const run = this.loads.run();
    if (!run || run.phase !== 'PENDING' || !run.message) {
      return null;
    }
    return { reason: run.reason ?? 'Pending', message: run.message, cpuMillis: run.cpuMillis };
  });

  readonly freeText = computed(() => {
    const quota = this.quota();
    return quota
      ? `The ${quota.name} quota has ${quota.freeMillis}m of ${quota.hardMillis}m free.`
      : '';
  });

  percentOf(current: number, max: number): number {
    return max <= 0 ? 0 : Math.min(100, Math.round((current / max) * 100));
  }

  utilisation(current: number | null, target: number | null): string {
    if (current === null || target === null) {
      return 'no cpu metric collected yet';
    }
    return `cpu ${current}% of a ${target}% target`;
  }
}

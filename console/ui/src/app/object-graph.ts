import { Component, computed, inject } from '@angular/core';
import { ClusterService } from './cluster.service';
import { StateService } from './state.service';

/**
 * The Kubernetes objects behind the simulation, and what each one owns.
 *
 * The table above this lists pods. This says why those pods exist: which Route
 * publishes what, which Service fronts which Deployment, and which object
 * governs each row. Read left to right it is the chain from a request to a
 * container; read down the right-hand column it is what controls each workload.
 *
 * Two things are visible here that a list of pods cannot show. booking-service
 * and redis have no Route at all - they are reachable only from inside the
 * namespace, and the empty cells say so - and both carry a NetworkPolicy that
 * narrows that further to queue-gate alone.
 *
 * Live where it can be: pod counts and HPA positions come from the same read
 * the table uses, so they move when the cluster moves. Anything unreadable
 * shows a dash rather than the manifest's number, because what is declared and
 * what is running are different claims.
 */
@Component({
  selector: 'rb-object-graph',
  template: `
    <div class="frame">
      <svg viewBox="0 0 1000 520" role="img" [attr.aria-label]="summary()">
        <defs>
          <marker id="rb-og-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 z" fill="var(--muted)" />
          </marker>
        </defs>

        <g>
          <rect class="zone" x="150" y="10" width="842" height="500" rx="8" />
          <text class="zone-label" x="166" y="34">{{ zoneLabel() }}</text>
        </g>

        <!-- y=52, below the namespace label at y=34, which shares this corner. -->
        <g class="col-heads">
          <text x="176" y="52">ROUTE</text>
          <text x="356" y="52">SERVICE</text>
          <text x="556" y="52">DEPLOYMENT</text>
          <text x="786" y="52">GOVERNED BY</text>
        </g>

        <g class="edges">
          @for (edge of edges; track edge) {
            <path [attr.d]="edge" />
          }
        </g>

        @for (box of boxes(); track box.id) {
          <g [class]="'box ' + box.tone">
            <rect [attr.x]="box.x" [attr.y]="box.y"
                  [attr.width]="box.w" [attr.height]="box.h" rx="6" />
            <text class="label" [attr.x]="box.x + 12" [attr.y]="box.y + 24">{{ box.label }}</text>
            @if (box.sub) {
              <text class="sub" [attr.x]="box.x + 12" [attr.y]="box.y + 44">{{ box.sub }}</text>
            }
          </g>
        }

        <!--
          Not a box, because there is no object here. An empty cell with a word
          is the point: nothing publishes these two.
        -->
        @for (gap of gaps; track gap.y) {
          <text class="absent" [attr.x]="gap.x" [attr.y]="gap.y">not published</text>
        }
      </svg>
    </div>
  `,
  styles: `
    .frame { width: 100%; overflow-x: auto; }
    svg { width: 100%; min-width: 860px; height: auto; display: block; }

    .zone { fill: var(--canvas); stroke: var(--muted); stroke-dasharray: 5 4; }
    .zone-label { font-family: var(--mono); font-size: 11px; letter-spacing: .06em; fill: var(--muted); }

    .col-heads text {
      font-family: var(--mono);
      font-size: 10px;
      letter-spacing: .1em;
      fill: var(--muted);
    }

    .edges path { fill: none; stroke: var(--muted); stroke-width: 2; marker-end: url(#rb-og-arrow); }

    .box rect { fill: var(--white); stroke: var(--line); stroke-width: 1; }
    .box.store rect { fill: var(--canvas); stroke-dasharray: 4 3; }
    .box.outside rect { stroke: var(--ink); stroke-dasharray: 4 3; }
    .box.guard rect { fill: var(--chip-info-bg); stroke: var(--chip-info-fg); }

    .label { font-family: var(--mono); font-size: 12px; font-weight: 700; fill: var(--ink); }
    .sub { font-size: 11px; fill: var(--muted); }
    .absent { font-size: 11px; font-style: italic; fill: var(--muted); }
  `
})
export class ObjectGraph {
  private readonly clusterService = inject(ClusterService);
  private readonly state = inject(StateService);

  private static readonly ROWS = [68, 156, 244, 332];
  private static readonly H = 64;

  /** Row centres, for anything that has to meet a box edge. */
  private static cy(row: number): number {
    return ObjectGraph.ROWS[row] + ObjectGraph.H / 2;
  }

  readonly edges = [
    // Anyone -> the two published Routes.
    `M128 226 H152 V${ObjectGraph.cy(0)} H176`,
    `M128 226 H152 V${ObjectGraph.cy(1)} H176`,
    // Route -> Service, for the two rows that have a Route.
    `M316 ${ObjectGraph.cy(0)} H356`,
    `M316 ${ObjectGraph.cy(1)} H356`,
    // Service -> Deployment, all four.
    ...[0, 1, 2, 3].map((r) => `M516 ${ObjectGraph.cy(r)} H556`),
    // Deployment -> the object that governs it.
    ...[0, 1, 2, 3].map((r) => `M746 ${ObjectGraph.cy(r)} H786`)
  ];

  /** The two rows nothing publishes. */
  readonly gaps = [
    { x: 200, y: ObjectGraph.cy(2) + 4 },
    { x: 200, y: ObjectGraph.cy(3) + 4 }
  ];

  private readonly cluster = computed(() => this.clusterService.cluster());
  private readonly pods = computed(() => this.state.view()?.pods ?? null);

  private podCount(deployment: string): string {
    const pods = this.pods();
    if (!pods?.available) {
      return '—';
    }
    const mine = pods.pods.filter((p) => p.name.startsWith(deployment + '-'));
    return `${mine.length} ${mine.length === 1 ? 'pod' : 'pods'}`;
  }

  private hpa(name: string): string {
    const cluster = this.cluster();
    if (!cluster?.available) {
      return '—';
    }
    const scaler = cluster.autoscalers.find((a) => a.name === name);
    return scaler ? `${scaler.current} of ${scaler.min}–${scaler.max}` : '—';
  }

  protected readonly zoneLabel = computed(() => {
    const quota = this.cluster()?.quota;
    return quota
      ? `NAMESPACE · ${quota.percent}% OF ${quota.hardMillis}m CPU IN USE`
      : 'NAMESPACE';
  });

  protected readonly boxes = computed(() => {
    const rows = ObjectGraph.ROWS;
    const h = ObjectGraph.H;
    return [
      { id: 'anyone', x: 8, y: 196, w: 120, h: 60, tone: 'outside',
        label: 'Anyone', sub: 'the internet' },

      { id: 'r-console', x: 176, y: rows[0], w: 140, h, tone: 'plain',
        label: 'Route', sub: 'console' },
      { id: 'r-gate', x: 176, y: rows[1], w: 140, h, tone: 'plain',
        label: 'Route', sub: 'queue-gate' },

      { id: 's-console', x: 356, y: rows[0], w: 160, h, tone: 'plain',
        label: 'Service', sub: 'console:8080' },
      { id: 's-gate', x: 356, y: rows[1], w: 160, h, tone: 'plain',
        label: 'Service', sub: 'queue-gate:8080' },
      { id: 's-booking', x: 356, y: rows[2], w: 160, h, tone: 'plain',
        label: 'Service', sub: 'booking-service:8081' },
      { id: 's-redis', x: 356, y: rows[3], w: 160, h, tone: 'store',
        label: 'Service', sub: 'redis:6379' },

      { id: 'd-console', x: 556, y: rows[0], w: 190, h, tone: 'plain',
        label: 'console', sub: this.podCount('console') },
      { id: 'd-gate', x: 556, y: rows[1], w: 190, h, tone: 'plain',
        label: 'queue-gate', sub: this.podCount('queue-gate') },
      { id: 'd-booking', x: 556, y: rows[2], w: 190, h, tone: 'plain',
        label: 'booking-service', sub: this.podCount('booking-service') },
      { id: 'd-redis', x: 556, y: rows[3], w: 190, h, tone: 'store',
        label: 'redis', sub: this.podCount('redis') },

      { id: 'g-console', x: 786, y: rows[0], w: 190, h, tone: 'guard',
        label: 'ServiceAccount', sub: 'reads this namespace' },
      { id: 'g-gate', x: 786, y: rows[1], w: 190, h, tone: 'guard',
        label: 'HPA', sub: this.hpa('queue-gate') },
      { id: 'g-booking', x: 786, y: rows[2], w: 190, h, tone: 'guard',
        label: 'HPA + NetworkPolicy', sub: this.hpa('booking-service') },
      { id: 'g-redis', x: 786, y: rows[3], w: 190, h, tone: 'guard',
        label: 'NetworkPolicy', sub: 'from queue-gate only' },

      // Namespace-wide, so along the bottom rather than on a row.
      { id: 'cm', x: 176, y: 420, w: 180, h: 56, tone: 'plain',
        label: 'ConfigMap', sub: 'queue-gate-config' },
      { id: 'sm', x: 376, y: 420, w: 180, h: 56, tone: 'plain',
        label: 'ServiceMonitor', sub: 'scrapes :9090' },
      { id: 'pr', x: 576, y: 420, w: 180, h: 56, tone: 'plain',
        label: 'PrometheusRule', sub: 'alerts on oversold' },
      { id: 'cj', x: 776, y: 420, w: 200, h: 56, tone: 'plain',
        label: 'CronJob', sub: 'keepalive, 3x a day' }
    ];
  });

  protected readonly summary = computed(() =>
    'Two Routes publish console and queue-gate. Four Services front four '
    + 'Deployments; booking-service and redis have no Route and are reachable '
    + 'only from queue-gate. Autoscalers govern queue-gate and booking-service, '
    + 'and a ConfigMap, ServiceMonitor, PrometheusRule and CronJob apply across '
    + 'the namespace.'
  );
}

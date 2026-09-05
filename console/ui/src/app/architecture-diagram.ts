import { Component, computed, inject } from '@angular/core';
import { StateService } from './state.service';

/**
 * What actually runs, as deployed.
 *
 * The companion to the flow diagram: that one is the path a request takes,
 * this one is the things the path runs on. Same drawing vocabulary, so the two
 * read as one idea - a dashed box for the namespace, solid boxes for what runs
 * inside it, dashed-edged boxes for the stores, and anything outside the dashed
 * box is genuinely outside the cluster.
 *
 * The pod counts are real. They are counted from the same pod list the cluster
 * page reads, so a replica that has just been scaled up appears here, and a
 * count that cannot be read shows a dash rather than the number from the
 * manifest - what is declared and what is running are different claims, and
 * only one of them is worth drawing.
 */
@Component({
  selector: 'rb-architecture-diagram',
  template: `
    <div class="frame">
      <svg viewBox="0 0 1000 330" role="img" [attr.aria-label]="summary()">
        <defs>
          <marker id="rb-arch-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 z" fill="var(--muted)" />
          </marker>
        </defs>

        <!--
          The Route is INSIDE this box. A Route is a namespaced object, and
          drawing it outside said the boundary was the cluster edge when it is
          the namespace edge. What is genuinely outside is the internet on one
          side and Oracle on the other.
        -->
        <g>
          <rect class="zone" x="186" y="10" width="592" height="310" rx="8" />
          <text class="zone-label" x="202" y="34">OPENSHIFT NAMESPACE · 3000m CPU QUOTA</text>
        </g>

        <!--
          Every path runs between two box edges at their real centre lines,
          computed from the same coordinates the boxes are drawn at rather than
          eyeballed - a connector that stops in white space is the fastest way
          to make a diagram look wrong.
        -->
        <g class="edges">
          <!-- Everything public enters through one Route ... -->
          <path d="M158 155 H206" />
          <!-- ... which serves the page ... -->
          <path d="M356 155 H371 V73 H386" />
          <!-- ... and the queue. -->
          <path d="M356 155 H371 V233 H386" />
          <!-- The gate keeps the line in Redis and admits to booking-service. -->
          <path d="M566 233 H581 V73 H596" />
          <path d="M566 233 H596" />
          <!-- booking-service is the only thing that reaches Oracle. -->
          <path d="M766 233 H792 V155 H818" />
        </g>

        @for (box of boxes(); track box.id) {
          <g [class]="'box ' + box.tone">
            <rect [attr.x]="box.x" [attr.y]="box.y"
                  [attr.width]="box.w" [attr.height]="box.h" rx="6" />
            <text class="label" [attr.x]="box.x + 14" [attr.y]="box.y + 26">{{ box.label }}</text>
            @if (box.value) {
              <text class="value" [attr.x]="box.x + 14" [attr.y]="box.y + 52">{{ box.value }}</text>
            }
            @if (box.sub) {
              <text class="sub" [attr.x]="box.x + 14"
                    [attr.y]="box.y + (box.value ? 72 : 46)">{{ box.sub }}</text>
            }
          </g>
        }
      </svg>
    </div>
  `,
  styles: `
    .frame { width: 100%; overflow-x: auto; }
    svg { width: 100%; min-width: 760px; height: auto; display: block; }

    .zone { fill: var(--canvas); stroke: var(--muted); stroke-dasharray: 5 4; }
    .zone-label { font-family: var(--mono); font-size: 11px; letter-spacing: .06em; fill: var(--muted); }

    .edges path { fill: none; stroke: var(--muted); stroke-width: 2; marker-end: url(#rb-arch-arrow); }

    .box rect { fill: var(--white); stroke: var(--line); stroke-width: 1; }
    .box.store rect { fill: var(--canvas); stroke-dasharray: 4 3; }
    .box.outside rect { fill: var(--white); stroke: var(--ink); stroke-dasharray: 4 3; }

    .label { font-family: var(--mono); font-size: 13px; font-weight: 700; fill: var(--ink); }
    .value { font-family: var(--mono); font-size: 17px; font-weight: 700; fill: var(--ink); }
    .sub { font-size: 12px; fill: var(--muted); }
  `
})
export class ArchitectureDiagram {
  private readonly state = inject(StateService);

  private readonly pods = computed(() => this.state.view()?.pods ?? null);

  /**
   * Pods whose name starts with the deployment's, ready ones counted separately.
   * A pod is named <deployment>-<replicaset>-<suffix>, so the prefix is the
   * deployment - and during a rollout both generations match, which is correct:
   * they are all running.
   */
  private count(deployment: string): string {
    const pods = this.pods();
    if (!pods?.available) {
      return '—';
    }
    const mine = pods.pods.filter((p) => p.name.startsWith(deployment + '-'));
    if (!mine.length) {
      return '0 pods';
    }
    const ready = mine.filter((p) => p.healthy).length;
    const noun = mine.length === 1 ? 'pod' : 'pods';
    return ready === mine.length
      ? `${mine.length} ${noun}`
      : `${ready}/${mine.length} ${noun}`;
  }

  protected readonly boxes = computed(() => [
    {
      id: 'internet', x: 8, y: 118, w: 150, h: 74, tone: 'outside',
      label: 'Anyone', value: '', sub: 'the public internet'
    },
    {
      id: 'route', x: 206, y: 118, w: 150, h: 74, tone: 'plain',
      label: 'Route', value: '', sub: '8080 only'
    },
    {
      id: 'console', x: 386, y: 36, w: 180, h: 74, tone: 'plain',
      label: 'console', value: this.count('console'), sub: 'this page'
    },
    {
      id: 'gate', x: 386, y: 196, w: 180, h: 74, tone: 'plain',
      label: 'queue-gate', value: this.count('queue-gate'), sub: 'scales to 10'
    },
    {
      id: 'redis', x: 596, y: 36, w: 170, h: 74, tone: 'store',
      label: 'redis', value: this.count('redis'), sub: 'the queue'
    },
    {
      id: 'booking', x: 596, y: 196, w: 170, h: 74, tone: 'plain',
      label: 'booking-service', value: this.count('booking-service'), sub: 'scales to 4'
    },
    {
      id: 'oracle', x: 818, y: 118, w: 174, h: 74, tone: 'outside',
      label: 'Oracle', value: '', sub: 'Autonomous, a region away'
    }
  ]);

  protected readonly summary = computed(() =>
    'Public traffic enters the namespace through one Route on port 8080, which '
    + 'serves the console and queue-gate. queue-gate keeps its queue in Redis and '
    + 'admits to booking-service, the only thing that reaches Oracle - which runs '
    + 'outside the cluster.'
  );
}

import { Component, computed, inject } from '@angular/core';
import { StateService } from './state.service';

/**
 * How a booking is made, drawn rather than described.
 *
 * The page used to explain this in five paragraphs. What is worth understanding
 * is a shape - everyone arrives at one door, a gate lets a few through at a
 * time, and the seat is taken under a lock in a single row - and a shape is
 * seen rather than read. Every box carries a name, at most one number and at
 * most one short line.
 *
 * One SVG in a fixed 1000x300 coordinate space rather than positioned HTML: at
 * any container width everything scales together, so the boxes, the connectors
 * and the type can never drift out of alignment with each other. Below 760px
 * the frame scrolls sideways instead of shrinking the type past reading.
 *
 * Drawn by hand rather than by a diagram library. ng-diagram was the obvious
 * candidate and was tried: it renders a permanent watermark linking to its own
 * site with no licence key to remove it, costs about 310kB, and its interactive
 * editing - the reason to take a dependency at all - has to be switched off
 * here anyway, because a visitor who drags a box has broken the picture with no
 * way back and a canvas that eats the wheel traps the page.
 *
 * The numbers are live, from the same state the dashboard reads. They show
 * dashes when no simulation is open: a zero would be a claim about a run that
 * is not happening.
 */
@Component({
  selector: 'rb-flow-diagram',
  template: `
    <div class="frame">
      <svg viewBox="0 0 1000 300" role="img"
           [attr.aria-label]="summary()">
        <defs>
          <marker id="rb-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 z" fill="var(--muted)" />
          </marker>
        </defs>

        <!-- The namespace, drawn first so everything else sits on top of it. -->
        <g>
          <rect class="zone" x="232" y="10" width="470" height="280" rx="8" />
          <text class="zone-label" x="248" y="34">OPENSHIFT NAMESPACE</text>
        </g>

        <!--
          Connectors before boxes, so a path that runs slightly under a corner
          is covered by the box rather than drawn across it.
        -->
        <g class="edges">
          <path id="rb-e1" d="M198 116 H262" />
          <!--
            Stated in text as well as shown in dots. Reduced motion hides the
            dots, and without this the nine-to-one ratio - the reason a queue
            exists at all - would go with them.
          -->
          <text class="hop" x="230" y="100">~3,000</text>
          <text class="hop" x="479" y="100">8/s</text>
          <path id="rb-e2" d="M355 162 V212" />
          <path id="rb-e3" d="M448 116 H510" />
          <path id="rb-e4" d="M696 116 H758" />
          <path id="rb-e5" d="M851 162 V212" />
        </g>

        <!--
          The dots are the one thing that has to be noticed: nine crossing the
          first hop for every one crossing the rest. That ratio is the whole
          argument for a queue, and it is made without a sentence.
        -->
        <g class="dots" aria-hidden="true">
          @for (delay of flood; track delay) {
            <circle class="dot flood" r="4">
              <animateMotion dur="0.9s" repeatCount="indefinite" [attr.begin]="delay + 's'">
                <mpath href="#rb-e1" />
              </animateMotion>
            </circle>
          }
          @for (edge of trickleEdges; track edge.path) {
            @for (delay of trickle; track delay) {
              <circle class="dot trickle" r="4">
                <animateMotion dur="2.6s" repeatCount="indefinite" [attr.begin]="delay + 's'">
                  <mpath [attr.href]="edge.path" />
                </animateMotion>
              </circle>
            }
          }
        </g>

        @for (box of boxes(); track box.id) {
          <g [class]="'box ' + box.tone">
            <rect [attr.x]="box.x" [attr.y]="box.y"
                  [attr.width]="box.w" [attr.height]="box.h" rx="6" />
            <text class="label" [attr.x]="box.x + 14" [attr.y]="box.y + 26">{{ box.label }}</text>
            @if (box.value) {
              <!-- Sits higher in a box with no sub line, or it clips the edge. -->
              <text class="value" [class.good]="box.good"
                    [attr.x]="box.x + 14"
                    [attr.y]="box.y + (box.sub ? 56 : 48)">{{ box.value }}</text>
            }
            @if (box.sub) {
              <text class="sub" [attr.x]="box.x + 14"
                    [attr.y]="box.y + (box.value ? 76 : 46)">{{ box.sub }}</text>
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
    .zone-label {
      font-family: var(--mono);
      font-size: 11px;
      letter-spacing: .06em;
      fill: var(--muted);
    }

    .edges path { fill: none; stroke: var(--muted); stroke-width: 2; marker-end: url(#rb-arrow); }

    .hop {
      font-family: var(--mono);
      font-size: 11px;
      letter-spacing: .04em;
      fill: var(--muted);
      text-anchor: middle;
    }

    .dot.flood { fill: var(--dhl-red); }
    .dot.trickle { fill: var(--ink); }

    .box rect { fill: var(--white); stroke: var(--line); stroke-width: 1; }
    .box.store rect { fill: var(--canvas); stroke-dasharray: 4 3; }
    .box.dark rect { fill: var(--ink); stroke: var(--ink); }

    .label { font-family: var(--mono); font-size: 13px; font-weight: 700; fill: var(--ink); }
    .value { font-family: var(--mono); font-size: 20px; font-weight: 700; fill: var(--ink); }
    .value.good { fill: var(--chip-ok-fg); }
    .sub { font-size: 12px; fill: var(--muted); }

    .box.dark .label, .box.dark .value { fill: var(--white); }
    .box.dark .value.good { fill: #7BD69C; }
    .box.dark .sub { fill: var(--on-dark-soft); }

    /*
      The dots reinforce the ratio; the hop labels state it. Reduced motion
      drops the dots and keeps the labels, so nothing is lost but the movement.
    */
    @media (prefers-reduced-motion: reduce) {
      .dot { display: none; }
    }
  `
})
export class FlowDiagram {
  private readonly state = inject(StateService);

  /** Staggered starts, so the dots space themselves along the hop. */
  readonly flood = [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8];
  readonly trickle = [0, 1.3];
  readonly trickleEdges = [
    { path: '#rb-e2' },
    { path: '#rb-e3' },
    { path: '#rb-e4' },
    { path: '#rb-e5' }
  ];

  private readonly drop = computed(() => this.state.view()?.drop ?? null);
  private readonly live = computed(() => this.drop()?.available === true);

  private reading(value: number | undefined): string {
    return this.live() && value !== undefined ? value.toLocaleString() : '—';
  }

  protected readonly boxes = computed(() => {
    const drop = this.drop();
    const live = this.live();
    return [
      {
        id: 'arrivals', x: 12, y: 78, w: 186, h: 92, tone: 'plain',
        label: 'Arrivals', value: this.reading(drop?.ticketsIssued),
        sub: 'everyone, in one second', good: false
      },
      {
        id: 'gate', x: 262, y: 78, w: 186, h: 92, tone: 'plain',
        label: 'queue-gate', value: this.reading(drop?.waiting),
        sub: 'waiting in line', good: false
      },
      {
        id: 'redis', x: 262, y: 212, w: 186, h: 62, tone: 'store',
        label: 'Redis', value: '', sub: 'the line itself', good: false
      },
      {
        id: 'booking', x: 510, y: 78, w: 186, h: 92, tone: 'plain',
        label: 'booking-service', value: this.reading(drop?.admitted),
        sub: '8 a second, in order', good: false
      },
      {
        id: 'oracle', x: 758, y: 78, w: 186, h: 92, tone: 'plain',
        label: 'Oracle',
        value: live && drop ? `${drop.seatsTaken} / ${drop.capacity}` : '—',
        sub: 'one row, one lock', good: false
      },
      {
        id: 'oversold', x: 758, y: 212, w: 186, h: 62, tone: 'dark',
        label: 'Oversold',
        value: live && drop ? String(drop.oversold) : '—',
        sub: '', good: live && drop?.oversold === 0
      }
    ];
  });

  /** The same argument in one sentence, for anyone not reading the picture. */
  protected readonly summary = computed(() =>
    'Arrivals reach queue-gate, which holds the line in Redis and admits eight a '
    + 'second to booking-service, which takes one seat per booking from a single '
    + 'locked Oracle row. Oversold stays at zero.'
  );
}

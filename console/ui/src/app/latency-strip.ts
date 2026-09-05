import { Component, computed, inject } from '@angular/core';
import { TrafficService } from './traffic.service';

/**
 * Why one a second, drawn.
 *
 * The counters say the queue drains at about one customer a second and the
 * number looks arbitrary. It is not: a booking crosses the gate in milliseconds,
 * then takes a pessimistic lock on the sitting's row and holds it across a round
 * trip to an Oracle instance in another region. Every other booking for that
 * sitting waits behind it. The rate is that round trip.
 *
 * So the dot here crosses the first two hops quickly and then visibly stops on
 * the database, which is the whole of the explanation. A stalled dot on a page
 * usually means something is broken; this one is labelled, because here it is
 * the design working and it is the reason for every number beside it.
 *
 * Paused when nothing is running, like the diagram upstairs — an idle page that
 * animates is telling you something is happening when nothing is.
 */
@Component({
  selector: 'rb-latency-strip',
  template: `
    <div class="strip" [class.flowing]="flowing()">
      <svg viewBox="0 0 1000 96" role="img" [attr.aria-label]="summary">
        <defs>
          <marker id="rb-lat-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="5" markerHeight="5" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 z" fill="var(--muted)" />
          </marker>
        </defs>

        <g class="rails">
          <path d="M150 44 H330" />
          <path d="M470 44 H650" />
          <path d="M790 44 H900" />
        </g>

        <!--
          One dot on one path, holding on the database leg. Several dots would
          say the hops run in parallel; they do not, and that is the point.
        -->
        <circle class="dot" r="5" aria-hidden="true">
          <animateMotion dur="4.2s" repeatCount="indefinite"
                         keyPoints="0;0.30;0.34;0.38;1"
                         keyTimes="0;0.12;0.16;0.80;1"
                         calcMode="linear">
            <mpath href="#rb-lat-track" />
          </animateMotion>
        </circle>
        <path id="rb-lat-track" d="M150 44 H900" fill="none" stroke="none" />

        @for (box of boxes; track box.label) {
          <g class="box">
            <rect [attr.x]="box.x" y="16" [attr.width]="box.w" height="56" rx="6"
                  [class.wait]="box.wait" />
            <text class="name mono" [attr.x]="box.x + box.w / 2" y="42">{{ box.label }}</text>
            <text class="cost mono" [attr.x]="box.x + box.w / 2" y="60">{{ box.cost }}</text>
          </g>
        }
      </svg>
      <p class="note">
        The gate is milliseconds. The seat is a lock on one row held across a round trip to
        Oracle in another region — every booking for this sitting queues behind it, and that
        round trip <strong>is</strong> the one-a-second rate.
      </p>
    </div>
  `,
  styles: `
    .strip { border-bottom: 1px solid var(--line); padding: 14px 16px 12px; }
    svg { width: 100%; min-width: 620px; height: auto; display: block; }
    .strip { overflow-x: auto; }

    .rails path { fill: none; stroke: var(--line); stroke-width: 2; marker-end: url(#rb-lat-arrow); }

    .box rect { fill: var(--white); stroke: var(--line); stroke-width: 1; }
    .box rect.wait { fill: var(--chip-warn-bg); stroke: var(--chip-warn-fg); }
    .name { font-size: 13px; font-weight: 700; fill: var(--ink); text-anchor: middle; }
    .cost { font-size: 11px; fill: var(--muted); text-anchor: middle; }

    .dot { fill: var(--dhl-red); animation-play-state: paused; opacity: .25; }
    .flowing .dot { opacity: 1; }
    /* SMIL is not CSS animation, so pausing it needs the element's own switch. */
    .strip:not(.flowing) .dot { display: none; }

    .note {
      margin: 8px 0 0;
      font-size: 13px;
      color: var(--ink-soft);
      max-width: 90ch;
      text-wrap: pretty;
    }

    @media (prefers-reduced-motion: reduce) { .dot { display: none; } }
  `
})
export class LatencyStrip {
  private readonly traffic = inject(TrafficService);

  protected readonly flowing = computed(() => this.traffic.flowing());

  protected readonly boxes = [
    { x: 30, w: 120, label: 'queue-gate', cost: 'milliseconds', wait: false },
    { x: 330, w: 140, label: 'booking-service', cost: 'milliseconds', wait: false },
    { x: 650, w: 140, label: 'row lock', cost: 'held', wait: true },
    { x: 900, w: 70, label: 'Oracle', cost: '~1s', wait: true }
  ];

  protected readonly summary =
    'A booking crosses queue-gate and booking-service in milliseconds, then holds a lock on '
    + 'one row across a round trip to Oracle, which is why the sitting fills at about one '
    + 'booking a second.';
}

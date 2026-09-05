import { Component, computed, inject } from '@angular/core';
import { StateService } from './state.service';

/**
 * The request path, drawn rather than described.
 *
 * The page used to explain this in five paragraphs. The thing worth
 * understanding is a contrast — a flood arrives and a trickle leaves — and a
 * contrast in rates is something you see in one second and read in thirty. So
 * the lanes between the boxes carry moving dots, and the two lanes differ: nine
 * dots a second into the gate, one every 2.6s out of it. That ratio is the
 * design, and it is the only part of this component that has to be noticed.
 *
 * The numbers are live where there is a drop to read and dashes where there is
 * not. The animation is not: it runs at a fixed illustrative rate, because a
 * diagram that stops moving when nobody is running a simulation reads as broken
 * rather than idle.
 */
@Component({
  selector: 'rb-flow-diagram',
  template: `
    <div class="flow">
      <div class="node">
        <div class="row">
          <span class="name mono">Arrivals</span>
          <span class="metric mono">{{ ticketsIssued() }}</span>
        </div>
        <div class="caption">everyone, in the same second</div>
      </div>

      <!--
        aria-hidden throughout: the dots are the argument made visually, and a
        screen reader gets the same argument from the lane labels and the
        metrics, which are real text.
      -->
      <div class="lane flood" aria-hidden="true">
        <span class="rail"></span>
        @for (dot of flood; track dot) {
          <span class="dot" [style.animation-delay.ms]="dot"></span>
        }
        <span class="lane-label mono">~3,000 at once</span>
      </div>

      <div class="node">
        <div class="row">
          <span class="name mono">queue-gate</span>
          <span class="metric mono">{{ waiting() }}</span>
        </div>
        <div class="caption">holds the line in Redis</div>
      </div>

      <div class="lane trickle" aria-hidden="true">
        <span class="rail"></span>
        @for (dot of trickle; track dot) {
          <span class="dot" [style.animation-delay.ms]="dot"></span>
        }
        <span class="lane-label mono">8 a second, in order</span>
      </div>

      <div class="node">
        <div class="row">
          <span class="name mono">booking-service</span>
          <span class="metric mono">{{ admitted() }}</span>
        </div>
        <div class="caption">one seat each, under a lock</div>
      </div>

      <div class="lane trickle" aria-hidden="true">
        <span class="rail"></span>
        @for (dot of trickle; track dot) {
          <span class="dot" [style.animation-delay.ms]="dot"></span>
        }
        <span class="lane-label mono">one update per claim</span>
      </div>

      <div class="node">
        <div class="row">
          <span class="name mono">Oracle</span>
          <span class="metric mono">{{ seats() }}</span>
        </div>
        <div class="caption">the limit lives here, not above it</div>
      </div>

      <div class="lane trickle" aria-hidden="true">
        <span class="rail"></span>
        @for (dot of trickle; track dot) {
          <span class="dot" [style.animation-delay.ms]="dot"></span>
        }
      </div>

      <div class="node claim">
        <div class="row">
          <span class="name mono">Oversold</span>
          <span
            class="metric mono"
            [class.good]="live() && oversold() === 0"
            [class.bad]="live() && oversold() !== 0">{{ live() ? oversold() : '—' }}</span>
        </div>
        <div class="caption">try to move it</div>
      </div>
    </div>
  `,
  styles: `
    .flow { display: flex; flex-direction: column; }

    .node {
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      padding: 12px 14px;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 2px 12px;
    }
    .name { font-size: 14px; font-weight: 700; letter-spacing: .02em; }
    .metric { font-size: 15px; font-weight: 700; color: var(--ink); }
    .caption { font-size: 12px; color: var(--muted); margin-top: 3px; text-wrap: pretty; }

    .claim { background: var(--ink); border-color: var(--ink); color: var(--white); }
    .claim .metric { color: var(--white); }
    .claim .caption { color: var(--on-dark-soft); }
    .metric.good { color: var(--chip-ok-fg); }
    .metric.bad { color: var(--chip-bad-fg); }

    /* The lane between two boxes: a rail, the dots moving down it, and a label. */
    .lane {
      position: relative;
      height: 46px;
      margin-left: 20px;
      padding-left: 16px;
      display: flex;
      align-items: center;
    }
    .rail {
      position: absolute;
      left: 0;
      top: 0;
      bottom: 0;
      width: 2px;
      background: var(--line);
    }
    .lane-label { font-size: 11px; letter-spacing: .04em; color: var(--muted); }

    .dot {
      position: absolute;
      left: -2px;
      top: 0;
      width: 6px;
      height: 6px;
      border-radius: 999px;
      background: var(--dhl-red);
      animation-name: fall;
      animation-timing-function: linear;
      animation-iteration-count: infinite;
      /*
        backwards, or a staggered dot shows its own styles during its delay
        instead of the first keyframe - nine of them sitting opaque at the top of
        the rail, which reads as a solid bar rather than as movement.
      */
      animation-fill-mode: backwards;
    }
    /* Nine dots at 900ms against two at 2600ms: the ratio is the whole point. */
    .flood .dot { animation-duration: 900ms; }
    .trickle .dot { animation-duration: 2600ms; background: var(--ink); }

    @keyframes fall {
      from { transform: translateY(-4px); opacity: 0; }
      12%  { opacity: 1; }
      88%  { opacity: 1; }
      to   { transform: translateY(46px); opacity: 0; }
    }

    /*
      Motion is the argument here, so with reduced motion the dots stop rather
      than the diagram losing its meaning: they hold their positions down the
      rail, which still shows nine against two.
    */
    @media (prefers-reduced-motion: reduce) {
      .dot { animation: none; opacity: 1; }
      .flood .dot:nth-child(2)  { top: 2px; }
      .flood .dot:nth-child(3)  { top: 7px; }
      .flood .dot:nth-child(4)  { top: 12px; }
      .flood .dot:nth-child(5)  { top: 17px; }
      .flood .dot:nth-child(6)  { top: 22px; }
      .flood .dot:nth-child(7)  { top: 27px; }
      .flood .dot:nth-child(8)  { top: 32px; }
      .flood .dot:nth-child(9)  { top: 37px; }
      .flood .dot:nth-child(10) { top: 42px; }
      .trickle .dot:nth-child(2) { top: 8px; }
      .trickle .dot:nth-child(3) { top: 30px; }
    }
  `
})
export class FlowDiagram {
  private readonly state = inject(StateService);

  /** Staggered start times, so the dots space themselves down the rail. */
  readonly flood = [0, 100, 200, 300, 400, 500, 600, 700, 800];
  readonly trickle = [0, 1300];

  private readonly drop = computed(() => this.state.view()?.drop ?? null);

  /** Whether there is a drop to read. Dashes are honest; zeroes would not be. */
  readonly live = computed(() => this.drop()?.available === true);

  readonly ticketsIssued = computed(() => this.text(this.drop()?.ticketsIssued));
  readonly waiting = computed(() => this.text(this.drop()?.waiting));
  readonly admitted = computed(() => this.text(this.drop()?.admitted));
  readonly oversold = computed(() => this.drop()?.oversold ?? 0);

  readonly seats = computed(() => {
    const drop = this.drop();
    return this.live() && drop ? `${drop.seatsTaken} / ${drop.capacity}` : '—';
  });

  private text(value: number | undefined): string {
    return this.live() && value !== undefined ? value.toLocaleString() : '—';
  }
}

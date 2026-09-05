import { Component, computed, inject } from '@angular/core';
import { LoadService } from './load.service';
import { StateService } from './state.service';

/**
 * A strip that says whether anything is happening, and what.
 *
 * A run takes about a minute and the interesting part is spread across the page:
 * the controls are at the top, the counters below them, the log below that. Miss
 * the moment it starts and there is nothing telling you a rush is in flight - so
 * this stays put and says so, with what the run itself is doing. It deliberately
 * does not repeat waiting, admitted or seats: those are the cards immediately
 * below it, and saying them here made one number appear three times on one page.
 *
 * It renders nothing at all when there is no simulation. A banner that is always
 * on screen stops being read.
 */
@Component({
  selector: 'rb-run-banner',
  template: `
    @if (phase(); as p) {
      <div class="strip" [class]="p.tone">
        <span class="pip" [class.beat]="p.live"></span>
        <span class="what">{{ p.title }}</span>
        @if (p.detail) {
          <span class="detail">{{ p.detail }}</span>
        }
        @if (p.counts.length) {
          <span class="counts">
            @for (c of p.counts; track c.label) {
              <span class="count"><b class="mono">{{ c.value }}</b> {{ c.label }}</span>
            }
          </span>
        }
      </div>
    }
  `,
  styles: `
    .strip {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      padding: 12px 16px;
      border: 1px solid var(--line);
      border-left: 4px solid var(--muted);
      border-radius: 4px;
      background: var(--white);
      font-size: 14px;
    }
    .strip.running { border-left-color: var(--chip-ok-fg); background: var(--chip-ok-bg); }
    .strip.waiting { border-left-color: var(--chip-warn-fg); background: var(--chip-warn-bg); }
    .strip.done { border-left-color: var(--ink); }

    .pip { width: 9px; height: 9px; border-radius: 999px; background: var(--muted); flex: none; }
    .running .pip { background: var(--chip-ok-fg); }
    .waiting .pip { background: var(--chip-warn-fg); }
    .beat { animation: beat 1.3s ease-in-out infinite; }
    @keyframes beat { 50% { opacity: .2; } }

    .what { font-weight: 700; }
    .detail { color: var(--ink-soft); min-width: 0; }
    .counts { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-left: auto; }
    .count { color: var(--ink-soft); white-space: nowrap; }
    .count b { font-size: 15px; color: var(--ink); }

    @media (prefers-reduced-motion: reduce) { .beat { animation: none; } }
  `
})
export class RunBanner {
  private readonly state = inject(StateService);
  private readonly loads = inject(LoadService);

  private readonly drop = computed(() => this.state.view()?.drop ?? null);

  protected readonly phase = computed(() => {
    const drop = this.drop();
    if (!drop?.available) {
      return null;
    }
    const run = this.loads.run();
    // The run, not the sitting. waiting, admitted and seats are the cards
    // directly beneath this strip - repeating them here made seatsTaken the
    // third rendering of one number on one page. vus and secondsElapsed are
    // facts about the run itself, which is the only thing this strip is
    // uniquely placed to describe, and nothing else on the page shows them.
    const counts = run && run.phase !== 'NONE'
      ? [
          { label: 'customers offered', value: run.vus },
          { label: 'seconds in', value: run.secondsElapsed }
        ]
      : [];

    switch (run?.phase) {
      case 'RUNNING':
        return {
          tone: 'running', live: true, counts,
          title: 'A rush is in flight',
          detail: 'Customers are arriving and the queue is draining.'
        };
      case 'PENDING':
        return {
          tone: 'waiting', live: true, counts,
          title: 'Waiting for the cluster',
          // The scheduler's verbatim words are in the cluster card's callout;
          // saying them twice on one page is how the reason got duplicated.
          detail: 'The Job is Pending. The cluster card below has the scheduler\'s own words.'
        };
      case 'SUCCEEDED':
        return {
          tone: 'done', live: false, counts,
          title: 'The rush is over',
          detail: 'These are the numbers it left behind.'
        };
      case 'FAILED':
        return {
          tone: 'waiting', live: false, counts,
          title: 'The run did not finish',
          detail: run?.message ?? 'The Job reported a failure.'
        };
      default:
        return {
          tone: 'idle', live: false, counts,
          title: 'Your simulation is open',
          detail: 'Nothing is arriving yet — send a crowd to fill it.'
        };
    }
  });
}

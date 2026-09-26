import { Component, computed, input } from '@angular/core';
import { AgentFact } from './analysis';

interface Step { label: string; count: number; width: number }
interface Loss { count: string; text: string }

/**
 * Where a rush's customers went, as four bars: arrived, joined the queue,
 * admitted, booked - each as wide as its share of those who arrived - with
 * the reasons people dropped out written between them.
 *
 * Drawn from the run's facts, never from the model's prose, so the chart is
 * right even when the report is not. A run analysed before the funnel was
 * counted has no Arrived fact, and the chart simply does not appear.
 */
@Component({
  selector: 'rb-funnel',
  template: `
    @if (steps(); as s) {
      <figure class="funnel" aria-label="Where the customers went">
        @for (step of s; track step.label; let i = $index) {
          <div class="row">
            <span class="label">{{ step.label }}</span>
            <span class="track"><span class="bar" [style.width.%]="step.width"></span></span>
            <span class="count mono">{{ step.count }}</span>
          </div>
          @if (losses()[i]; as lost) {
            @if (lost.length) {
              <ul class="losses">
                @for (l of lost; track l.text) {
                  <li><span class="mono">{{ l.count }}</span> {{ l.text }}</li>
                }
              </ul>
            }
          }
        }
        @if (seatsLine(); as line) {
          <figcaption class="seats">{{ line }}</figcaption>
        }
      </figure>
    }
  `,
  styles: `
    .funnel { margin: 4px 0 14px; display: grid; gap: 4px; }
    .row { display: grid; grid-template-columns: 130px minmax(0, 1fr) 48px; align-items: center; gap: 10px; }
    .label { font-size: 13px; }
    .track { height: 14px; background: var(--canvas); border-radius: 3px; overflow: hidden; }
    .bar { display: block; height: 100%; min-width: 2px; background: var(--ink); border-radius: 3px; }
    .count { text-align: right; font-size: 13px; font-weight: 700; }
    .losses { margin: 0 0 4px 140px; padding: 0; list-style: none; font-size: 12px; color: var(--chip-warn-fg); }
    .losses li::before { content: '↘ '; }
    .seats { margin-top: 6px; font-size: 13px; color: var(--ink-soft); }
    .mono { font-family: var(--mono); }
    @media (max-width: 599px) {
      .row { grid-template-columns: minmax(0, 1fr) 44px; }
      .label { grid-column: 1 / -1; }
      .losses { margin-left: 0; }
    }
  `
})
export class Funnel {
  readonly facts = input.required<AgentFact[]>();

  private value(label: string): string | null {
    return this.facts().find(f => f.label === label)?.value ?? null;
  }

  protected readonly steps = computed<Step[] | null>(() => {
    const arrived = Number(this.value('Arrived'));
    if (!this.value('Arrived') || !(arrived > 0)) {
      return null;
    }
    return ['Arrived', 'Joined the queue', 'Admitted', 'Booked'].map(label => {
      const count = Number(this.value(label) ?? 0);
      return { label, count, width: Math.max(0, Math.min(100, (count / arrived) * 100)) };
    });
  });

  /** The drop-offs after each step, from the outcome facts. */
  protected readonly losses = computed<Loss[][]>(() => {
    const out = (pairs: [string, string][]) => pairs
      .map(([label, text]) => ({ count: this.value(label), text }))
      .filter((l): l is Loss => l.count !== null);
    return [
      out([['Sold out at the queue (409)', 'found it sold out at the queue (409)'], ['Other faults', 'hit a fault']]),
      out([['Gave up waiting (403)', 'gave up waiting and were refused (403)']]),
      out([['Admitted but refused (403)', 'admitted but refused (403)'],
        ['Sold out at booking (409)', 'sold out by the time they booked (409)'],
        ['Overloaded (503)', 'turned away by an overloaded service (503)']]),
      []
    ];
  });

  protected readonly seatsLine = computed(() => {
    const run = this.value('Seats taken by this run');
    const total = this.value('Seats taken / capacity');
    if (!run) {
      return null;
    }
    return `${run} seats taken by this run` + (total ? ` · ${total} seats taken in the sitting` : '');
  });
}

import { Component, computed, inject, signal } from '@angular/core';
import { ChaosCard } from './chaos-card';
import { IncidentService } from './incidents';
import { LoadService } from './load.service';
import { RunPanel } from './run-panel';

/**
 * The two things a visitor can do to the system, as tabs of one card: run a
 * rush, and - once one is running - break something during it.
 *
 * Both stay rendered and only one shows, so switching tabs keeps what was
 * chosen on the other. The Chaos tab says when it has something to show: a
 * rush it could break, or a fault already running.
 */
@Component({
  selector: 'rb-controls-card',
  imports: [ChaosCard, RunPanel],
  template: `
    <section class="panel">
      <div class="accent" [class.red]="tab() === 'chaos'"></div>
      <div class="tabs" role="tablist">
        <button role="tab" class="tab" [class.on]="tab() === 'rush'" [attr.aria-selected]="tab() === 'rush'"
                (click)="tab.set('rush')">Run a rush</button>
        <button role="tab" class="tab" [class.on]="tab() === 'chaos'" [attr.aria-selected]="tab() === 'chaos'"
                (click)="tab.set('chaos')">
          Chaos drill
          @if (incidents.active()) { <span class="dot live" title="A fault is running"></span> }
          @else if (!rushing()) { <span class="tag">needs a rush</span> }
        </button>
      </div>
      <div [hidden]="tab() !== 'rush'"><rb-run-panel [bare]="true" /></div>
      <div [hidden]="tab() !== 'chaos'"><rb-chaos-card [bare]="true" /></div>
    </section>
  `,
  styles: `
    .accent { height: 4px; background: var(--dhl-yellow); }
    .accent.red { background: var(--dhl-red); }
    .tabs { display: flex; gap: 4px; padding: 12px 16px 0; border-bottom: 1px solid var(--line); }
    .tab { font: inherit; font-size: 15px; font-weight: 700; padding: 10px 14px; border: 0; background: none;
           color: var(--muted); cursor: pointer; border-bottom: 3px solid transparent; margin-bottom: -1px;
           display: inline-flex; align-items: center; gap: 8px; }
    .tab:hover { color: var(--ink); }
    .tab.on { color: var(--ink); border-bottom-color: var(--ink); }
    .tag { font-size: 11px; font-weight: 600; color: var(--chip-warn-fg); background: var(--chip-warn-bg);
           border-radius: 999px; padding: 2px 8px; }
    .dot { width: 8px; height: 8px; border-radius: 999px; background: var(--dhl-red); }
  `
})
export class ControlsCard {
  protected readonly incidents = inject(IncidentService);
  private readonly loads = inject(LoadService);
  protected readonly tab = signal<'rush' | 'chaos'>('rush');
  protected readonly rushing = computed(() => this.loads.run()?.phase === 'RUNNING');
}

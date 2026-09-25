import { Component, inject } from '@angular/core';
import { InspectorService } from './inspector.service';

/**
 * The load-test Jobs of the last hour, under the graph.
 *
 * The graph draws the long-lived objects; a run's Job lives an hour and is not
 * one of them. This row is the way in to it, and it stays in view whether or
 * not the inspector is open, since the inspector only shows while something is
 * selected.
 */
@Component({
  selector: 'rb-recent-runs',
  template: `
    <div class="runs">
      <span class="kind mono">Recent runs</span>
      @for (j of inspector.recentJobs(); track j.name) {
        <button class="run" (click)="inspector.select({ kind: 'job', name: j.name })" [title]="j.headline">
          <span [class]="'dot t-' + j.tone"></span>
          <span class="mono">{{ j.name }}</span>
          <span class="quiet">{{ j.headline }}</span>
        </button>
      } @empty {
        <span class="quiet">No runs in the last hour.</span>
      }
    </div>
  `,
  styles: `
    .runs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 10px; margin-top: 16px;
            padding-top: 14px; border-top: 1px solid var(--line); }
    .kind { font-size: 11px; letter-spacing: .1em; text-transform: uppercase; color: var(--muted); margin-right: 4px; }
    .mono { font-family: var(--mono); }
    .quiet { color: var(--muted); font-size: 13px; }
    .run { display: inline-flex; align-items: center; gap: 8px; max-width: 100%; border: 1px solid var(--line);
           background: var(--white); color: var(--ink); border-radius: 6px; padding: 4px 10px; cursor: pointer;
           font-size: 12px; text-align: left; }
    .run .quiet { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 280px; }
    .dot { width: 8px; height: 8px; border-radius: 50%; flex: none; background: var(--muted); }
    .dot.t-ok { background: var(--chip-ok-fg); }
    .dot.t-warn { background: var(--chip-warn-fg); }
    .dot.t-bad { background: var(--chip-bad-fg); }
  `
})
export class RecentRuns {
  protected readonly inspector = inject(InspectorService);
}

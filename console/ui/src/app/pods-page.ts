import { Component, output } from '@angular/core';
import { PodHealthPanel } from './pod-health';

@Component({
  selector: 'rb-pods-page',
  imports: [PodHealthPanel],
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Public</button>
        <span>/</span>
        <span style="color: var(--ink);">Pod health</span>
      </div>
      <div>
        <h1>Pod health</h1>
        <p class="lede">
          Read through the Kubernetes API with a ServiceAccount scoped to this namespace and no
          access to Secrets.
        </p>
      </div>
      <rb-pod-health [full]="true" />
      <div class="card">
        <div class="why">Why the budget matters here</div>
        <p style="margin: 0; font-size: 15px; color: var(--ink-soft); max-width: 70ch; text-wrap: pretty;">
          Every visitor load run asks the scheduler for CPU out of the same 3000m. When there is not
          enough, the Job sits Pending and the console says which limit stopped it and what is
          holding the budget, rather than reporting a generic failure.
        </p>
      </div>
    </div>
  `,
  styles: `
    .stack-24 { display: flex; flex-direction: column; gap: 24px; }
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .why { font-size: 19px; font-weight: 700; margin-bottom: 8px; }
  `
})
export class PodsPage {
  readonly home = output<void>();
}

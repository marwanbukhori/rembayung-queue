import { Component, output } from '@angular/core';
import { ClusterResources } from './cluster-resources';

@Component({
  selector: 'rb-cluster-page',
  imports: [ClusterResources],
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">Cluster resources</span>
      </div>
      <div>
        <h1>Cluster resources</h1>
        <p class="lede">
          Every workload behind the simulation, read live through the Kubernetes API with a
          ServiceAccount scoped to this namespace and no access to Secrets.
        </p>
      </div>
      <rb-cluster-resources [full]="true" />
      <div class="card">
        <div class="why">Why the budget matters here</div>
        <p style="margin: 0; font-size: 15px; color: var(--ink-soft); max-width: 70ch; text-wrap: pretty;">
          Every simulation's load run asks the scheduler for CPU out of the same 3000m. When there is
          not enough, the Job sits Pending and the console says which limit stopped it and what is
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
export class ClusterPage {
  readonly home = output<void>();
}

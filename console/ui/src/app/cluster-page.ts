import { Component, computed, inject, output } from '@angular/core';
import { ClusterResources } from './cluster-resources';
import { ArchitectureDiagram } from './architecture-diagram';
import { ClusterService } from './cluster.service';
import { ObjectGraph } from './object-graph';
import { ObservabilityPanel } from './observability-panel';
import { PodPulse } from './pod-pulse';

@Component({
  selector: 'rb-cluster-page',
  imports: [ArchitectureDiagram, ClusterResources, ObjectGraph, ObservabilityPanel, PodPulse],
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
      <div class="card">
        <div class="why">The shape of it</div>
        <p class="note">
          What talks to what, and where the namespace boundary falls.
        </p>
        <rb-architecture-diagram />
      </div>
      <rb-cluster-resources [full]="true" />

      <!--
        No wrapper: the card carries its own title and subtitle, so a heading
        above it said the same thing twice in two sizes.
      -->
      <rb-pod-pulse />

      <!--
        Below the workloads, above the object graph: it is a fact about the
        things just listed, and the reader has to have seen them first.
      -->
      <rb-observability-panel />

      <div class="card">
        <div class="why">How these objects connect</div>
        <p class="note">
          The list above is what is running. This is why: which Route publishes what, which
          Service fronts which Deployment, and what governs each one. booking-service and redis
          have no Route — they are reachable only from inside the namespace.
        </p>
        <rb-object-graph />
      </div>
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
  private readonly cluster = inject(ClusterService);

  protected readonly endpoints = computed(() => this.cluster.cluster()?.endpoints ?? []);

  readonly home = output<void>();
}

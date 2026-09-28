import { Component, computed, inject, output } from '@angular/core';
import { ClusterResources } from './cluster-resources';
import { ArchitectureDiagram } from './architecture-diagram';
import { ClusterService } from './cluster.service';
import { PodPulse } from './pod-pulse';

@Component({
  selector: 'rb-cluster-page',
  imports: [ArchitectureDiagram, ClusterResources, PodPulse],
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
          Everything this system runs on, read live from the cluster as you look. The console reads it with its own
          limited account: this namespace only, and no access to passwords.
        </p>
        <p class="lede budget">
          <b>One budget for everything.</b> Every service and every rush share the same 3 CPUs. When a rush asks for
          more than is left, it waits, and the page says which limit stopped it rather than failing with a generic error.
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
        Splunk and Dynatrace each had a panel here, and a card of saved searches;
        with both trials over they were three cards saying "Trial ended". One line
        keeps the history without looking broken.
      -->
      <p class="note">
        Splunk and Dynatrace were wired in and used while this was built; both trials have ended. Prometheus still
        watches everything, and its charts are on the simulation page beside the rush, with the live objects.
      </p>
    </div>
  `,
  styles: `
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .why { font-size: 19px; font-weight: 700; margin-bottom: 8px; }
    .lede { max-width: 72ch; text-wrap: pretty; }
    .budget { margin-top: 10px; }
    .budget b { color: var(--ink); }
  `
})
export class ClusterPage {
  private readonly cluster = inject(ClusterService);

  protected readonly endpoints = computed(() => this.cluster.cluster()?.endpoints ?? []);

  readonly home = output<void>();
}

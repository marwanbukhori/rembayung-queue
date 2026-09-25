import { Component } from '@angular/core';

/**
 * Everything the project uses, each with the job it does here. Moved from the
 * Overview to the Build notes: the Overview says what was built and shows it
 * running; this is the inventory a reader reaches for next.
 */
@Component({
  selector: 'rb-tools-list',
  template: `
    <section class="panel">
      <div class="head">
        <h2 class="title">Everything used, and what for</h2>
        <span class="count mono">{{ count }} pieces</span>
      </div>
      <div class="tools">
        @for (group of tools; track group.area) {
          <div class="tool-group">
            <div class="tool-area eyebrow">{{ group.area }}</div>
            @for (tool of group.items; track tool.name) {
              <div class="tool" [title]="tool.what">
                <span class="tool-name mono">{{ tool.name }}</span>
                <span class="tool-what">{{ tool.what }}</span>
              </div>
            }
          </div>
        }
      </div>
    </section>
  `,
  styles: `
    .panel { background: var(--white); border: 1px solid var(--line); border-radius: 4px; padding: 24px; }
    .head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 16px; }
    .title { font-size: 19px; font-weight: 700; margin: 0; }
    .count { font-size: 11px; color: var(--muted); }
    .tools {
      display: grid;
      gap: 24px 32px;
      grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr));
    }
    .tool-group { min-width: 0; }
    .tool-area { color: var(--muted); margin-bottom: 10px; }
    .tool { padding: 6px 0; border-top: 1px solid var(--rule); }
    .tool:first-of-type { border-top: 0; }
    .tool-name { display: block; font-size: 13px; font-weight: 700; }
    /*
      Clamped to one line. Twenty-three entries at two or three lines each was a
      page of prose where a scannable list belonged; the full sentence is still
      there on hover and for a screen reader.
    */
    .tool-what {
      display: block;
      font-size: 12px;
      color: var(--muted);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

  `
})
export class ToolsList {
  readonly tools = [
    {
      area: 'The services',
      items: [
        { name: 'Java 25', what: 'three Spring Boot services in one repository' },
        { name: 'Spring Boot 4', what: 'HTTP, scheduling, health probes, Micrometer metrics' },
        { name: 'Spring Data JPA', what: 'the pessimistic row lock that makes a seat unsellable twice' },
        { name: 'Maven', what: 'one wrapper per service; CI runs verify on each' }
      ]
    },
    {
      area: 'Data',
      items: [
        { name: 'Oracle', what: 'Autonomous Database a region away; the seat count lives in one row' },
        { name: 'Flyway', what: 'schema migrations, validated on every start' },
        { name: 'Redis', what: 'the queue itself: ticket counter, admission tokens, drop records' },
        { name: 'Testcontainers', what: 'real Oracle 23ai and Redis in the test run, not mocks' }
      ]
    },
    {
      area: 'Delivery',
      items: [
        { name: 'GitHub Actions', what: 'ci.yml builds and tests; cd.yml deploys only a green run' },
        { name: 'Docker Buildx', what: 'one image per service, tagged with the commit SHA' },
        { name: 'ghcr.io', what: 'the registry; immutable SHA tags, never :latest' },
        { name: 'Ansible', what: 'patches the Deployments, waits, rolls the set back on failure' },
        { name: 'Kustomize', what: 'base manifests with a sandbox overlay that pins the tags' }
      ]
    },
    {
      area: 'The cluster',
      items: [
        { name: 'OpenShift', what: 'Routes, Services and Deployments under a 3000m namespace quota' },
        { name: 'HPA', what: 'queue-gate scales 2 to 10, booking-service 2 to 4, on CPU' },
        { name: 'NetworkPolicy', what: 'Redis and booking-service reachable from queue-gate only' },
        { name: 'RBAC', what: 'a ServiceAccount that reads this namespace and no Secrets' },
        { name: 'CronJob', what: 'restarts the workloads three times a day to outlive the idler' }
      ]
    },
    {
      area: 'Seeing it',
      items: [
        { name: 'Prometheus', what: 'a ServiceMonitor scrapes :9090; a rule alerts on oversold' },
        { name: 'Dynatrace', what: 'trial ended - application-only OneAgent, now switched off' },
        { name: 'Splunk', what: 'trial ended - JSON events over HEC, now switched off' },
        { name: 'k6', what: 'the crowd, run as a Job inside the cluster' },
        { name: 'Angular 20', what: 'this console; signals and standalone components, no UI framework' }
      ]
    }
  ];

  readonly count = this.tools.reduce((sum, group) => sum + group.items.length, 0);
}

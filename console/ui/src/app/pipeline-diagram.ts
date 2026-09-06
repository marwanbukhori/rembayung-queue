import { Component } from '@angular/core';

/**
 * The whole path from a commit to a running pod.
 *
 * Drawn from the workflows rather than from memory: ci.yml runs the three Maven
 * builds, packages the jars, logs in to ghcr.io and pushes an image tagged with
 * the commit SHA; cd.yml is triggered by workflow_run on that CI run and refuses
 * to deploy unless its conclusion was success, then resolves the tag and hands
 * it to an Ansible playbook that patches the Deployments and waits.
 *
 * Every label here is a thing that exists in this repository. A pipeline diagram
 * that shows the pipeline somebody wishes they had is worse than none.
 */
@Component({
  selector: 'rb-pipeline-diagram',
  template: `
    <div class="frame">
      <svg viewBox="0 0 1000 420" role="img" [attr.aria-label]="summary">
        <defs>
          <marker id="rb-pipe-arrow" viewBox="0 0 10 10" refX="9" refY="5"
                  markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 z" fill="var(--muted)" />
          </marker>
        </defs>

        @for (lane of lanes; track lane.label) {
          <g>
            <rect class="lane" [attr.x]="lane.x" y="16" [attr.width]="lane.w" height="388" rx="8" />
            <text class="lane-label mono" [attr.x]="lane.x + 14" y="38">{{ lane.label }}</text>
          </g>
        }

        <g class="edges">
          @for (edge of edges; track edge; let i = $index) {
            <path [attr.id]="'rb-pipe-' + i" [attr.d]="edge" />
          }
        </g>

        <!--
          One commit, travelling. A pipeline drawn as boxes is a diagram of a
          pipeline; a dot moving through it, with each stage lighting as it
          arrives, is the pipeline. The whole loop takes about twelve seconds -
          slow enough to follow with your eyes rather than a strobe.
        -->
        <g class="commit" aria-hidden="true">
          @for (edge of edges; track edge; let i = $index) {
            <circle class="dot" r="5">
              <animateMotion dur="12s" repeatCount="indefinite"
                             [attr.begin]="i * 1.05 + 's'"
                             keyPoints="0;1" keyTimes="0;0.085" calcMode="linear"
                             fill="freeze">
                <mpath [attr.href]="'#rb-pipe-' + i" />
              </animateMotion>
              <animate attributeName="opacity" dur="12s" repeatCount="indefinite"
                       [attr.begin]="i * 1.05 + 's'"
                       values="0;1;1;0;0" keyTimes="0;0.02;0.075;0.09;1" />
            </circle>
          }
        </g>

        @for (box of boxes; track box.label) {
          <g [class]="'box ' + box.tone">
            <rect [attr.x]="box.x" [attr.y]="box.y" [attr.width]="box.w"
                  [attr.height]="box.h" rx="6" />
            <text class="name mono" [attr.x]="box.x + 12" [attr.y]="box.y + 24">{{ box.label }}</text>
            <text class="what" [attr.x]="box.x + 12" [attr.y]="box.y + 42">{{ box.what }}</text>
            @if (box.extra) {
              <text class="what" [attr.x]="box.x + 12" [attr.y]="box.y + 58">{{ box.extra }}</text>
            }
          </g>
        }
      </svg>
    </div>
  `,
  styles: `
    .frame { width: 100%; overflow-x: auto; }
    svg { width: 100%; min-width: 900px; height: auto; display: block; }

    .lane { fill: var(--canvas); stroke: var(--line); stroke-dasharray: 4 4; }
    .lane-label { font-size: 10px; letter-spacing: .1em; fill: var(--muted); }

    .edges path { fill: none; stroke: var(--muted); stroke-width: 2; marker-end: url(#rb-pipe-arrow); }
    .commit .dot { fill: var(--dhl-red); opacity: 0; }

    /*
      Each stage lights as the commit reaches it, on the same twelve-second
      loop. The delays are the lane order, so the highlight travels rather than
      flickering everywhere at once.
    */
    .box rect { animation: stage 12s ease-in-out infinite; }
    @keyframes stage {
      0%, 100% { fill: var(--white); }
      6%       { fill: var(--highlight); }
      14%      { fill: var(--white); }
    }
    .box.gate rect, .box.store rect, .box.watch rect { animation: none; }

    @media (prefers-reduced-motion: reduce) {
      .commit .dot { display: none; }
      .box rect { animation: none; }
    }

    .box rect { fill: var(--white); stroke: var(--line); stroke-width: 1; }
    .box.gate rect { fill: var(--chip-warn-bg); stroke: var(--chip-warn-fg); }
    .box.store rect { fill: var(--canvas); stroke-dasharray: 4 3; }
    .box.watch rect { fill: var(--chip-info-bg); stroke: var(--chip-info-fg); }

    .name { font-size: 12px; font-weight: 700; fill: var(--ink); }
    .what { font-size: 11px; fill: var(--muted); }
  `
})
export class PipelineDiagram {
  /** The four stages a change passes through, as columns. */
  protected readonly lanes = [
    { x: 8, w: 224, label: 'SOURCE' },
    { x: 248, w: 224, label: 'CI · GITHUB ACTIONS' },
    { x: 488, w: 200, label: 'REGISTRY' },
    { x: 704, w: 288, label: 'CD · ANSIBLE → OPENSHIFT' }
  ];

  protected readonly boxes = [
    { x: 24, y: 60, w: 192, h: 52, tone: 'plain',
      label: 'Java 25 · Spring Boot 4', what: 'three services, one repo' },
    { x: 24, y: 130, w: 192, h: 52, tone: 'plain',
      label: 'Maven', what: './mvnw verify per service' },
    { x: 24, y: 200, w: 192, h: 68, tone: 'plain',
      label: 'Testcontainers', what: 'real Oracle 23ai and Redis', extra: 'in the test run, not mocks' },
    { x: 24, y: 286, w: 192, h: 52, tone: 'plain',
      label: 'git push main', what: 'the only trigger' },

    { x: 264, y: 60, w: 192, h: 68, tone: 'plain',
      label: 'ci.yml', what: 'builds and tests all three,', extra: 'cancels superseded branches' },
    { x: 264, y: 146, w: 192, h: 52, tone: 'gate',
      label: 'tests must pass', what: 'no green run, no image' },
    { x: 264, y: 216, w: 192, h: 68, tone: 'plain',
      label: 'Buildx', what: 'one image per service,', extra: 'tagged with the commit SHA' },

    { x: 504, y: 130, w: 168, h: 68, tone: 'store',
      label: 'ghcr.io', what: 'immutable SHA tags,', extra: 'never :latest' },

    { x: 720, y: 60, w: 256, h: 68, tone: 'gate',
      label: 'cd.yml', what: 'workflow_run on that CI run —', extra: 'refuses unless it succeeded' },
    { x: 720, y: 146, w: 256, h: 68, tone: 'plain',
      label: 'Ansible', what: 'patches each Deployment, waits,', extra: 'rolls back the whole set on failure' },
    { x: 720, y: 232, w: 256, h: 68, tone: 'plain',
      label: 'OpenShift', what: 'Route → Service → Deployment,', extra: 'HPA, quota, NetworkPolicy, RBAC' },
    { x: 720, y: 318, w: 256, h: 68, tone: 'watch',
      label: 'Prometheus · Dynatrace · Splunk', what: 'ServiceMonitor scrapes :9090,',
      extra: 'OneAgent traces, HEC ships logs' }
  ];

  protected readonly edges = [
    // Source flows down its own lane into CI.
    'M120 112 V130',
    'M120 182 V200',
    'M120 268 V286',
    'M216 312 H240 V94 H264',
    // CI: build, gate, publish.
    'M360 128 V146',
    'M360 198 V216',
    // CI to the registry, registry to CD.
    'M456 250 H480 V164 H504',
    'M672 164 H696 V94 H720',
    // CD down its own lane.
    'M848 128 V146',
    'M848 214 V232',
    'M848 300 V318'
  ];

  protected readonly summary =
    'A push to main runs ci.yml, which builds and tests three Spring Boot services against real '
    + 'Oracle and Redis containers. Only a green run publishes images to ghcr.io, tagged with the '
    + 'commit SHA. cd.yml triggers on that run, refuses to deploy unless it succeeded, and hands the '
    + 'tag to an Ansible playbook that patches the OpenShift Deployments, waits for them, and rolls '
    + 'the whole set back if any fails. Prometheus, Dynatrace and Splunk observe what results.';
}

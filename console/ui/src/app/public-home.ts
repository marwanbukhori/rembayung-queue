import { Component, computed, inject, output } from '@angular/core';
import { ArchitectureDiagram } from './architecture-diagram';
import { FlowDiagram } from './flow-diagram';
import { PipelineDiagram } from './pipeline-diagram';
import { Reveal } from './reveal';
import { StateService } from './state.service';

/** The three places to look when this page is not enough. */
interface Outward {
  name: string;
  note: string;
  href: string;
  mark: 'openshift' | 'dynatrace' | 'splunk';
}

/**
 * The overview: what this is, what it simulates, what it is built out of, and
 * then the live numbers under it.
 *
 * <h2>Written for someone who has never heard of any of this</h2>
 * The page used to open with "250 seats, never oversold" and a button marked
 * "Start a drop", both of which are meaningless without the briefing that used
 * to come with the link. The hero now states, in order: that this is a
 * simulation and not a booking site, what real event it simulates, what the
 * cluster behind it looks like, and what it is made of. Only then does it show a
 * number.
 */
@Component({
  selector: 'rb-public-home',
  imports: [ArchitectureDiagram, FlowDiagram, PipelineDiagram, Reveal],
  template: `
    <div class="stack">
      <section class="panel">
        <div class="accent-top"></div>

        <!--
          Bands down the page, each one thing, each read across. The hero used to
          be two tall columns side by side, which meant the pitch and the diagram
          were both narrow and neither finished on one screen.
        -->
        <div class="hero">
          <div class="hero-lead">
            <span class="tag eyebrow">Simulation — not a real booking site</span>
            <h1 class="headline">Watch a restaurant's 9pm booking rush, on demand</h1>
            <div class="hero-actions">
              <button class="btn btn-primary" (click)="visitor.emit()">Start a simulation</button>
              <button class="btn btn-secondary" (click)="docs.emit()">Read the design spec</button>
            </div>
            <!--
              The claim, live, where the claim is made. Read from the same field
              the alert rule reads, so the hero cannot boast a number the rest
              of the page would contradict.
            -->
            <div class="proof">
              <span class="proof-pip"></span>
              <span class="proof-figure mono">{{ oversold() }}</span>
              <span class="proof-text">seats oversold, across every simulation this cluster has run</span>
            </div>
          </div>
          <div class="hero-say">
            <p class="hero-lede">
              Rembayung takes reservations for one sitting a night, and bookings open at
              <strong>21:00 every day except Friday</strong>. The rush is not a surprise, it is
              scheduled: thousands of people press the same button in the same second for 250
              seats. This console runs that minute on demand, so you can watch what the queue does
              instead of being told.
            </p>
            <p class="hero-note">
              Starting one seeds a fresh 250-seat slot of your own and opens it immediately, so you
              can see what 21:00 looks like without waiting for 21:00.
            </p>
          </div>
        </div>

        <div class="band" [rbReveal]="0">
          <div class="band-head-row">
            <div class="band-head eyebrow">The path one customer takes</div>
            <div class="band-aside mono">read live from the cluster</div>
          </div>
          <rb-flow-diagram [alwaysMoving]="true" />
        </div>

        <div class="band" [rbReveal]="0">
          <div class="band-head-row">
            <div class="band-head eyebrow">What runs it</div>
            <div class="band-aside mono">pod counts are live</div>
          </div>
          <rb-architecture-diagram />
        </div>

        <div class="band" [rbReveal]="0">
          <div class="band-head-row">
            <div class="band-head eyebrow">Three moves to try to break it</div>
            <div class="band-aside mono">nothing to install</div>
          </div>
          <div class="moves">
            @for (move of moves; track move.name; let i = $index) {
              <div class="move">
                <div class="move-num mono">{{ i + 1 }}</div>
                <div style="min-width: 0;">
                  <div class="move-name">{{ move.name }}</div>
                  <div class="move-note">{{ move.note }}</div>
                </div>
              </div>
            }
          </div>
        </div>

        <div class="band" [rbReveal]="0">
          <div class="band-head eyebrow">What is being simulated</div>
          <div class="facts">
            <div class="fact">
              <div class="fact-figure mono">21:00</div>
              <div class="fact-name">Every day except Friday</div>
              <div class="fact-note">
                A published opening time, so every customer arrives in the same second rather than
                spread across an evening.
              </div>
            </div>
            <div class="fact">
              <div class="fact-figure mono">~3,000</div>
              <div class="fact-name">Attempts that broke the real thing</div>
              <div class="fact-note">
                The restaurant's own platform fell over at around three thousand booking attempts,
                and took the reservations with it.
              </div>
            </div>
            <div class="fact">
              <div class="fact-figure mono">250</div>
              <div class="fact-name">Seats that must stay 250</div>
              <div class="fact-note">
                Scalpers found the double-sell: the same seat confirmed to two people. That is the
                failure this build refuses to reproduce.
              </div>
            </div>
          </div>
        </div>

        <div class="band" [rbReveal]="0">
          <div class="band-head-row">
            <div class="band-head eyebrow">How a commit reaches a pod</div>
            <div class="band-aside mono">every label exists in the repo</div>
          </div>
          <rb-pipeline-diagram />
        </div>

        <div class="band" [rbReveal]="0">
          <div class="band-head-row">
            <div class="band-head eyebrow">Everything used, and what for</div>
            <div class="band-aside mono">{{ toolCount() }} pieces</div>
          </div>
          <!--
            Named with a job each. A row of logos says what somebody has touched;
            it does not say what any of it does here, which is the only thing
            worth knowing about a stack you are being shown.
          -->
          <div class="tools">
            @for (group of tools; track group.area) {
              <div class="tool-group" [rbReveal]="0">
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
        </div>
      </section>

    </div>
  `,
  styles: `
    /*
      Headline and actions on the left, the explanation on the right, both on one
      line above the fold. Two columns here and nowhere else: this is the only
      place on the page where two things are genuinely read together.
    */
    .hero {
      padding: 36px 24px 30px;
      display: grid;
      gap: 20px 48px;
      grid-template-columns: repeat(auto-fit, minmax(min(380px, 100%), 1fr));
      align-items: start;
    }
    .hero-lead { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 18px; }
    .hero-say { min-width: 0; display: flex; flex-direction: column; gap: 14px; }
    .tag {
      color: var(--chip-info-fg);
      background: var(--chip-info-bg);
      border-radius: 999px;
      padding: 4px 12px;
    }
    .headline {
      margin: 0;
      font-size: 38px;
      font-weight: 800;
      letter-spacing: -0.02em;
      line-height: 1.1;
      max-width: 19ch;
      text-wrap: balance;
    }
    .hero-lede { margin: 0; font-size: 16px; color: var(--ink-soft); max-width: 62ch; text-wrap: pretty; }
    .hero-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
    .proof {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 14px;
      border: 1px solid var(--chip-ok-fg);
      background: var(--chip-ok-bg);
      border-radius: 999px;
      font-size: 14px;
      color: var(--ink-soft);
    }
    .proof-pip {
      width: 8px;
      height: 8px;
      border-radius: 999px;
      background: var(--chip-ok-fg);
      flex: none;
      animation: proof-beat 2s ease-in-out infinite;
    }
    @keyframes proof-beat { 50% { opacity: .25; } }
    .proof-figure { font-size: 18px; font-weight: 700; color: var(--chip-ok-fg); }
    .proof-text { min-width: 0; text-wrap: pretty; }
    @media (prefers-reduced-motion: reduce) { .proof-pip { animation: none; } }

    .hero-note { margin: 0; font-size: 14px; color: var(--muted); max-width: 62ch; text-wrap: pretty; }

    /* A band's title on the left, its one-line qualifier on the right. */
    .band-head-row {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 4px 16px;
      margin-bottom: 16px;
    }
    .band-head-row .band-head { margin-bottom: 0; }
    .band-aside { font-size: 11px; letter-spacing: .04em; color: var(--muted); }

    /* Three across, and one under the other only when there is no room. */
    .moves {
      display: grid;
      gap: 20px 32px;
      grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr));
    }
    .move { display: flex; gap: 12px; align-items: flex-start; min-width: 0; }
    .move-num {
      flex: none;
      width: 26px;
      height: 26px;
      border-radius: 999px;
      background: var(--dhl-yellow);
      display: grid;
      place-items: center;
      font-size: 13px;
      font-weight: 700;
    }
    .move-name { font-size: 15px; font-weight: 700; }
    .move-note { font-size: 14px; color: var(--ink-soft); margin-top: 2px; text-wrap: pretty; }

    .band { border-top: 1px solid var(--rule); padding: 24px; }
    .band-head { color: var(--muted); margin-bottom: 16px; }
    .facts {
      display: grid;
      gap: 20px 24px;
      grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
    }
    .fact { min-width: 0; }
    .fact-figure { font-size: 30px; font-weight: 700; line-height: 1; }
    .fact-name { font-size: 15px; font-weight: 700; margin-top: 8px; }
    .fact-note { font-size: 14px; color: var(--ink-soft); margin-top: 4px; text-wrap: pretty; }

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

    .chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .stack-chip {
      font-family: var(--mono);
      font-size: 12px;
      letter-spacing: .03em;
      color: var(--ink-soft);
      background: var(--canvas);
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 4px 12px;
      white-space: nowrap;
    }

    .links {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr));
    }
    .link-card {
      min-width: 0;
      display: flex;
      align-items: flex-start;
      gap: 12px;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      padding: 16px;
      color: var(--ink);
      text-decoration: none;
      transition: border-color 120ms var(--ease);
    }
    .link-card:hover { border-color: var(--ink); color: var(--ink); }
    .link-mark {
      flex: none;
      width: 32px;
      height: 32px;
      border-radius: 4px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .mark-openshift { background: var(--chip-bad-bg); color: var(--dhl-red); }
    .mark-dynatrace { background: var(--chip-info-bg); color: var(--info); }
    .mark-splunk { background: var(--chip-neutral-bg); color: var(--ink); }
    .link-text { min-width: 0; flex: 1 1 auto; }
    .link-name { display: block; font-size: 16px; font-weight: 700; }
    .link-note { display: block; font-size: 14px; color: var(--ink-soft); margin-top: 2px; text-wrap: pretty; }
    .link-out { flex: none; color: var(--muted); margin-top: 2px; }
    .link-card:hover .link-out { color: var(--dhl-red); }

    .doc-groups {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr));
      align-items: start;
    }
    .doc-group {
      min-width: 0;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .doc-group-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      padding: 12px 16px;
      border-bottom: 1px solid var(--line);
    }
    .group-chip { background: var(--highlight); color: var(--ink); border-radius: 999px; padding: 3px 10px; }
    .group-count { font-size: 13px; color: var(--muted); }
    .doc-row {
      display: block;
      width: 100%;
      padding: 12px 16px;
      background: var(--white);
      border: 0;
      border-bottom: 1px solid var(--rule);
      font: inherit;
      color: var(--ink);
      text-align: left;
      cursor: pointer;
    }
    .doc-row:last-child { border-bottom: 0; }
    .doc-row:hover { background: var(--highlight); }
    .doc-title { display: block; font-size: 14px; font-weight: 700; text-wrap: pretty; }
    .doc-blurb {
      display: block;
      font-size: 13px;
      color: var(--ink-soft);
      margin-top: 2px;
      text-wrap: pretty;
    }
  `
})
export class PublicHome {
  readonly docs = output<void>();
  readonly visitor = output<void>();

  protected readonly state = inject(StateService);

  /** Named because a hiring manager reads the list before reading the code. */
  /** The three things a visitor can actually do, in the order the page offers them. */
  /** Named with the job each one does here, not just named. */
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
        { name: 'Dynatrace', what: 'application-only OneAgent for distributed traces and the service map' },
        { name: 'Splunk', what: 'logback ships JSON events over HEC, behind a profile' },
        { name: 'k6', what: 'the crowd, run as a Job inside the cluster' },
        { name: 'Angular 20', what: 'this console; signals and standalone components, no UI framework' }
      ]
    }
  ];

  readonly toolCount = computed(() =>
    this.tools.reduce((sum, group) => sum + group.items.length, 0));

  readonly moves = [
    {
      name: 'Start the 21:00 rush',
      note: 'Seeds a slot row of 250 seats and a ticket counter of your own, then opens it.',
    },
    {
      name: 'Send the crowd',
      note: 'A load job inside the cluster offers hundreds of customers in the same second.',
    },
    {
      name: 'Push it past breaking',
      note: 'Raise admission until the connection pool gives out, and watch oversold stay at zero.',
    },
  ];

  readonly oversold = computed(() => this.state.view()?.drop.oversold ?? 0);



}

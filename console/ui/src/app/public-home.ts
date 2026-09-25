import { Component, output } from '@angular/core';
import { ArchitectureDiagram } from './architecture-diagram';
import { Reveal } from './reveal';

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
  imports: [ArchitectureDiagram, Reveal],
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
            <span class="tag eyebrow">Simulation</span>
            <h1 class="headline">A virtual queue and booking service, deployed on OpenShift and load-tested live</h1>
          </div>
          <div class="hero-say">
            <p class="hero-lede">
              A working console for a booking system built to survive its own busiest second. A
              restaurant sells 250 seats a night and opens them all at once; around three thousand
              people press the same button in the same moment, and the seat sold twice is the one
              that ends up in the newspaper.
            </p>
            <p class="hero-lede">
              Everything behind this page is real and deployed. Three Spring Boot services on
              <strong>OpenShift</strong>, tested against real Oracle and Redis containers, shipped
              by <strong>GitHub Actions</strong> and <strong>Ansible</strong> with automatic
              rollback, autoscaled under a fixed CPU budget, and watched by
              <strong>Prometheus</strong> (Dynatrace and Splunk were wired in too; both trials have
              since ended). Press start and the rush runs
              against that cluster while you watch.
            </p>
          </div>
        </div>


        <div class="band" id="system" [rbReveal]="0">
          <div class="band-head-row">
            <div class="band-head eyebrow">The system</div>
            <div class="band-aside mono">pod counts and oversold are live</div>
          </div>
          <rb-architecture-diagram />
        </div>

        <!--
          Where to go next. One obvious action - run a rush - and the rest as
          equals. CI/CD and AI Agent tiles join as their pages ship.
        -->
        <div class="band tiles">
          <button class="tile primary" (click)="visitor.emit()">
            <span class="tile-name">Run a simulation</span>
            <span class="tile-what">Start a rush against the live cluster and watch it land</span>
            <span class="tile-go">Start a rush →</span>
          </button>
          <button class="tile" (click)="cluster.emit()">
            <span class="tile-name">Cluster</span>
            <span class="tile-what">The objects behind it: pods, autoscalers, the CPU budget</span>
          </button>
          <button class="tile" (click)="cicd.emit()">
            <span class="tile-name">CI/CD</span>
            <span class="tile-what">How a commit reaches a pod, with real runs and their logs</span>
          </button>
          <button class="tile" (click)="agent.emit()">
            <span class="tile-name">AI Agent <span class="tag">In progress</span></span>
            <span class="tile-what">A bounded agent that reads each rush and reports what it found, citing its facts</span>
          </button>
          <button class="tile" (click)="docs.emit()">
            <span class="tile-name">Build notes</span>
            <span class="tile-what">How each part was built, and why</span>
          </button>
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

    .toc {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      padding: 14px 24px;
      border-top: 1px solid var(--rule);
    }
    .toc-link {
      font-size: 13px;
      color: var(--ink-soft);
      text-decoration: none;
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 5px 13px;
      white-space: nowrap;
      transition: border-color 120ms var(--ease), color 120ms var(--ease);
    }
    .toc-link:hover { border-color: var(--dhl-red); color: var(--dhl-red); }

    /* So a jumped-to band clears the sticky navbar above it. */
    .band { border-top: 1px solid var(--rule); padding: 24px; scroll-margin-top: 96px; }
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

    .tiles { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
    .tile {
      display: flex; flex-direction: column; gap: 6px; text-align: left; font: inherit; cursor: pointer;
      background: var(--white); color: var(--ink); border: 1px solid var(--line); border-radius: 4px; padding: 18px 20px;
    }
    .tile:hover { border-color: var(--ink); }
    /* The primary action takes the left half; the four others fill a 2 by 2 beside it. */
    .tile.primary { grid-column: span 2; grid-row: span 2; background: var(--dhl-red); color: #fff; border-color: var(--dhl-red); }
    .tile.primary:hover { filter: brightness(1.08); }
    .tile-name { font-size: 18px; font-weight: 700; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; }
    .tag { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px;
           background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .tile-what { font-size: 14px; color: var(--ink-soft); }
    .tile.primary .tile-what { color: rgba(255, 255, 255, .9); }
    .tile-go { margin-top: auto; padding-top: 16px; font-size: 22px; font-weight: 700; }
    @media (max-width: 899px) { .tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    @media (max-width: 899px) { .tile.primary { grid-row: auto; } }
    @media (max-width: 599px) { .tiles { grid-template-columns: minmax(0, 1fr); } .tile.primary { grid-column: auto; } }

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
  readonly cluster = output<void>();
  readonly cicd = output<void>();
  readonly agent = output<void>();


  /** Named because a hiring manager reads the list before reading the code. */
  /** The three things a visitor can actually do, in the order the page offers them. */
  /** Named with the job each one does here, not just named. */







}

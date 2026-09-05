import { Component, OnInit, computed, effect, inject, output } from '@angular/core';
import { CanonicalDrop } from './canonical-drop';
import { ClusterResources } from './cluster-resources';
import { DocsService, GROUP_LABELS } from './docs.service';
import { Placeholder } from './placeholder';
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
  imports: [CanonicalDrop, ClusterResources, Placeholder],
  template: `
    <div class="stack">
      <section class="panel">
        <div class="accent-top"></div>

        <div class="hero">
          <div class="hero-left">
            <span class="tag eyebrow">Simulation — not a real booking site</span>
            <h1 class="headline">Watch a restaurant's 9pm booking rush, on demand</h1>
            <p class="hero-lede">
              Rembayung takes reservations for one sitting a night, and bookings open at
              <strong>21:00 every day except Friday</strong>. The rush is not a surprise, it is
              scheduled: thousands of people press the same button in the same second for 250
              seats. This console runs that minute on demand, so you can watch what the queue does
              instead of being told.
            </p>
            <div class="hero-actions">
              <button class="btn btn-primary" (click)="visitor.emit()">Start a simulation</button>
              <button class="btn btn-secondary" (click)="docs.emit()">Read the design spec</button>
            </div>
            <p class="hero-note">
              Starting one seeds a fresh 250-seat slot of your own and opens it immediately, so you
              can see what 21:00 looks like without waiting for 21:00.
            </p>

            <div class="moves">
              <div>
                <div class="moves-head">Three moves to try to break it</div>
                <p class="moves-sub">Nothing to install, nothing shared with anyone else on this page</p>
              </div>
              <div class="move-list">
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
          </div>

          <!--
            The path down the right, not a picture: each box is a component that
            owns one guarantee, and the label on each hop is what crosses between
            them. It reads correctly out loud, which a diagram does not.
          -->
          <div class="hero-right">
            <div class="path-head">
              <div class="path-title">The path one customer takes</div>
              <div class="path-count mono">five hops</div>
            </div>

            <div class="path-node">
              <div class="path-node-head">
                <span class="path-name mono">Arrivals</span>
                <span class="path-badge mono">~3,000 in one second</span>
              </div>
              <div class="path-note">
                Everyone presses the same button the moment bookings open. Synchronised by a
                published time, not spread across an evening.
              </div>
            </div>

            <div class="hop"><div class="hop-line" aria-hidden="true"></div><span class="hop-label mono">all of it lands on one Route</span></div>

            <div class="zone">
              <div class="zone-head mono">OPENSHIFT NAMESPACE · 3000m CPU BUDGET</div>

              <div class="path-node">
                <div class="path-node-head">
                  <span class="path-name mono">queue-gate</span>
                  <span class="path-badge mono">8 a second by default</span>
                </div>
                <div class="path-note">
                  Issues a ticket, holds the line in Redis, and admits at a fixed rate so the
                  database is never asked to serialise more than it can.
                </div>
              </div>

              <div class="hop"><div class="hop-line" aria-hidden="true"></div><span class="hop-label mono">a bounded trickle, in arrival order</span></div>

              <div class="path-node">
                <div class="path-node-head">
                  <span class="path-name mono">booking-service</span>
                  <span class="path-badge mono">20 Oracle sessions at most</span>
                </div>
                <div class="path-note">
                  Takes one seat per confirmed booking, under a lock, idempotently. A retry cannot
                  claim a second seat.
                </div>
              </div>
            </div>

            <div class="hop"><div class="hop-line strong" aria-hidden="true"></div><span class="hop-label mono">one conditional update per claim</span></div>

            <div class="path-node">
              <div class="path-node-head">
                <span class="path-name mono">Oracle</span>
                <span class="path-badge mono">250 seats, one row</span>
              </div>
              <div class="path-note">
                Zero rows affected means sold out. The limit lives in the database, so no ordering
                mistake above it can invent a 251st seat.
              </div>
            </div>

            <div class="hop"><div class="hop-line strong" aria-hidden="true"></div><span class="hop-label mono">read once by the state provider</span></div>

            <!--
              Read from the same field the alert rule reads, not a constant. A
              hard-coded 0 here would be the one number on the page a visitor
              cannot trust, and it is the number the whole build is about.
            -->
            <div class="path-node claim">
              <div class="path-node-head">
                <span class="path-name mono">Oversold</span>
                @if (state.view()?.drop?.available) {
                  <span class="path-badge mono" [class.badge-good]="oversold() === 0" [class.badge-bad]="oversold() !== 0">{{ oversold() }}</span>
                } @else {
                  <span class="path-badge mono">—</span>
                }
              </div>
              <div class="path-note">
                The dashboard below reads this from the same place the alert would. Try to move it.
              </div>
            </div>
          </div>
        </div>

        <div class="band">
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

        <div class="band">
          <div class="band-head eyebrow">Built with</div>
          <div class="chips">
            @for (item of stack; track item) {
              <span class="stack-chip">{{ item }}</span>
            }
          </div>
        </div>
      </section>

      <rb-canonical-drop />

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Cluster resources</h2>
            <p class="sub">What is actually running, read live from the Kubernetes API</p>
          </div>
          <button class="btn-tertiary" (click)="cluster.emit()">See more</button>
        </div>
        <rb-cluster-resources />
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Deploy history</h2>
            <p class="sub">From the Deployments' own annotations</p>
          </div>
        </div>
        <rb-placeholder
          note="Not read yet. The rows come from each Deployment's own rollout annotations rather than a table this console keeps, so they arrive with the cluster reader that task 8 wires up." />
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Links out</h2>
            <p class="sub">Three places to look when this page is not enough</p>
          </div>
        </div>
        <div class="links">
          @for (link of outward; track link.href) {
            <a class="link-card" [href]="link.href" target="_blank" rel="noreferrer">
              <span class="link-mark" [class]="'mark-' + link.mark" aria-hidden="true">
                @switch (link.mark) {
                  @case ('openshift') {
                    <svg viewBox="0 0 24 24" width="18" height="18">
                      <path d="M12 3.2 19.4 7v10L12 20.8 4.6 17V7Z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
                      <circle cx="12" cy="12" r="3" fill="currentColor" />
                    </svg>
                  }
                  @case ('dynatrace') {
                    <svg viewBox="0 0 24 24" width="18" height="18">
                      <path d="M12 3.2 19.4 7v10L12 20.8 4.6 17V7Z" fill="currentColor" />
                      <path d="M8 12h8M12 8v8" stroke="#FFFFFF" stroke-width="1.8" stroke-linecap="round" />
                    </svg>
                  }
                  @case ('splunk') {
                    <svg viewBox="0 0 24 24" width="18" height="18">
                      <path d="M5 5.5 17 12 5 18.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                  }
                }
              </span>
              <span class="link-text">
                <span class="link-name">{{ link.name }}</span>
                <span class="link-note">{{ link.note }}</span>
              </span>
              <svg class="link-out" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
                <path d="M9 15 19 5M13 5h6v6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
              </svg>
            </a>
          }
        </div>
      </section>

      <!--
        Last on the page on purpose: the written record is what a reader goes to
        once the live thing above has convinced them there is something to read
        about.
      -->
      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Documentation</h2>
            <p class="sub">
              {{ total() }} specs, build notes and plans, rendered from Markdown baked into the image
            </p>
          </div>
          <button class="btn-tertiary" (click)="docs.emit()">See all {{ total() }}</button>
        </div>
        @if (groups().length) {
          <div class="doc-groups">
            @for (group of groups(); track group.key) {
              <div class="doc-group">
                <div class="doc-group-head">
                  <span class="group-chip eyebrow">{{ group.label }}</span>
                  <span class="group-count">{{ group.count }} in this group</span>
                </div>
                @for (doc of group.docs; track doc.id) {
                  <button class="doc-row" (click)="open.emit(doc.id)">
                    <span class="doc-title">{{ doc.title }}</span>
                    <span class="doc-blurb">{{ blurb(doc.id) }}</span>
                  </button>
                }
              </div>
            }
          </div>
        } @else if (docsService.listError(); as problem) {
          <rb-placeholder [note]="problem" />
        } @else {
          <rb-placeholder note="Loading the documentation baked into the image." />
        }
      </section>

      @if (state.transportError(); as problem) {
        <p class="reason">The numbers above may be stale: {{ problem }}.</p>
      }
    </div>
  `,
  styles: `
    /*
      auto-fit with a 360px floor rather than a media query: the two columns sit
      side by side wherever there is room for both and stack wherever there is
      not, including inside the narrow viewport the docs drawer leaves behind.
    */
    .hero {
      padding: 36px 24px 32px;
      display: grid;
      gap: 40px;
      grid-template-columns: repeat(auto-fit, minmax(min(360px, 100%), 1fr));
      align-items: start;
    }
    .hero-left { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 18px; }
    .hero-right { min-width: 0; }

    .moves { border-top: 1px solid var(--rule); padding-top: 22px; display: flex; flex-direction: column; gap: 14px; width: 100%; }
    .moves-head { font-size: 17px; font-weight: 700; }
    .moves-sub { margin: 2px 0 0; font-size: 14px; color: var(--muted); text-wrap: pretty; }
    .move-list { display: flex; flex-direction: column; gap: 12px; }
    .move { display: flex; gap: 12px; align-items: flex-start; }
    .move-num {
      flex: none;
      width: 28px;
      height: 28px;
      border-radius: 999px;
      background: var(--dhl-yellow);
      display: grid;
      place-items: center;
      font-size: 13px;
      font-weight: 700;
    }
    .move-name { font-size: 15px; font-weight: 700; }
    .move-note { font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }

    .path-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 4px 12px;
      margin-bottom: 18px;
    }
    .path-title { font-size: 17px; font-weight: 700; }
    .path-count { font-size: 12px; color: var(--muted); letter-spacing: .04em; }

    .path-node { background: var(--white); border: 1px solid var(--line); border-radius: 4px; padding: 14px 16px; }
    .path-node-head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 4px 12px;
    }
    .path-name { font-size: 14px; font-weight: 700; letter-spacing: .02em; }
    .path-badge {
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .04em;
      background: var(--chip-neutral-bg);
      color: var(--chip-neutral-fg);
      border-radius: 2px;
      padding: 2px 8px;
      white-space: nowrap;
    }
    .path-note { font-size: 13px; color: var(--ink-soft); margin-top: 6px; text-wrap: pretty; }

    .claim { background: var(--ink); border-color: var(--ink); color: var(--white); }
    .claim .path-note { color: var(--on-dark-soft); }
    .badge-good { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .badge-bad { background: var(--chip-bad-bg); color: var(--chip-bad-fg); }

    /* The connector between two boxes, and the label for what crosses it. */
    .hop { display: flex; align-items: stretch; gap: 10px; padding: 8px 0 8px 22px; }
    .hop-line { position: relative; flex: none; width: 2px; min-height: 26px; background: var(--muted); }
    .hop-line.strong { background: var(--ink); }
    .hop-line::after {
      content: '';
      position: absolute;
      left: -4px;
      bottom: 0;
      width: 0;
      height: 0;
      border-left: 5px solid transparent;
      border-right: 5px solid transparent;
      border-top: 7px solid currentColor;
      color: var(--muted);
    }
    .hop-line.strong::after { color: var(--ink); }
    .hop-label { font-size: 11px; letter-spacing: .04em; color: var(--muted); align-self: center; text-wrap: pretty; }

    .zone { border: 1px dashed var(--muted); border-radius: 4px; padding: 14px; background: var(--white); }
    .zone-head { font-size: 11px; letter-spacing: .06em; color: var(--muted); margin-bottom: 12px; }
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
      max-width: 22ch;
      text-wrap: balance;
    }
    .hero-lede { margin: 0; font-size: 17px; color: var(--ink-soft); max-width: 56ch; text-wrap: pretty; }
    .hero-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-top: 4px; }
    .hero-note { margin: 0; font-size: 14px; color: var(--muted); max-width: 52ch; text-wrap: pretty; }

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
export class PublicHome implements OnInit {
  readonly cluster = output<void>();
  readonly docs = output<void>();
  readonly open = output<string>();
  readonly visitor = output<void>();

  protected readonly state = inject(StateService);
  protected readonly docsService = inject(DocsService);

  /** Named because a hiring manager reads the list before reading the code. */
  /** The three things a visitor can actually do, in the order the page offers them. */
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

  readonly stack = [
    'Java 25',
    'Spring Boot 4',
    'Oracle',
    'Redis',
    'OpenShift',
    'Ansible',
    'GitHub Actions',
    'Prometheus',
    'Dynatrace',
    'Splunk'
  ];

  readonly outward: Outward[] = [
    {
      name: 'OpenShift console',
      note: 'Workloads, routes and quota for the namespace this page reads.',
      href: 'https://console-openshift-console.apps.rm3.7wse.p1.openshiftapps.com',
      mark: 'openshift'
    },
    {
      name: 'Dynatrace tenant',
      note: 'Traces through the gate and into booking-service. Trial, may have expired.',
      href: 'https://icp44821.apps.dynatrace.com',
      mark: 'dynatrace'
    },
    {
      name: 'Splunk stack',
      note: 'Structured logs and the audit trail. Trial, may have expired.',
      href: 'https://prd-p-2d10o.splunkcloud.com',
      mark: 'splunk'
    }
  ];

  private static readonly ORDER = ['specs', 'notes', 'plans'];
  private static readonly PER_GROUP = 3;

  readonly total = computed(() => (this.docsService.summaries() ?? []).length);

  /** Up to three per group here; the documentation page carries the rest. */
  readonly groups = computed(() => {
    const list = this.docsService.summaries() ?? [];
    return PublicHome.ORDER
      .map((key) => {
        const all = list.filter((doc) => doc.group === key);
        return {
          key,
          label: `${GROUP_LABELS[key]}s`,
          count: all.length,
          docs: all.slice(0, PublicHome.PER_GROUP)
        };
      })
      .filter((group) => group.count > 0);
  });

  constructor() {
    // The blurbs need the list first, so they are asked for again once it lands.
    effect(() => {
      if (this.docsService.summaries()) {
        this.docsService.loadBlurbs();
      }
    });
  }

  blurb(id: string): string {
    return this.docsService.blurbs()[id] ?? 'Opens the rendered document.';
  }

  ngOnInit(): void {
    this.docsService.load();
  }
}

import { Component, computed, signal } from '@angular/core';

/** One question asked of the logs, and the answer it came back with. */
interface Scenario {
  /** What someone would actually want to know. */
  question: string;
  /** The search that answers it. Shown verbatim — it is the evidence. */
  spl: string;
  /** What the capture shows, in the words of someone reading it. */
  reading: string;
  /** File under public/evidence/splunk, served from the web root. */
  shot: string;
  /** Splunk's own relative time expressions for the deep link. */
  earliest: string;
  latest: string;
}

const SPLUNK = 'https://prd-p-2d10o.splunkcloud.com';

/**
 * What the logs are actually good for, asked as questions rather than listed as
 * a feature.
 *
 * <h2>Why screenshots and a link, rather than one or the other</h2>
 * The panel above this one reports whether Splunk is receiving anything, which
 * is all the cluster can honestly claim: it holds a write-only HEC token and
 * cannot read a single event back. So the answers here are captures.
 *
 * A capture is not a weaker version of a live embed, it is a different and
 * better thing for this page. Splunk sits behind its own login, so an embed
 * would show most readers a sign-in screen and nothing else; and the tenant is
 * a trial, so an embed would break for good the day it lapses. The deep link is
 * there beside each one for the reader who does have an account, carrying the
 * query and the time window so it opens on the same question.
 *
 * <h2>Why the searches are shown in full</h2>
 * The screenshot is the claim and the SPL is the proof. A reader who doubts the
 * numbers can paste the query into their own Splunk and get their own answer,
 * which is the whole difference between showing evidence and asserting a result.
 *
 * <h2>A shot whose file is missing removes itself</h2>
 * These captures are taken by hand against a live tenant, so they can go stale
 * or be replaced. Rather than a broken frame, an image that fails to load drops
 * its whole card — the same rule {@link EvidenceStrip} follows, and it means
 * recapturing is only ever dropping a PNG in.
 */
@Component({
  selector: 'rb-log-scenarios',
  template: `
    <section class="panel">
      <div class="accent-top"></div>

      <header class="head">
        <div class="head-text">
          <h2 class="title">What the logs answer</h2>
          <p class="sub">
            Four questions, each with the search that answers it and the answer it came
            back with.
          </p>
        </div>
        <span class="pill">{{ visible().length }} searches</span>
      </header>

      <div class="bar">
        <span class="mono">source="rembayung"</span>
        <span class="bar-note">
          Captured rather than embedded: Splunk needs a login, and this tenant is a trial
          that will one day lapse.
        </span>
      </div>

      <div class="list">
        @for (item of visible(); track item.shot) {
          <article class="scenario">
            <div class="text">
              <h3 class="q">{{ item.question }}</h3>
              <p class="reading">{{ item.reading }}</p>
              <code class="spl">{{ item.spl }}</code>
              <a class="link" [href]="href(item)" target="_blank" rel="noreferrer">
                Run this search
                <svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">
                  <path d="M9 15 19 5M13 5h6v6" fill="none" stroke="currentColor"
                        stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </a>
            </div>
            <div class="shot-col">
              <a class="frame" [href]="item.shot" target="_blank" rel="noreferrer"
                 [attr.aria-label]="'Open the full screenshot: ' + item.question">
                <img [src]="item.shot" [alt]="item.question + ': ' + item.reading"
                     loading="lazy" (error)="missing(item.shot)" />
              </a>
              <span class="enlarge">Cropped to fit. Open for the full screen</span>
            </div>
          </article>
        }
      </div>

      <p class="foot">
        <code>source</code> is stamped on every event by the log appender, so these match
        whatever actually arrived rather than depending on a field extraction being
        configured.
      </p>
    </section>
  `,
  styles: `
    .panel {
      position: relative;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .accent-top { height: 3px; background: var(--dhl-red); }

    .head {
      padding: 20px;
      display: flex; flex-wrap: wrap; gap: 8px 24px;
      align-items: baseline; justify-content: space-between;
    }
    .head-text { min-width: 0; }
    .title { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.015em; line-height: 1.25; }
    .sub { margin: 4px 0 0; font-size: 15px; color: var(--muted); max-width: 60ch; text-wrap: pretty; }

    .pill {
      display: inline-flex; align-items: center;
      font-size: 13px; font-weight: 700; letter-spacing: .04em;
      padding: 6px 13px; border-radius: 999px; white-space: nowrap;
      background: var(--canvas); color: var(--ink-soft);
    }

    .bar {
      padding: 10px 20px;
      background: #FCFBFA;
      border-top: 1px solid var(--rule);
      border-bottom: 1px solid var(--rule);
      display: flex; flex-wrap: wrap; gap: 4px 16px; align-items: baseline;
    }
    .bar .mono {
      font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
      font-size: 12px; color: var(--ink);
    }
    .bar-note { font-size: 13px; color: var(--muted); text-wrap: pretty; }

    .list { display: flex; flex-direction: column; }

    .scenario {
      display: grid;
      grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
      gap: 20px;
      align-items: start;
      padding: 20px;
      border-top: 1px solid var(--rule);
    }
    /* One column on narrow screens: the screenshot is unreadable below about
       420px of width, and a squeezed frame is worse than a stacked one. */
    @media (max-width: 860px) {
      .scenario { grid-template-columns: minmax(0, 1fr); }
    }

    .text { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .q { margin: 0; font-size: 15px; font-weight: 700; }
    .reading { margin: 0; font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }

    .spl {
      display: block;
      background: var(--canvas);
      border: 1px solid var(--line);
      border-radius: 3px;
      padding: 8px 10px;
      font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
      font-size: 12px;
      line-height: 1.5;
      color: var(--ink);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }

    .link {
      align-self: flex-start;
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 13px;
      font-weight: 700;
      color: var(--ink);
      text-decoration: none;
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 4px 12px;
    }
    .link:hover { border-color: var(--dhl-red); color: var(--dhl-red); }

    .shot-col { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
    .enlarge { font-size: 12px; color: var(--muted); }

    .frame {
      display: block;
      border: 1px solid var(--line);
      border-radius: 3px;
      overflow: hidden;
      background: #0f1013;
      aspect-ratio: 16 / 7;
    }
    .frame img { display: block; width: 100%; height: 100%; object-fit: cover; object-position: top left; }
    .frame:hover img { opacity: .92; }

    .foot {
      margin: 0;
      padding: 12px 18px 16px;
      border-top: 1px solid var(--rule);
      font-size: 13px;
      color: var(--muted);
      text-wrap: pretty;
    }
    .foot code {
      font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, monospace);
      font-size: 12px;
      color: var(--ink-soft);
    }
  `
})
export class LogScenarios {
  private readonly all: Scenario[] = [
    {
      question: 'Is every service actually shipping?',
      spl: 'source="rembayung" | stats count by message.service, severity',
      reading:
        'All three, split by level. queue-gate carries the traffic, booking-service the '
        + 'writes, and the console its own polling. One row missing here is a service '
        + 'whose logs are going nowhere.',
      shot: '/evidence/splunk/services.png',
      earliest: '-60m',
      latest: 'now'
    },
    {
      question: 'What did the rush actually do?',
      spl: 'source="rembayung" message.outcome=* | stats count by message.service, message.outcome',
      reading:
        'The outcome of every request that reached a decision. This is the seat count '
        + 'argued from the log rather than from the database, and it has to agree with '
        + 'the oversold counter, or one of the two is lying.',
      shot: '/evidence/splunk/outcomes.png',
      earliest: '-60m',
      latest: 'now'
    },
    {
      question: 'Is any of this searchable, or just text?',
      spl: 'source="rembayung"',
      reading:
        'The field list down the left is the point. Every line ships as JSON, so ticket, '
        + 'position, dropId and admitted are fields to filter on, not words to grep for. '
        + 'That is the whole reason for the JSON encoder over a pretty pattern.',
      shot: '/evidence/splunk/fields.png',
      earliest: '-60m',
      latest: 'now'
    },
    {
      question: 'Does it catch anything real?',
      spl: 'source="rembayung" severity IN (WARN, ERROR)'
        + ' | table _time message.service logger message.message',
      reading:
        'It already did. This is 126 warnings in an hour from one browser tab left open on '
        + 'a sandbox that no longer existed, and the line named the service but not the '
        + 'drop, so it identified nothing. Both were fixed once the logs made it visible.',
      shot: '/evidence/splunk/warnings.png',
      earliest: '-60m',
      latest: 'now'
    }
  ];

  /** Files the browser could not load; recorded so they stop being rendered. */
  private readonly absent = signal<ReadonlySet<string>>(new Set());

  protected readonly visible = computed(() =>
    this.all.filter((item) => !this.absent().has(item.shot)));

  /**
   * Splunk's search app takes the query in `q`, and it must carry the leading
   * `search` command — without it the app opens on an error rather than on the
   * results. earliest and latest go beside it so the link lands on the same
   * window the capture was taken in.
   */
  protected href(item: Scenario): string {
    const q = encodeURIComponent(`search ${item.spl}`);
    return `${SPLUNK}/en-US/app/search/search`
      + `?q=${q}&earliest=${item.earliest}&latest=${item.latest}`;
  }

  protected missing(shot: string): void {
    this.absent.update((was) => new Set(was).add(shot));
  }
}

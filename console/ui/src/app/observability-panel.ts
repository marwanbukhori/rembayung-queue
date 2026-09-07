import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ObservabilityService } from './observability.service';
import { Feed } from './state';

/**
 * Whether the two observability vendors are actually receiving anything.
 *
 * <h2>Why a panel and not just the links</h2>
 * An empty Splunk search reads identically whether shipping is broken or the
 * application simply had nothing to say — and that ambiguity has already cost
 * one long afternoon here, when the answer turned out to be that queue-gate and
 * booking-service contained three log statements between them, none on the
 * request path. Both vendors are also trial tenants whose UIs stall and log out,
 * so this also has to answer the question when neither UI opens.
 *
 * <h2>What each row is claiming</h2>
 * Splunk's row is a live call to the collector's health endpoint, with the round
 * trip it took: "healthy" and "healthy but four seconds away" are different
 * states. Dynatrace has no readable equivalent from inside the cluster, so its
 * row reports the strongest fact pod state supports — a Ready pod carrying the
 * agent flag could not have reached Ready without the agent, because -agentpath
 * cannot be made conditional and the JVM will not start without the library.
 *
 * Neither claims to show logs or traces. Reading those back needs credentials
 * this cluster does not hold, and a panel that pretended otherwise would be
 * showing something else.
 *
 * <h2>No credentials on this page</h2>
 * The design this follows had a modal offering a shared viewer login with a
 * copy button. It is not implemented and should not be: this repository is
 * public, so a password written into the page source is a working credential
 * published next to the URL it opens. The links go to each vendor's own sign-in.
 */
@Component({
  selector: 'rb-observability-panel',
  template: `
    @if (status(); as s) {
      <section class="panel">
        <div class="accent-top"></div>

        <header class="head">
          <div class="head-text">
            <h2 class="title">Monitoring pipelines</h2>
            <p class="sub">Probed from this cluster, not taken on trust.</p>
          </div>
          <span class="pill" [class.ok]="bothUp()" [class.bad]="!bothUp()">
            <span class="dot" [class.beat]="bothUp()"></span>
            {{ bothUp() ? 'Both monitors online' : 'Check the rows below' }}
          </span>
        </header>

        <div class="probe-bar">
          <span class="probed mono">{{ probedLabel() }}</span>
          <span class="spacer"></span>
          <button class="btn-probe" type="button"
                  [disabled]="observability.probing()"
                  (click)="observability.refresh()">
            @if (observability.probing()) { <span class="spinner"></span> }
            {{ observability.probing() ? 'Probing' : 'Probe again' }}
          </button>
        </div>

        <!-- Splunk -->
        <div class="vendor-block">
          <div class="vendor-grid">
            <div class="vendor-id">
              <div class="vendor-name-row">
                <span class="vendor-name">Splunk</span>
                <span class="pill sm" [class.ok]="s.splunk.reachable" [class.bad]="!s.splunk.reachable">
                  <span class="dot" [class.beat]="s.splunk.reachable"></span>
                  {{ s.splunk.reachable ? 'Collector up' : 'No answer' }}
                </span>
              </div>
              <p class="vendor-what">What happened: every pod's log lines, searchable.</p>
              <dl class="facts">
                <dt>Endpoint</dt><dd class="mono">{{ s.splunk.endpoint }}</dd>
                <dt>Probe</dt><dd class="mono soft">{{ splunkProbe() }}</dd>
              </dl>
              <a class="open" [href]="splunkHref()" target="_blank" rel="noreferrer">
                Open the search {{ arrow }}
              </a>
            </div>

            <div class="feeds">
              <div class="feeds-head">
                <span class="feeds-label">SHIPPING LOGS</span>
                <span class="feeds-count mono">{{ onCount(s.splunk.shippers) }} of {{ s.splunk.shippers.length }}</span>
              </div>
              <dl class="feed-list">
                @for (f of s.splunk.shippers; track f.service) {
                  <div class="bar" [class.on]="f.on"></div>
                  <dt class="mono" [class.off]="!f.on">{{ f.service }}</dt>
                  <dd>{{ f.detail }}</dd>
                }
              </dl>
            </div>
          </div>
        </div>

        <!-- Dynatrace -->
        <div class="vendor-block last">
          <div class="vendor-grid">
            <div class="vendor-id">
              <div class="vendor-name-row">
                <span class="vendor-name">Dynatrace</span>
                <span class="pill sm" [class.ok]="agentCount() > 0" [class.bad]="agentCount() === 0">
                  <span class="dot" [class.beat]="agentCount() > 0"></span>
                  {{ agentCount() > 0 ? agentCount() + ' agents loaded' : 'No agents' }}
                </span>
              </div>
              <p class="vendor-what">Where the time went: the Oracle round trip, hop by hop.</p>
              <dl class="facts">
                <dt>Tenant</dt><dd class="mono">{{ s.dynatrace.tenant }}</dd>
                <dt>Mode</dt><dd class="mono soft">{{ s.dynatrace.mode }}</dd>
              </dl>
              <a class="open" [href]="dynatraceHref()" target="_blank" rel="noreferrer">
                Open the traces {{ arrow }}
              </a>
            </div>

            <div class="feeds">
              <div class="feeds-head">
                <span class="feeds-label">ONEAGENT IN THE JVM</span>
                <span class="feeds-count mono">{{ agentCount() }} of {{ s.dynatrace.instrumented.length }}</span>
              </div>
              <dl class="feed-list">
                @for (f of s.dynatrace.instrumented; track f.service) {
                  <div class="bar" [class.on]="f.on"></div>
                  <dt class="mono" [class.off]="!f.on">{{ f.service }}</dt>
                  <dd>{{ f.detail }}</dd>
                }
              </dl>
            </div>
          </div>
        </div>

        <p class="caveat">
          Logs live in Splunk only. This OneAgent flavour ships traces and the service map and
          no logs, so a log search in Dynatrace comes up empty by design.
        </p>
      </section>
    }
  `,
  styles: `
    .panel {
      container-type: inline-size;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .accent-top { height: 4px; background: var(--dhl-yellow); }

    .head {
      padding: 20px;
      display: flex; flex-wrap: wrap; gap: 8px 24px;
      align-items: baseline; justify-content: space-between;
    }
    .head-text { min-width: 0; }
    .title { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.015em; line-height: 1.25; }
    .sub { margin: 4px 0 0; font-size: 15px; color: var(--muted); max-width: 60ch; text-wrap: pretty; }

    .pill {
      display: inline-flex; align-items: center; gap: 7px;
      font-size: 13px; font-weight: 700; letter-spacing: .04em;
      padding: 6px 13px; border-radius: 999px; white-space: nowrap;
    }
    .pill.sm { font-size: 12px; padding: 4px 11px; }
    .pill.ok { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .pill.bad { background: var(--chip-bad-bg); color: var(--chip-bad-fg); }
    .dot { width: 7px; height: 7px; border-radius: 50%; flex: none; background: currentColor; }
    .beat { animation: livePulse 2s ease-in-out infinite; }
    @keyframes livePulse { 0%, 100% { opacity: 1; } 50% { opacity: .25; } }

    .probe-bar {
      padding: 10px 20px;
      background: #FCFBFA;
      border-top: 1px solid var(--rule);
      border-bottom: 1px solid var(--rule);
      display: flex; flex-wrap: wrap; gap: 8px 20px; align-items: center;
    }
    .probed { font-size: 13px; color: #656565; white-space: nowrap; }
    .spacer { flex: 1 1 20px; }
    .btn-probe {
      display: inline-flex; align-items: center; justify-content: center; gap: 8px;
      font: inherit; font-size: 13px; font-weight: 700;
      padding: 0 14px; min-height: 38px; border-radius: 4px; flex: none;
      background: transparent; color: var(--ink); border: 2px solid var(--ink);
      cursor: pointer;
      transition: background 120ms var(--ease), color 120ms var(--ease), border-color 120ms var(--ease);
    }
    .btn-probe:hover:not(:disabled) { background: var(--ink); color: var(--white); }
    .btn-probe:disabled {
      background: var(--disabled-bg); color: var(--disabled-fg);
      border-color: transparent; cursor: not-allowed;
    }
    .spinner {
      width: 12px; height: 12px; border-radius: 50%; flex: none;
      border: 2px solid #C9C9C9; border-top-color: var(--muted);
      animation: spin 700ms linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    .vendor-block { padding: 20px; border-bottom: 1px solid var(--rule); }
    .vendor-block.last { border-bottom: 0; }
    .vendor-grid {
      display: grid; gap: 20px 32px;
      grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr));
      align-items: start;
    }
    .vendor-id { min-width: 0; display: flex; flex-direction: column; gap: 10px; }
    .vendor-name-row { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
    .vendor-name { font-size: 20px; font-weight: 700; letter-spacing: -0.01em; }
    .vendor-what { margin: 0; font-size: 15px; color: var(--ink-soft); max-width: 46ch; text-wrap: pretty; }

    .facts {
      margin: 0; display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 4px 12px; align-items: baseline;
    }
    .facts dt { font-size: 13px; color: var(--muted); }
    .facts dd { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
    .facts dd.soft { color: var(--ink-soft); }

    .open {
      margin-top: 2px; font-size: 15px; font-weight: 700;
      color: var(--dhl-red); text-decoration: none;
    }
    .open:hover { text-decoration: underline; }

    .feeds { min-width: 0; }
    .feeds-head {
      display: flex; flex-wrap: wrap; gap: 4px 12px;
      align-items: baseline; justify-content: space-between;
      padding-bottom: 8px; border-bottom: 1px solid var(--rule);
    }
    .feeds-label { font-size: 12px; font-weight: 700; letter-spacing: .08em; color: var(--muted); }
    .feeds-count { font-size: 13px; color: var(--ink); }

    /* A 3px status bar, the name, and what the reading was — the bar carries the
       state so the row needs no tick, and a column of bars is scannable at a
       glance in a way a column of words is not. */
    .feed-list { margin: 0; display: grid; grid-template-columns: 3px auto minmax(0, 1fr); gap: 0; }
    .bar { background: var(--line); box-shadow: 0 1px 0 var(--white); }
    .bar.on { background: var(--chip-ok-fg); }
    .feed-list dt {
      font-size: 14px; font-weight: 700; white-space: nowrap; color: var(--ink);
      padding: 9px 0 9px 12px; border-bottom: 1px solid #F2F0EC;
    }
    .feed-list dt.off { color: var(--muted); }
    .feed-list dd {
      margin: 0; font-size: 14px; color: var(--muted); min-width: 0;
      text-wrap: pretty; text-align: right;
      padding: 9px 0 9px 16px; border-bottom: 1px solid #F2F0EC;
    }
    .feed-list > :nth-last-child(-n+3) { border-bottom: 0; }

    .caveat {
      margin: 0; padding: 14px 20px;
      font-size: 14px; color: var(--muted); max-width: 90ch; text-wrap: pretty;
      border-top: 1px solid var(--rule);
    }

    .mono { font-family: var(--mono); }

    @media (prefers-reduced-motion: reduce) {
      .beat, .spinner { animation: none; }
    }
  `
})
export class ObservabilityPanel {
  protected readonly observability = inject(ObservabilityService);

  private static readonly SPLUNK = 'https://prd-p-2d10o.splunkcloud.com';
  private static readonly DYNATRACE = 'https://icp44821.apps.dynatrace.com';

  /** In the template rather than inline, because a backtick or an angle bracket
   *  in a styles template literal has terminated this file's string before. */
  protected readonly arrow = '↗';

  protected readonly status = computed(() => this.observability.status());

  /** Ticks so "last probed Ns ago" counts up between polls rather than jumping. */
  private readonly now = signal(Date.now());

  constructor() {
    // In the constructor, not ngOnInit. inject() only works inside an injection
    // context; calling it from a lifecycle hook compiles cleanly and then throws
    // NG0203 the first time the component is created.
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /**
   * How many agents are loaded, not whether all of them are.
   *
   * "All" was the first spelling and it read NO AGENTS against a namespace where
   * both services on the booking path were instrumented — because redis is in
   * the list and redis will never carry a Java agent.
   */
  protected readonly agentCount = computed(
    () => (this.status()?.dynatrace.instrumented ?? []).filter((f: Feed) => f.on).length
  );

  /** Both halves alive: the collector answered, and at least one JVM is traced. */
  protected readonly bothUp = computed(
    () => (this.status()?.splunk.reachable ?? false) && this.agentCount() > 0
  );

  protected onCount(feeds: Feed[]): number {
    return feeds.filter((f) => f.on).length;
  }

  /** The collector's own words plus what the round trip cost. */
  protected splunkProbe(): string {
    const s = this.status()?.splunk;
    if (!s) {
      return '';
    }
    return s.latencyMs >= 0 ? `${s.detail}, ${s.latencyMs}ms` : s.detail;
  }

  protected probedLabel(): string {
    if (this.observability.probing()) {
      return 'probing both now';
    }
    const at = this.status()?.checkedAt;
    if (!at) {
      return 'not probed yet';
    }
    const seconds = Math.max(0, Math.round((this.now() - Date.parse(at)) / 1000));
    if (seconds < 2) {
      return 'last probed just now';
    }
    return seconds < 120
      ? `last probed ${seconds}s ago`
      : `last probed ${Math.round(seconds / 60)}m ago`;
  }

  protected splunkHref(): string {
    // source, not service: the appender sets source=rembayung on every event it
    // ships, so this matches whatever arrived. Searching `service` instead
    // depends on Splunk extracting that field out of the JSON body, and an
    // unconfigured extraction gives an empty search over data that is there.
    const query = encodeURIComponent('search source="rembayung"');
    return `${ObservabilityPanel.SPLUNK}/en-US/app/search/search?earliest=-60m&latest=now&q=${query}`;
  }

  protected dynatraceHref(): string {
    return `${ObservabilityPanel.DYNATRACE}/ui/apps/dynatrace.distributedtraces/?gtf=-60m`;
  }
}

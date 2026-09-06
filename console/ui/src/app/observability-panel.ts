import { Component, computed, inject } from '@angular/core';
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
 * request path. Both vendors are trial tenants whose UIs log out and stall, so
 * this also has to answer the question when neither UI opens.
 *
 * <h2>What each row is claiming</h2>
 * Splunk's row is a live call to the collector's health endpoint. Dynatrace has
 * no readable equivalent, so its row reports the strongest fact available from
 * pod state: the JVM refuses to start on a bad -agentpath and the init container
 * exits when the credential is missing, so a Ready pod carrying the agent flag
 * cannot have reached Ready without the agent. Neither claims to show logs or
 * traces. Reading those back needs credentials this cluster does not hold, and a
 * panel that pretended otherwise would be showing something else.
 */
@Component({
  selector: 'rb-observability-panel',
  template: `
    @if (status(); as s) {
      <div class="card">
        <div class="why">Are the monitors actually receiving anything?</div>
        <p class="note">
          Checked from here, not taken on trust — a live call to Splunk's collector, and the
          agent state of every JVM in the namespace. Neither vendor can be read back from this
          cluster: the tokens it holds can write logs and install an agent, nothing else.
        </p>

        <div class="grid">
          <!-- Splunk -->
          <section class="vendor">
            <header>
              <span class="name">Splunk</span>
              <span class="pill" [class.ok]="s.splunk.reachable" [class.bad]="!s.splunk.reachable">
                <span class="dot" [class.beat]="s.splunk.reachable"></span>
                {{ s.splunk.reachable ? 'COLLECTOR UP' : 'NO ANSWER' }}
              </span>
            </header>
            <p class="role">What happened — every pod's log lines in one searchable place.</p>
            <dl>
              <dt>Endpoint</dt><dd class="mono">{{ s.splunk.endpoint }}</dd>
              <dt>Reply</dt><dd>{{ s.splunk.detail }}</dd>
            </dl>
            <div class="feeds">
              <div class="feeds-head">Shipping logs to it</div>
              @for (f of s.splunk.shippers; track f.service) {
                <div class="feed">
                  <span class="mark" [class.on]="f.on">{{ f.on ? '✓' : '✕' }}</span>
                  <span class="svc mono">{{ f.service }}</span>
                  <span class="detail">{{ f.detail }}</span>
                </div>
              }
              @if (!s.splunk.shippers.length) {
                <div class="empty">No pods readable from here.</div>
              }
            </div>
            <a class="open" [href]="splunkHref()" target="_blank" rel="noreferrer">
              Open the search {{ arrow }}
            </a>
          </section>

          <!-- Dynatrace -->
          <section class="vendor">
            <header>
              <span class="name">Dynatrace</span>
              <span class="pill" [class.ok]="agentCount() > 0" [class.bad]="agentCount() === 0">
                <span class="dot" [class.beat]="agentCount() > 0"></span>
                {{ agentCount() > 0 ? agentCount() + ' AGENTS LOADED' : 'NO AGENTS' }}
              </span>
            </header>
            <p class="role">Where the time went — the ~2.7s Oracle round trip, hop by hop.</p>
            <dl>
              <dt>Tenant</dt><dd class="mono">{{ s.dynatrace.tenant }}</dd>
              <dt>Mode</dt><dd>{{ s.dynatrace.mode }}</dd>
            </dl>
            <div class="feeds">
              <div class="feeds-head">OneAgent inside the JVM</div>
              @for (f of s.dynatrace.instrumented; track f.service) {
                <div class="feed">
                  <span class="mark" [class.on]="f.on">{{ f.on ? '✓' : '✕' }}</span>
                  <span class="svc mono">{{ f.service }}</span>
                  <span class="detail">{{ f.detail }}</span>
                </div>
              }
              @if (!s.dynatrace.instrumented.length) {
                <div class="empty">No pods readable from here.</div>
              }
            </div>
            <a class="open" [href]="dynatraceHref()" target="_blank" rel="noreferrer">
              Open the traces {{ arrow }}
            </a>
          </section>
        </div>

        <p class="caveat">
          {{ s.dynatrace.detail }} Looking for logs in Dynatrace will always come up empty —
          that is the flavour of agent, not a fault.
        </p>
      </div>
    }
  `,
  styles: `
    .card { container-type: inline-size; }
    .why { font-size: 19px; font-weight: 700; margin-bottom: 8px; }
    .note { margin: 0 0 18px; font-size: 14px; color: var(--ink-soft); max-width: 78ch; text-wrap: pretty; }

    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
    @container (max-width: 720px) { .grid { grid-template-columns: 1fr; } }

    .vendor {
      display: flex; flex-direction: column;
      border: 1px solid var(--line); border-radius: 6px; padding: 14px 16px;
      background: var(--white);
    }
    .vendor header { display: flex; align-items: center; gap: 10px; }
    .name { font-size: 16px; font-weight: 700; }

    .pill {
      display: inline-flex; align-items: center; gap: 6px;
      margin-left: auto;
      font-size: 11px; font-weight: 700; letter-spacing: .07em;
      padding: 3px 9px; border-radius: 999px; border: 1px solid transparent;
    }
    .pill.ok { background: var(--chip-ok-bg); color: var(--chip-ok-fg); border-color: currentColor; }
    .pill.bad { background: var(--chip-warn-bg); color: var(--dhl-red); border-color: currentColor; }
    .dot { width: 7px; height: 7px; border-radius: 999px; background: currentColor; }
    .beat { animation: beat 1.6s ease-in-out infinite; }
    @keyframes beat { 50% { opacity: .25; } }

    .role { margin: 8px 0 12px; font-size: 13px; color: var(--ink-soft); }

    dl { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; margin: 0 0 14px; font-size: 13px; }
    dt { color: var(--muted); }
    dd { margin: 0; color: var(--ink); min-width: 0; overflow-wrap: anywhere; }

    .feeds { border-top: 1px solid var(--rule); padding-top: 10px; }
    .feeds-head {
      font-size: 11px; letter-spacing: .08em; text-transform: uppercase;
      color: var(--muted); margin-bottom: 7px;
    }
    .feed { display: flex; align-items: baseline; gap: 8px; font-size: 13px; padding: 3px 0; }
    .mark { color: var(--dhl-red); font-weight: 700; width: 12px; flex: none; }
    .mark.on { color: var(--chip-ok-fg); }
    .svc { font-weight: 700; }
    .detail { color: var(--muted); min-width: 0; }
    .empty { font-size: 13px; color: var(--muted); }

    .open {
      margin-top: auto; padding-top: 12px;
      font-size: 13px; font-weight: 700; color: var(--ink); text-decoration: none;
    }
    .open:hover { color: var(--dhl-red); }

    .caveat { margin: 14px 0 0; font-size: 13px; color: var(--muted); max-width: 90ch; text-wrap: pretty; }
    .mono { font-family: ui-monospace, Menlo, monospace; }

    @media (prefers-reduced-motion: reduce) { .beat { animation: none; } }
  `
})
export class ObservabilityPanel {
  private readonly observability = inject(ObservabilityService);

  private static readonly SPLUNK = 'https://prd-p-2d10o.splunkcloud.com';
  private static readonly DYNATRACE = 'https://icp44821.apps.dynatrace.com';

  /** In the template rather than inline, because a backtick or an angle bracket
   *  in a styles template literal has terminated this file's string before. */
  protected readonly arrow = '↗';

  protected readonly status = computed(() => this.observability.status());

  /**
   * How many agents are loaded, not whether all of them are.
   *
   * "All" was the first spelling and it read NO AGENTS against a namespace where
   * both services on the booking path were instrumented - because redis is in
   * the list and redis will never carry a Java agent. A count says what is true
   * without needing every row to mean the same thing, and a drop from two to one
   * is still visible.
   */
  protected readonly agentCount = computed(
    () => (this.status()?.dynatrace.instrumented ?? []).filter((f: Feed) => f.on).length
  );

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

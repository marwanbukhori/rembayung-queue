import { Component, computed, inject } from '@angular/core';
import { SandboxService } from './sandbox.service';

/**
 * This run, opened in the tools that were watching it.
 *
 * Not the logs themselves, and the reason is a credential rather than a
 * decision. The cluster holds a Splunk HEC token and a Dynatrace PaaS token:
 * the first can only write events and the second can only install an agent.
 * Neither can read anything back, so a panel here claiming to show Splunk logs
 * would be showing something else. Reading needs a Splunk search token and a
 * Dynatrace API token with log scope, which are different credentials that
 * nobody has created.
 *
 * What is possible without them is the useful half: carry a time window across
 * so each tool opens on the minutes that were just watched.
 *
 * Each link also has to open the surface that actually holds data. Splunk gets
 * a query over the fields the shipped events carry; Dynatrace gets traces and
 * the service map, because an application-only OneAgent does not ship logs.
 */
@Component({
  selector: 'rb-observability-links',
  template: `
    @if (dropId(); as id) {
      <div class="row">
        <span class="lead">See this run in</span>
        @for (link of links(); track link.name) {
          <a class="link" [href]="link.href" target="_blank" rel="noreferrer"
             [title]="link.note">
            {{ link.name }}
            <svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">
              <path d="M9 15 19 5M13 5h6v6" fill="none" stroke="currentColor"
                    stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </a>
        }
        <span class="note">last 30 minutes, this namespace</span>
      </div>
    }
  `,
  styles: `
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 14px;
      padding: 12px 16px;
      border-top: 1px solid var(--rule);
      font-size: 13px;
    }
    .lead { color: var(--muted); }
    .link {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-weight: 700;
      color: var(--ink);
      text-decoration: none;
      border: 1px solid var(--line);
      border-radius: 999px;
      padding: 4px 12px;
    }
    .link:hover { border-color: var(--dhl-red); color: var(--dhl-red); }
    .note { color: var(--muted); margin-left: auto; }
  `
})
export class ObservabilityLinks {
  private readonly sandboxes = inject(SandboxService);

  private static readonly SPLUNK = 'https://prd-p-2d10o.splunkcloud.com';
  private static readonly DYNATRACE = 'https://icp44821.apps.dynatrace.com';

  protected readonly dropId = computed(() => this.sandboxes.sandbox()?.dropId ?? null);

  protected readonly links = computed(() => {
    const id = this.dropId();
    if (!id) {
      return [];
    }
    // Searched by service and time, not by drop id.
    //
    // The first version searched for the drop id and found nothing almost every
    // time, which is why these looked broken. The events shipped to Splunk carry
    // @timestamp, message, logger_name, thread_name, level and service - there is
    // no drop id among them, and neither the gate nor booking-service writes one
    // per request. Service and a time window are what the data actually supports.
    const query = encodeURIComponent(
      'search service="queue-gate" OR service="booking-service"');
    return [
      {
        name: 'Splunk',
        note: 'application logs',
        href: `${ObservabilityLinks.SPLUNK}/en-US/app/search/search`
          + `?earliest=-30m&latest=now&q=${query}`
      },
      {
        // Traces and the service map, not logs. This deployment runs the
        // application-only OneAgent, which buys distributed tracing and the
        // service map and does not ship logs at all - so the Logs app it used
        // to open was guaranteed to be empty however long the run had been.
        // gtf is Dynatrace's global timeframe parameter and takes a relative
        // expression directly.
        name: 'Dynatrace',
        note: 'traces and the service map',
        href: `${ObservabilityLinks.DYNATRACE}/ui/apps/dynatrace.distributedtraces/?gtf=-30m`
      }
    ];
  });
}

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
 * What is possible without them is the useful half: carry the session id and
 * the time window across, so the logs are one click away and already filtered
 * to the run just watched rather than to everything that has ever happened.
 */
@Component({
  selector: 'rb-observability-links',
  template: `
    @if (dropId(); as id) {
      <div class="row">
        <span class="lead">See this run in</span>
        @for (link of links(); track link.name) {
          <a class="link" [href]="link.href" target="_blank" rel="noreferrer">
            {{ link.name }}
            <svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">
              <path d="M9 15 19 5M13 5h6v6" fill="none" stroke="currentColor"
                    stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </a>
        }
        <span class="note">filtered to <span class="mono">{{ id }}</span>, last 30 minutes</span>
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
    // earliest/latest and q are the documented search-app parameters; a relative
    // window is used rather than the run's exact clock, because the browser's
    // clock and the cluster's need not agree and being half a minute wide costs
    // nothing.
    const query = encodeURIComponent(`search "${id}"`);
    return [
      {
        name: 'Splunk',
        href: `${ObservabilityLinks.SPLUNK}/en-US/app/search/search`
          + `?earliest=-30m&latest=now&q=${query}`
      },
      {
        // gtf is Dynatrace's global timeframe parameter and takes a relative
        // expression directly.
        name: 'Dynatrace',
        href: `${ObservabilityLinks.DYNATRACE}/ui/apps/dynatrace.logs/?gtf=-30m`
      }
    ];
  });
}

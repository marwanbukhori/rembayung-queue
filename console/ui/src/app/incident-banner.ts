import { Component, computed, inject, output } from '@angular/core';
import { IncidentService, duration, faultName, percent, seconds } from './incidents';

/**
 * A red strip on every page while an incident is open: how long it has been
 * open, both SLOs now, and the agent's latest line. It links to the incident.
 *
 * It renders nothing when nothing is open, like the run banner - a warning that
 * is always there stops being read.
 */
@Component({
  selector: 'rb-incident-banner',
  template: `
    @if (view(); as v) {
      <button class="strip" (click)="open.emit()" [attr.aria-label]="'Open incident ' + v.id">
        <span class="pip"></span>
        <span class="what">Incident open · {{ v.elapsed }}</span>
        <span class="slo">success <b class="mono">{{ v.success }}</b> · p95 <b class="mono">{{ v.p95 }}</b></span>
        @if (v.line) { <span class="line">{{ v.line }}</span> }
        <span class="go">View the incident →</span>
      </button>
    }
  `,
  styles: `
    .strip {
      width: 100%; font: inherit; text-align: left; cursor: pointer;
      display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px;
      padding: 10px 16px; border: 0; border-left: 4px solid var(--dhl-red); border-radius: 4px;
      background: var(--chip-bad-bg); color: var(--ink); font-size: 14px;
    }
    .strip:hover { filter: brightness(.98); }
    .strip:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; }
    .pip { width: 9px; height: 9px; border-radius: 999px; background: var(--dhl-red); flex: none;
           animation: beat 1.3s ease-in-out infinite; }
    @keyframes beat { 50% { opacity: .2; } }
    .what { font-weight: 700; color: var(--chip-bad-fg); }
    .slo { color: var(--ink-soft); white-space: nowrap; }
    .line { color: var(--ink-soft); min-width: 0; flex: 1 1 240px; overflow: hidden; text-overflow: ellipsis;
            white-space: nowrap; }
    .go { margin-left: auto; font-weight: 700; white-space: nowrap; }
    @media (prefers-reduced-motion: reduce) { .pip { animation: none; } }
  `
})
export class IncidentBanner {
  readonly open = output<void>();
  private readonly incidents = inject(IncidentService);

  protected readonly view = computed(() => {
    const summary = this.incidents.open();
    if (!summary) {
      return null;
    }
    const detail = this.incidents.current();
    const now = this.incidents.slo()?.now;
    const latest = detail?.id === summary.id
      ? [...detail.timeline].reverse().find((e) => e.source === 'agent') : undefined;
    return {
      id: summary.id,
      elapsed: duration((this.incidents.now() - Date.parse(summary.openedAt)) / 1000)
        + (summary.fault ? ` · drill: ${faultName(summary.fault)}` : ''),
      success: now?.hasTraffic ? percent(now.successRatio) : 'no traffic',
      p95: seconds(now?.p95Seconds),
      line: latest?.text ?? 'The agent is looking into it.'
    };
  });
}

import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Incident, IncidentFact, IncidentService, Proposal, describe, duration, faultName, isOpen, percent, seconds
} from './incidents';
import { DemoKeyService, hasConsoleKey } from './key';
import { malaysiaTime } from './time';

/**
 * The incident commander at work: the SLOs, what happened in order, what the
 * agent thinks the cause is and on which facts, and the fix it proposes -
 * which only a person can approve. Past incidents keep their postmortems.
 */
@Component({
  selector: 'rb-agent-incidents',
  template: `
    <div class="stack-24">
      <section class="card pad">
        <div class="row-head">
          <h2>Booking SLOs</h2>
          <span class="quiet">booking-service /bookings, over 5 minutes</span>
        </div>
        <div class="gauges">
          @for (g of gauges(); track g.name) {
            <div class="gauge" [class.bad]="g.state === 'breached'">
              <div class="g-name">{{ g.name }}</div>
              <div class="g-value mono">{{ g.value }}</div>
              <div class="bar" role="img" [attr.aria-label]="g.name + ' ' + g.value + ', target ' + g.target">
                <span class="fill" [style.width.%]="g.fill"></span>
                <span class="target" [style.left.%]="g.mark"></span>
              </div>
              <div class="g-foot">target {{ g.target }} · <b>{{ g.state }}</b></div>
            </div>
          }
        </div>
      </section>

      @if (selected(); as i) {
        <section class="card pad incident" [class.live]="open(i)">
          <div class="row-head">
            <h2>{{ i.kind === 'drill' ? 'Drill' : 'SLO breach' }}{{ i.fault ? ': ' + name(i.fault) : '' }}</h2>
            <span class="status" [class]="i.status">{{ i.status }}</span>
          </div>
          <p class="quiet mono">{{ i.id }} · opened {{ time(i.openedAt) }}{{ i.resolvedAt ? ' · closed ' + time(i.resolvedAt) : '' }}
            · agent cycles {{ i.cycles }}</p>

          <div class="counters">
            <div class="counter"><span class="c-label">Time to detect</span>
              <span class="c-value mono">{{ ttd(i) }}</span><span class="c-note">opened → the agent named a cause</span></div>
            <div class="counter"><span class="c-label">Time to recover</span>
              <span class="c-value mono">{{ ttr(i) }}</span><span class="c-note">detected → both SLOs held 60 s</span></div>
          </div>

          <div class="cols">
            <div class="col">
              <h3>What the agent thinks</h3>
              @if (diagnosis(i); as d) {
                <p class="cause">{{ d.cause }} <span class="conf" [class]="d.confidence">{{ d.confidence }}</span></p>
                <ul class="claims">
                  @for (c of d.claims; track $index) {
                    <li>{{ c.text }}
                      @for (id of c.facts; track id) {
                        <button class="chip mono" [class.on]="fact() === i.id + id" [attr.aria-expanded]="fact() === i.id + id"
                                (click)="toggle(i.id + id)">{{ id }}</button>
                      }
                      @for (id of c.facts; track id) {
                        @if (fact() === i.id + id) {
                          @if (lookup(d.facts, id); as f) {
                            <div class="fact"><b>{{ f.id }}</b> {{ f.label }}: <span class="mono">{{ f.value }}</span>
                              <span class="quiet">({{ f.source }})</span></div>
                          }
                        }
                      }
                    </li>
                  }
                </ul>
                <p class="quiet small">{{ i.diagnoses.length }} diagnos{{ i.diagnoses.length === 1 ? 'is' : 'es' }}; every number
                  checked against the facts it cites.</p>
              } @else {
                <p class="quiet">The agent has not reported yet; it looks every 30 seconds while the incident is open.</p>
              }

              <h3>Proposed fix</h3>
              @if (proposal(i); as p) {
                <div class="proposal" [class]="p.status">
                  <div class="p-head"><b>{{ what(p) }}</b> <span class="p-status">{{ p.status }}</span></div>
                  <p class="p-reason">{{ p.reason }}</p>
                  @if (p.status === 'pending' && open(i)) {
                    @if (readOnly) {
                      <p class="quiet small">Approving needs the console key.
                        @if (demoKey.shareable()) { <button class="btn-tertiary" (click)="demoKey.open()">Get the demo key</button> }
                      </p>
                    } @else {
                      <div class="p-actions">
                        <button class="btn btn-primary" [disabled]="busy()" (click)="decide(i, p, 'approve')">Approve</button>
                        <button class="btn btn-secondary" [disabled]="busy()" (click)="decide(i, p, 'dismiss')">Dismiss</button>
                      </div>
                    }
                  }
                  <p class="quiet small">The agent proposes from a fixed menu; nothing it proposes runs until a person approves.</p>
                </div>
              } @else {
                <p class="quiet">None yet.</p>
              }
              @if (failure(); as f) { <p class="reason" role="alert">{{ f }}</p> }

              @if (i.postmortem; as pm) {
                <h3>Postmortem <span class="quiet small">({{ pm.source === 'model' ? 'written by the agent, checked' : 'built from the facts by rules' }})</span></h3>
                <dl class="pm">
                  <dt>Summary</dt><dd>{{ pm.summary }}</dd>
                  <dt>Impact</dt><dd>{{ pm.impact }}</dd>
                  <dt>Root cause</dt><dd>{{ pm.rootCause }}</dd>
                  <dt>What fixed it</dt><dd>{{ pm.fix }}</dd>
                  @if (pm.followUps.length) {
                    <dt>Follow-ups</dt><dd><ul>@for (f of pm.followUps; track $index) { <li>{{ f }}</li> }</ul></dd>
                  }
                </dl>
              }
            </div>

            <div class="col">
              <h3>Timeline</h3>
              <ol class="timeline">
                @for (e of i.timeline; track $index) {
                  <li><span class="t mono">{{ time(e.at) }}</span><span class="src" [class]="e.source">{{ e.source }}</span>
                    <span class="txt">{{ e.text }}</span></li>
                }
              </ol>
            </div>
          </div>
        </section>
      } @else {
        <section class="card pad">
          <p class="quiet">No incident yet. Start a rush on the simulation page, then a chaos drill: an incident opens at once,
            and the agent starts investigating it here.</p>
        </section>
      }

      @if (incidents.incidents().length) {
        <section class="card pad">
          <h2>Incidents</h2>
          <ul class="list">
            @for (s of incidents.incidents(); track s.id) {
              <li><button [class.on]="s.id === selected()?.id" (click)="pick(s.id)">
                <span class="status" [class]="s.status">{{ s.status }}</span>
                <span class="l-what">{{ s.kind === 'drill' ? name(s.fault) : 'SLO breach' }}</span>
                <span class="quiet mono">{{ time(s.openedAt) }}</span>
              </button></li>
            }
          </ul>
        </section>
      }
    </div>
  `,
  styles: `
    .pad { padding: 20px 24px; }
    h2 { margin: 0; font-size: 20px; }
    h3 { margin: 20px 0 8px; font-size: 15px; }
    .quiet { color: var(--muted); margin: 0; }
    .small { font-size: 12px; }
    .row-head { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px 16px; margin-bottom: 8px; }
    .gauges { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(min(260px, 100%), 1fr)); }
    .gauge { border: 1px solid var(--line); border-radius: 6px; padding: 12px 14px; }
    .gauge.bad { border-color: var(--dhl-red); background: var(--chip-bad-bg); }
    .g-name { font-size: 13px; color: var(--muted); }
    .g-value { font-size: 26px; font-weight: 700; }
    .bar { position: relative; height: 8px; border-radius: 4px; background: var(--rule); margin: 8px 0 6px; }
    .fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 4px; background: var(--chip-ok-fg); }
    .bad .fill { background: var(--dhl-red); }
    .target { position: absolute; top: -3px; bottom: -3px; width: 2px; background: var(--ink); }
    .g-foot { font-size: 12px; color: var(--ink-soft); }
    .incident.live { border-left: 4px solid var(--dhl-red); }
    .status { font-size: 12px; font-weight: 700; padding: 2px 9px; border-radius: 999px; background: var(--rule); color: var(--ink-soft); }
    .status.open, .status.mitigating { background: var(--chip-bad-bg); color: var(--chip-bad-fg); }
    .status.resolved { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .status.unresolved { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .counters { display: flex; flex-wrap: wrap; gap: 12px; margin: 14px 0 4px; }
    .counter { display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 6px; padding: 10px 14px; min-width: 180px; }
    .c-label { font-size: 12px; color: var(--muted); }
    .c-value { font-size: 22px; font-weight: 700; }
    .c-note { font-size: 12px; color: var(--muted); }
    .cols { display: grid; gap: 8px 32px; grid-template-columns: minmax(0, 1fr); }
    @media (min-width: 960px) { .cols { grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); } }
    .col { min-width: 0; }
    .cause { margin: 0 0 6px; font-weight: 700; }
    .conf { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--rule); margin-left: 6px; }
    .conf.high { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .conf.medium { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .claims { margin: 0 0 6px; padding-left: 18px; font-size: 14px; }
    .claims li { margin: 4px 0; }
    .chip { margin-left: 5px; font-size: 11px; border: 1px solid var(--line); background: var(--white); border-radius: 3px;
            padding: 0 5px; cursor: pointer; }
    .chip.on { background: var(--ink); color: var(--white); border-color: var(--ink); }
    .fact { margin: 4px 0; font-size: 12px; padding: 6px 8px; background: var(--rule); border-radius: 4px; overflow-wrap: anywhere; }
    .proposal { border: 1px solid var(--line); border-radius: 6px; padding: 12px 14px; }
    .proposal.pending { border-color: var(--dhl-red); }
    .p-head { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: baseline; }
    .p-status { font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
    .p-reason { margin: 6px 0; font-size: 14px; }
    .p-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
    .reason { color: var(--chip-bad-fg); font-size: 13px; }
    .pm { margin: 0; font-size: 14px; }
    .pm dt { font-weight: 700; margin-top: 8px; }
    .pm dd { margin: 2px 0 0; }
    .pm ul { margin: 0; padding-left: 18px; }
    .timeline { list-style: none; margin: 0; padding: 0 0 0 12px; border-left: 2px solid var(--line); font-size: 13px; }
    .timeline li { display: flex; flex-wrap: wrap; gap: 2px 8px; padding: 5px 0; }
    .t { color: var(--muted); }
    .src { font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 999px; background: var(--rule); }
    .src.chaos, .src.slo { background: var(--chip-bad-bg); color: var(--chip-bad-fg); }
    .src.agent { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .src.human, .src.action { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .txt { flex: 1 1 100%; overflow-wrap: anywhere; }
    .list { list-style: none; margin: 8px 0 0; padding: 0; }
    .list button { width: 100%; font: inherit; text-align: left; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px;
                   background: none; border: 0; border-top: 1px solid var(--rule); padding: 10px 4px; cursor: pointer; }
    .list button.on { background: var(--rule); }
    .l-what { font-weight: 700; flex: 1 1 160px; }
  `
})
export class AgentIncidents {
  protected readonly incidents = inject(IncidentService);
  protected readonly demoKey = inject(DemoKeyService);
  protected readonly readOnly = !hasConsoleKey();

  private readonly picked = signal<Incident | null>(null);
  protected readonly fact = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  /** The incident a visitor picked, or else the open one, or else the latest. */
  protected readonly selected = computed(() => {
    const picked = this.picked();
    const current = this.incidents.current();
    return picked && picked.id !== current?.id ? picked : current;
  });

  protected readonly gauges = computed(() => {
    const now = this.incidents.slo()?.now;
    const traffic = !!now?.available && now.hasTraffic;
    const success = now?.successRatio ?? null;
    const p95 = now?.p95Seconds ?? null;
    const state = (bad: boolean) => !now?.available ? 'unavailable' : !traffic ? 'no traffic' : bad ? 'breached' : 'ok';
    return [
      { name: 'Booking success', value: traffic ? percent(success) : '—', target: '≥ 99%',
        // The bar spans 90-100%, where the story is; the target sits at 99.
        fill: success === null ? 0 : Math.max(0, Math.min(100, (success - 0.9) * 1000)), mark: 90,
        state: state(success !== null && success < 0.99) },
      { name: 'Booking latency p95', value: traffic ? seconds(p95) : '—', target: '< 2 s',
        fill: p95 === null ? 0 : Math.min(100, (p95 / 4) * 100), mark: 50,
        state: state(p95 !== null && p95 >= 2) }
    ];
  });

  protected name(id: string | null): string {
    return faultName(id);
  }

  protected open(i: Incident): boolean {
    return isOpen(i.status);
  }

  protected time(at: string): string {
    return malaysiaTime(at);
  }

  protected what(p: Proposal): string {
    return describe(p);
  }

  protected diagnosis(i: Incident) {
    return i.diagnoses.length ? i.diagnoses[i.diagnoses.length - 1] : null;
  }

  protected proposal(i: Incident): Proposal | null {
    return i.proposals.find((p) => p.status === 'pending') ?? i.proposals[i.proposals.length - 1] ?? null;
  }

  protected lookup(facts: IncidentFact[], id: string): IncidentFact | undefined {
    return facts.find((f) => f.id === id);
  }

  protected toggle(key: string): void {
    this.fact.set(this.fact() === key ? null : key);
  }

  protected ttd(i: Incident): string {
    const end = i.detectedAt ? Date.parse(i.detectedAt) : i.resolvedAt ? null : this.incidents.now();
    return end === null ? 'not detected' : duration((end - Date.parse(i.openedAt)) / 1000);
  }

  protected ttr(i: Incident): string {
    if (!i.detectedAt) {
      return '—';
    }
    const end = i.resolvedAt ? Date.parse(i.resolvedAt) : this.incidents.now();
    return i.status === 'unresolved' ? 'handed to a person' : duration((end - Date.parse(i.detectedAt)) / 1000);
  }

  protected pick(id: string): void {
    if (id === this.incidents.current()?.id) {
      this.picked.set(null);
      return;
    }
    this.incidents.get(id).subscribe({ next: (i) => this.picked.set(i), error: () => {} });
  }

  protected decide(i: Incident, p: Proposal, verdict: 'approve' | 'dismiss'): void {
    this.busy.set(true);
    this.failure.set(null);
    this.incidents.decide(i.id, p.n, verdict).subscribe({
      next: () => { this.busy.set(false); this.incidents.refresh(); },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.failure.set(e.status === 409 ? 'That proposal was already decided, or the incident has closed.'
          : e.status === 401 ? 'The console key was refused.'
          : e.status === 502 ? 'The cluster refused the change; the timeline says why.' : 'That did not go through. Try again.');
        this.incidents.refresh();
      }
    });
  }
}

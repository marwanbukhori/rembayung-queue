import { Component, computed, input, signal } from '@angular/core';
import { AgentClaim, AgentFact, Analysis } from './analysis';
import { Funnel } from './funnel';
import { TIME_ZONE_LABEL, malaysiaTime } from './time';

/**
 * One run's report, the way the agent wrote it: three short lists, each claim
 * followed by the ids of the facts it rests on, and the trail of what it looked
 * at. A chip opens its fact beside the claim, so a reader can check any number
 * without leaving the sentence.
 *
 * Used by the inspector for a load run and by the AI Agent page for the latest
 * one, so both show a report the same way.
 */
@Component({
  selector: 'rb-analysis-report',
  imports: [Funnel],
  template: `
    @let a = analysis();
    <div class="meta">
      <span [class]="'who ' + a.source">{{ a.source === 'model' ? 'Written by ' + a.model : 'Built from the facts alone' }}</span>
      <span class="mono when">{{ time(a.analysedAt) }} {{ zone }} · {{ seconds(a.millis) }}</span>
    </div>
    @if (a.source === 'fallback' && a.note) {
      <p class="why">The model's report was not used: {{ a.note }}.</p>
    }

    @if (twoWaves()) {
      <div class="waves">
        <div><h4>Wave 1</h4><rb-funnel [facts]="a.facts" prefix="Wave 1 · " /></div>
        <div><h4>Wave 2</h4><rb-funnel [facts]="a.facts" prefix="Wave 2 · " /></div>
      </div>
    } @else {
      <rb-funnel [facts]="a.facts" />
    }

    @for (group of groups(); track group.title) {
      @if (group.claims.length) {
        <h4>{{ group.title }}</h4>
        <ul class="claims">
          @for (claim of group.claims; track $index; let c = $index) {
            <li>
              <span>{{ claim.text }}</span>
              @for (id of claim.facts; track id) {
                <button class="chip mono" [class.on]="open() === group.title + c + id"
                        [attr.aria-expanded]="open() === group.title + c + id"
                        (click)="toggle(group.title + c + id)">{{ id }}</button>
              }
              @for (id of claim.facts; track id) {
                @if (open() === group.title + c + id) {
                  @if (fact(id); as f) {
                    <div class="fact">
                      <span class="src mono">{{ f.id }} · {{ f.source }}</span>
                      <span>{{ f.label }}: <b>{{ f.value }}</b></span>
                    </div>
                  }
                }
              }
            </li>
          }
        </ul>
      }
    }

    @if (a.trail.length) {
      <h4>What it looked at</h4>
      <ol class="trail">
        @for (s of a.trail; track $index) {
          <li><span class="mono">{{ s.tool }}</span>{{ s.why ? ' because ' + s.why : '' }} → {{ s.factId }}
            @if (s.via) {<span class="via">{{ s.via === 'mcp' ? 'via MCP' : 'in-process' }}</span>}</li>
        }
      </ol>
    } @else {
      <p class="quiet">It asked for nothing beyond the baseline facts.</p>
    }
    <details class="all">
      <summary>All {{ a.facts.length }} facts</summary>
      <dl>
        @for (f of a.facts; track f.id) {
          <dt class="mono">{{ f.id }}</dt>
          <dd><span class="quiet">{{ f.source }} · </span>{{ f.label }}: {{ f.value }}</dd>
        }
      </dl>
    </details>
  `,
  styles: `
    :host { display: block; font-size: 14px; }
    .waves { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 24px; }
    .waves h4 { margin: 4px 0 6px; }
    @media (max-width: 699px) { .waves { grid-template-columns: minmax(0, 1fr); } }
    .meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; margin-bottom: 6px; }
    .who { font-weight: 700; }
    .who.fallback { color: var(--chip-warn-fg); }
    .when { font-size: 12px; color: var(--muted); }
    .why { margin: 4px 0 8px; font-size: 13px; color: var(--ink-soft); }
    h4 { font-size: 14px; margin: 14px 0 6px; }
    .claims { margin: 0; padding-left: 18px; display: grid; gap: 6px; }
    .chip { margin-left: 5px; font-size: 11px; font-weight: 400; border: 1px solid var(--line); background: var(--white);
            color: var(--ink); border-radius: 6px; padding: 0 6px; cursor: pointer; }
    .chip.on { background: var(--ink); color: var(--white); border-color: var(--ink); }
    .fact { display: flex; flex-direction: column; gap: 2px; margin-top: 5px; padding: 6px 10px;
            border-left: 3px solid var(--ink); background: var(--canvas); font-size: 13px; overflow-wrap: anywhere; }
    .fact b { white-space: pre-wrap; }
    .src { font-size: 11px; color: var(--muted); }
    .trail { margin: 0; padding-left: 18px; display: grid; gap: 4px; color: var(--ink-soft); overflow-wrap: anywhere; }
    .trail .mono { color: var(--ink); font-size: 12px; }
    .via { margin-left: 6px; font-size: 11px; padding: 0 6px; border-radius: 999px; background: var(--chip-info-bg); color: var(--chip-info-fg); }
    .quiet { color: var(--muted); font-size: 13px; }
    .mono { font-family: var(--mono); }
    .all { margin-top: 12px; }
    .all summary { cursor: pointer; color: var(--muted); font-size: 13px; }
    .all dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 10px; margin: 8px 0 0; font-size: 12px; }
    .all dd { margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
  `
})
export class AnalysisReport {
  readonly analysis = input.required<Analysis>();

  protected readonly zone = TIME_ZONE_LABEL;
  protected readonly open = signal<string | null>(null);
  /** A two-wave run draws each wave's funnel, side by side. */
  protected readonly twoWaves = computed(() => this.analysis().facts.some(f => f.label === 'Wave 2 · Arrived'));

  protected readonly groups = computed(() => {
    const r = this.analysis().report;
    const all: { title: string; claims: AgentClaim[] | undefined }[] = [
      { title: 'Before and after', claims: r.beforeAfter },
      { title: 'Summary', claims: r.summary },
      { title: 'Where the customers went', claims: r.customers },
      { title: 'Capacity and scaling', claims: r.capacity },
      { title: 'Errors', claims: r.errors },
      { title: 'Went well', claims: r.wentWell },
      { title: 'Caught', claims: r.caught },
      { title: 'Look at', claims: r.lookAt }
    ];
    return all.map(g => ({ title: g.title, claims: g.claims ?? [] })).filter(g => g.claims.length);
  });

  protected fact(id: string): AgentFact | undefined {
    return this.analysis().facts.find(f => f.id === id);
  }

  protected toggle(key: string): void {
    this.open.update(o => (o === key ? null : key));
  }

  protected time(at: string): string {
    return malaysiaTime(at, false);
  }

  protected seconds(ms: number): string {
    return `${Math.max(1, Math.round(ms / 1000))} s`;
  }
}

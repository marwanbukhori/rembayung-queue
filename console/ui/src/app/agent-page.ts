import { Component, OnInit, computed, effect, inject, output, signal, untracked } from '@angular/core';
import { AgentHow } from './agent-how';
import { AgentIncidents } from './agent-incidents';
import { AgentMcp } from './agent-mcp';
import { AgentReports } from './agent-reports';
import { Analysis, AnalysisService, AnalysisSummary } from './analysis';
import { IncidentService } from './incidents';

type View = 'reports' | 'incidents' | 'mcp' | 'how';

/**
 * The run agent and the MCP server it works through, on one page.
 *
 * Four views, because a visitor comes for one of four things: to read what
 * the agent found (Reports), to watch it run an incident (Incidents), to
 * connect their own Claude to the same tools (MCP), or to see how the agent is
 * built (How it works). The view is kept in the address, so each can be linked
 * on its own.
 */
@Component({
  selector: 'rb-agent-page',
  imports: [AgentHow, AgentIncidents, AgentMcp, AgentReports],
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">AI Agent &amp; MCP</span>
      </div>
      <div class="head">
        <div>
          <h1>AI Agent &amp; MCP
            @if (live()) { <span class="badge live">● Live</span> } @else { <span class="badge">Waiting for a first run</span> }
          </h1>
          <p class="lede">
            After every rush, an agent inside the console gathers the facts, asks up to five questions through the
            console's MCP server, and writes a report whose every number is checked against the facts it cites.
            During an incident it diagnoses through the same tools and proposes a fix; a person approves it.
            The same MCP server is open to your own Claude.
          </p>
        </div>
      </div>

      <nav class="views" role="tablist" aria-label="AI Agent and MCP">
        @for (v of views; track v.id) {
          <button role="tab" [class.on]="view() === v.id" [attr.aria-selected]="view() === v.id" (click)="show(v.id)">
            {{ v.label }}
          </button>
        }
      </nav>

      @switch (view()) {
        @case ('reports') {
          @if (runs().length) {
            <rb-agent-reports [runs]="runs()" [latest]="latest()" [selected]="selected()" (select)="select($event)" />
          } @else {
            <section class="card pad">
              <p class="quiet">No run analysed yet. Start a rush on the simulation page; its report appears here about a
                minute after it ends.</p>
            </section>
          }
        }
        @case ('incidents') { <rb-agent-incidents /> }
        @case ('mcp') { <rb-agent-mcp /> }
        @case ('how') { <rb-agent-how [run]="latest()" /> }
      }
    </div>
  `,
  styles: `
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    h1 { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; }
    .badge { font-size: 13px; font-weight: 700; padding: 4px 10px; border-radius: 999px;
             background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .badge.live { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .views { display: flex; gap: 4px; border-bottom: 1px solid var(--line); overflow-x: auto; }
    .views button { font: inherit; font-size: 15px; background: none; border: 0; border-bottom: 3px solid transparent;
                    padding: 10px 14px; cursor: pointer; color: var(--ink-soft); white-space: nowrap; }
    .views button.on { color: var(--ink); font-weight: 700; border-bottom-color: var(--dhl-red); }
    .pad { padding: 20px 24px; }
    .quiet { color: var(--muted); margin: 0; }
  `
})
export class AgentPage implements OnInit {
  readonly home = output<void>();

  private readonly analyses = inject(AnalysisService);
  protected readonly views: { id: View; label: string }[] = [
    { id: 'reports', label: 'Reports' }, { id: 'incidents', label: 'Incidents' }, { id: 'mcp', label: 'MCP' },
    { id: 'how', label: 'How it works' }
  ];
  protected readonly view = signal<View>(this.initialView());
  protected readonly runs = signal<AnalysisSummary[]>([]);
  protected readonly latest = signal<Analysis | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly live = computed(() => this.runs().some(r => r.source === 'model'));
  private readonly incidents = inject(IncidentService);

  constructor() {
    // The site-wide incident banner asks for the Incidents view; follow it even when this page is already open.
    const seen = this.incidents.focus();
    effect(() => {
      if (this.incidents.focus() > seen) {
        untracked(() => this.view.set('incidents'));
      }
    });
  }

  ngOnInit(): void {
    this.analyses.list().subscribe({
      next: runs => {
        this.runs.set(runs);
        const asked = new URL(window.location.href).searchParams.get('run');
        const first = runs.find(r => r.key === asked) ?? runs[0];
        if (first) {
          this.load(first.key);
        }
      },
      error: () => {}
    });
  }

  protected show(view: View): void {
    this.view.set(view);
    this.setParam('view', view === 'reports' ? null : view);
  }

  protected select(key: string): void {
    this.setParam('run', key);
    this.load(key);
  }

  private load(key: string): void {
    this.selected.set(key);
    this.analyses.get(key).subscribe({
      next: a => {
        if (this.selected() === key) {
          this.latest.set(a);
        }
      },
      error: () => {}
    });
  }

  private initialView(): View {
    const v = new URL(window.location.href).searchParams.get('view');
    return v === 'mcp' || v === 'how' || v === 'incidents' ? v : 'reports';
  }

  private setParam(name: string, value: string | null): void {
    const url = new URL(window.location.href);
    if (value === null) {
      url.searchParams.delete(name);
    } else {
      url.searchParams.set(name, value);
    }
    history.replaceState(history.state, '', url);
  }
}

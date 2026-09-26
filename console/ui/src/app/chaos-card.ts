import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FAULTS, IncidentService, duration, faultName } from './incidents';
import { DemoKeyService, hasConsoleKey } from './key';
import { LoadService } from './load.service';

/**
 * Break something on purpose, and watch the system notice.
 *
 * One fault at a time, each ending by itself within two minutes whatever
 * happens to the console. Starting one opens a drill incident at once; the
 * agent then investigates it on the AI Agent & MCP page and proposes a fix for
 * a person to approve.
 */
@Component({
  selector: 'rb-chaos-card',
  template: `
    <section class="card chaos">
      <div class="head">
        <h2 class="title">Chaos drill</h2>
        <span class="chip">up to 2 minutes</span>
      </div>
      <p class="sub">
        Break one thing and watch the SLOs, the incident and the agent respond. Every fault ends by itself.
        @if (!rushing()) { <span class="hint">Start a rush first, or there is no traffic to hurt.</span> }
      </p>

      @if (incidents.active(); as a) {
        <div class="running" role="status">
          <b>{{ name(a.fault) }}</b> is running · ends in <span class="mono">{{ left(a.until) }}</span>
          @if (!readOnly) {
            <button class="btn-tertiary" [disabled]="busy()" (click)="end()">End it now</button>
          }
        </div>
      }

      <div class="faults">
        @for (f of faults; track f.id) {
          <button class="fault" [disabled]="readOnly || busy() || !!incidents.active()" (click)="start(f.id)">
            <span class="name">{{ f.name }}</span>
            <span class="effect">{{ f.effect }}</span>
          </button>
        }
      </div>

      @if (readOnly) {
        <p class="locked">
          A drill needs the console key.
          @if (demoKey.shareable()) {
            <button class="btn btn-primary small" (click)="demoKey.open()">Get the demo key</button>
          }
        </p>
      }
      @if (failure(); as f) { <p class="reason" role="alert">{{ f }}</p> }
    </section>
  `,
  styles: `
    .chaos { padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; border-top: 4px solid var(--dhl-red); }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .title { margin: 0; font-size: 20px; }
    .chip { font-size: 12px; font-weight: 700; padding: 3px 9px; border-radius: 999px;
            background: var(--chip-warn-bg); color: var(--chip-warn-fg); white-space: nowrap; }
    .sub { margin: 0; font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }
    .hint { display: block; margin-top: 4px; color: var(--muted); }
    .faults { display: grid; gap: 8px; }
    .fault { font: inherit; text-align: left; display: flex; flex-direction: column; gap: 2px; padding: 10px 12px;
             border: 1px solid var(--line); border-radius: 4px; background: var(--white); cursor: pointer; }
    .fault:hover:not(:disabled) { border-color: var(--dhl-red); }
    .fault:disabled { opacity: .5; cursor: default; }
    .name { font-weight: 700; font-size: 14px; }
    .effect { font-size: 13px; color: var(--muted); }
    .running { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; font-size: 14px; padding: 10px 12px;
               border-radius: 4px; background: var(--chip-bad-bg); color: var(--ink); }
    .running .btn-tertiary { margin-left: auto; }
    .locked { margin: 0; font-size: 13px; color: var(--muted); display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .small { font-size: 13px; padding: 6px 12px; }
    .reason { margin: 0; font-size: 13px; color: var(--chip-bad-fg); }
  `
})
export class ChaosCard {
  protected readonly incidents = inject(IncidentService);
  protected readonly demoKey = inject(DemoKeyService);
  private readonly loads = inject(LoadService);

  protected readonly faults = FAULTS;
  protected readonly readOnly = !hasConsoleKey();
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly rushing = computed(() => this.loads.run()?.phase === 'RUNNING');

  protected name(id: string): string {
    return faultName(id);
  }

  protected left(until: string): string {
    return duration((Date.parse(until) - this.incidents.now()) / 1000);
  }

  protected start(fault: string): void {
    this.busy.set(true);
    this.failure.set(null);
    this.incidents.inject(fault).subscribe({
      next: () => { this.busy.set(false); this.incidents.refresh(); },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.failure.set(e.status === 409
          ? `${faultName(e.error?.active?.fault)} is already running; one fault at a time.`
          : e.status === 401 ? 'The console key was refused.' : 'The drill could not start. Try again in a moment.');
        this.incidents.refresh();
      }
    });
  }

  protected end(): void {
    this.busy.set(true);
    this.incidents.end().subscribe({
      next: () => { this.busy.set(false); this.incidents.refresh(); },
      error: () => { this.busy.set(false); this.failure.set('Could not end the fault; it will end by itself.'); }
    });
  }
}

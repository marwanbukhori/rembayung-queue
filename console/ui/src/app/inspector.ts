import { Component, computed, inject, signal } from '@angular/core';
import { InspectorService } from './inspector.service';
import { ObjectLink } from './state';

/**
 * The right-hand column of the cluster page: whatever object was clicked.
 *
 * One template for every kind. The server sends a headline, a tone and
 * label/value facts, so a Route and a CronJob render the same way and a new
 * kind needs no new component.
 *
 * Under 900px it becomes a drawer over the page, with a close button, because
 * a third of a phone is too narrow to read an event message in.
 */
@Component({
  selector: 'rb-inspector',
  template: `
    <aside class="inspector" [class.open]="!!inspector.selected()">
      @if (inspector.selected(); as ref) {
        <header class="head">
          <div>
            <div class="kind mono">{{ ref.kind }}</div>
            <div class="name mono">{{ ref.name }}</div>
          </div>
          <button class="close" (click)="inspector.select(null)" aria-label="Close inspector">✕</button>
        </header>

        @if (inspector.gone()) {
          <p class="gone">
            This {{ ref.kind }} no longer exists. Pods are replaced on every rollout and when the
            autoscaler scales in.
          </p>
          @if (owner(); as o) {
            <button class="link" (click)="open(o)">Open {{ o.label }} {{ o.name }}</button>
          }
        } @else if (inspector.detail(); as d) {
          @if (!d.available) {
            <p class="pill bad">{{ d.headline }}: {{ d.detail }}</p>
          } @else {
            <p [class]="'pill ' + d.tone">{{ d.headline }}</p>

            <div class="tabs" role="tablist">
              <button role="tab" [class.on]="tab() === 'overview'" (click)="tab.set('overview')">Overview</button>
              <button role="tab" [class.on]="tab() === 'events'" (click)="tab.set('events')">
                Events ({{ d.events.length }})
              </button>
            </div>

            @if (tab() === 'overview') {
              <dl class="facts">
                @for (f of d.facts; track f.label) {
                  <dt>{{ f.label }}</dt>
                  <dd [class]="f.tone ? 'mono t-' + f.tone : 'mono'">{{ f.value }}</dd>
                }
              </dl>
              @if (d.related.length) {
                <div class="related">
                  @for (l of d.related; track l.kind + l.name) {
                    <button [class]="'chip t-' + (l.tone ?? 'neutral')" (click)="open(l)">
                      <span class="chip-kind">{{ l.label }}</span> {{ l.name }}
                    </button>
                  }
                </div>
              }
            } @else {
              @if (d.events.length === 0) {
                <p class="quiet">No recent events. Kubernetes keeps them for about an hour.</p>
              }
              <ol class="events">
                @for (e of d.events; track $index) {
                  <li [class.warn]="e.type === 'Warning'">
                    <span class="mono when">{{ time(e.at) }}</span>
                    <span class="reason">{{ e.reason }}{{ e.count > 1 ? ' ×' + e.count : '' }}</span>
                    <span class="msg">{{ e.message }}</span>
                  </li>
                }
              </ol>
            }
          }
        } @else {
          <p class="quiet">Reading…</p>
        }
      } @else {
        <div class="empty">
          <p class="quiet">Click any Route, Service, Deployment, autoscaler or the CronJob in the graph.</p>
          <div class="kind mono">Recent runs</div>
          @for (j of inspector.recentJobs(); track j.name) {
            <button class="row" (click)="open({ kind: 'job', name: j.name, label: 'Job', tone: j.tone })">
              <span [class]="'dot t-' + j.tone"></span>
              <span class="mono">{{ j.name }}</span>
              <span class="quiet">{{ j.headline }}</span>
            </button>
          } @empty {
            <p class="quiet">No runs in the last hour.</p>
          }
        </div>
      }
    </aside>
  `,
  styles: `
    .inspector { border-left: 1px solid var(--line); padding: 0 0 0 20px; min-width: 0; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
    .kind { font-size: 11px; letter-spacing: .1em; text-transform: uppercase; color: var(--muted); }
    .name { font-size: 15px; font-weight: 700; word-break: break-all; }
    .mono { font-family: var(--mono); }
    .close { display: none; background: none; border: 0; font-size: 18px; cursor: pointer; color: var(--ink); }
    .pill { display: inline-block; max-width: 100%; overflow-wrap: anywhere; margin: 12px 0; padding: 4px 11px;
            border-radius: 12px; font-size: 13px;
            background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .pill.ok { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .pill.warn { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .pill.bad { background: var(--chip-bad-bg); color: var(--chip-bad-fg); }
    .tabs { display: flex; gap: 14px; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
    .tabs button { background: none; border: 0; padding: 6px 0; cursor: pointer; font-size: 14px;
                   color: var(--muted); border-bottom: 2px solid transparent; }
    .tabs button.on { color: var(--ink); font-weight: 700; border-color: var(--chip-bad-fg); }
    .facts { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; font-size: 13px; }
    .facts dt { color: var(--muted); }
    .facts dd { margin: 0; word-break: break-word; }
    .t-ok { color: var(--chip-ok-fg); }
    .t-warn { color: var(--chip-warn-fg); }
    .t-bad { color: var(--chip-bad-fg); }
    .related { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
    .chip { font-family: var(--mono); font-size: 12px; border: 1px solid var(--line); background: var(--white);
            color: var(--ink); border-radius: 6px; padding: 3px 8px; cursor: pointer; }
    .chip-kind { color: var(--muted); }
    .chip.t-bad { border-color: var(--chip-bad-fg); }
    .chip.t-warn { border-color: var(--chip-warn-fg); }
    .events { list-style: none; padding: 0; margin: 0; font-size: 13px; display: grid; gap: 8px; }
    .events li { display: grid; grid-template-columns: 70px 1fr; gap: 2px 10px; }
    .events .msg { grid-column: 2; color: var(--ink-soft); }
    .events li.warn .reason { color: var(--chip-bad-fg); font-weight: 700; }
    .when { color: var(--muted); font-size: 12px; }
    .quiet { color: var(--muted); font-size: 13px; }
    .gone { font-size: 14px; }
    .link { background: none; border: 0; color: var(--chip-info-fg); cursor: pointer; padding: 0; font-size: 14px; }
    .empty .row { display: grid; grid-template-columns: 10px 1fr; gap: 2px 8px; width: 100%; text-align: left;
                  background: none; border: 0; border-bottom: 1px solid var(--line); padding: 8px 0; cursor: pointer; }
    .empty .row .quiet { grid-column: 2; }
    .dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; background: var(--muted); }
    .dot.t-ok { background: var(--chip-ok-fg); }
    .dot.t-warn { background: var(--chip-warn-fg); }
    .dot.t-bad { background: var(--chip-bad-fg); }

    @media (max-width: 899px) {
      .inspector { position: fixed; inset: 0 0 0 auto; width: min(420px, 100vw); z-index: 20;
                   background: var(--white); padding: 16px; overflow-y: auto; border-left: 1px solid var(--line);
                   box-shadow: -8px 0 24px rgba(0, 0, 0, .12); transform: translateX(100%);
                   transition: transform .2s ease; }
      .inspector.open { transform: none; }
      .close { display: block; }
    }
  `
})
export class Inspector {
  protected readonly inspector = inject(InspectorService);
  protected readonly tab = signal<'overview' | 'events'>('overview');

  /** Where a vanished pod came from, guessed from its name, so the reader has somewhere to go. */
  protected readonly owner = computed<ObjectLink | null>(() => {
    const ref = this.inspector.selected();
    if (ref?.kind !== 'pod') {
      return null;
    }
    const parts = ref.name.split('-');
    return parts.length > 2
      ? { kind: 'deployment', name: parts.slice(0, -2).join('-'), label: 'Deployment', tone: null }
      : null;
  });

  protected open(link: ObjectLink): void {
    this.tab.set('overview');
    this.inspector.select({ kind: link.kind, name: link.name });
  }

  protected time(at: string | null): string {
    return at ? at.slice(11, 19) : '—';
  }
}

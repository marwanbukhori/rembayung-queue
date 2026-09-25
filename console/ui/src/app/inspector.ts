import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { InspectorService } from './inspector.service';
import { LogLine, ObjectLink } from './state';
import { malaysiaTime } from './time';

/**
 * The right-hand column of the cluster page: whatever object was clicked.
 *
 * One template for every kind. The server sends a headline, a tone and
 * label/value facts, so a Route and a CronJob render the same way and a new
 * kind needs no new component.
 *
 * From 1280px it is a panel over the graph's right edge while something is
 * selected. Below 1280px it becomes a drawer over the page, with a close button, because
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
              @if (d.kind === 'pod') {
                <button role="tab" [class.on]="tab() === 'logs'" (click)="showLogs()">Logs</button>
              }
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
            } @else if (tab() === 'logs' && d.kind === 'pod') {
              @let page = inspector.logPage();
              <div class="log-filters" role="group" aria-label="Which lines">
                @for (f of filters; track f) {
                  <button [class.on]="(page?.filter ?? inspector.logFilter()) === f"
                          [disabled]="!!page?.restricted && f !== 'events'"
                          [title]="page?.restricted && f !== 'events' ? 'Needs the console key' : ''"
                          (click)="inspector.setLogFilter(f)">{{ f }}</button>
                }
              </div>
              @if (page?.note) {
                <p class="quiet">{{ page?.note }}</p>
              }
              @if (page && !page.available) {
                <p class="pill bad">not readable: {{ page.detail }}</p>
              }
              <div class="log" #logBox (scroll)="follow = atBottom(logBox)">
                @for (l of inspector.logLines(); track $index) {
                  <div class="log-line" [class.warn]="l.level === 'WARN'" [class.err]="l.level === 'ERROR'">
                    <span class="when">{{ localTime(l.at) }}</span>
                    @if (l.event) {
                      <span class="ev">{{ l.event }}</span>
                    } @else if (l.level) {
                      <span class="lvl">{{ l.level }}</span>
                    }
                    <span class="msg">{{ l.message }}</span>
                    @for (kv of fieldsOf(l); track kv[0]) {
                      <span class="kv">{{ kv[0] }}={{ kv[1] }}</span>
                    }
                  </div>
                } @empty {
                  <p class="quiet">{{ page ? 'Nothing yet. New lines appear here as they are written.' : 'Reading…' }}</p>
                }
              </div>
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
    .log-filters { display: flex; gap: 6px; margin-bottom: 8px; }
    .log-filters button { font-family: var(--mono); font-size: 12px; border: 1px solid var(--line);
                          background: var(--white); color: var(--ink); border-radius: 999px; padding: 2px 10px; cursor: pointer; }
    .log-filters button.on { border-color: var(--ink); font-weight: 700; }
    .log-filters button:disabled { opacity: .45; cursor: not-allowed; }
    .log { max-height: 420px; overflow: auto; background: #1d1d1d; color: #d7e6df; border-radius: 6px;
           padding: 8px 10px; font-family: var(--mono); font-size: 12px; line-height: 1.5; }
    .log .quiet { color: #9aa; }
    .log-line { white-space: pre-wrap; word-break: break-word; }
    .log-line .when { color: #8a9; margin-right: 8px; }
    .log-line .ev { color: #7fd1a8; margin-right: 6px; }
    .log-line .lvl { color: #9ab; margin-right: 6px; }
    .log-line.warn .lvl, .log-line.warn .msg { color: #f6c26b; }
    .log-line.err .lvl, .log-line.err .msg { color: #ff8a80; }
    .log-line .kv { color: #9ab; margin-left: 6px; }
    .gone { font-size: 14px; }
    .link { background: none; border: 0; color: var(--chip-info-fg); cursor: pointer; padding: 0; font-size: 14px; }
    .empty .row { display: grid; grid-template-columns: 10px 1fr; gap: 2px 8px; width: 100%; text-align: left;
                  background: none; border: 0; border-bottom: 1px solid var(--line); padding: 8px 0; cursor: pointer; }
    .empty .row .quiet { grid-column: 2; }
    .dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; background: var(--muted); }
    .dot.t-ok { background: var(--chip-ok-fg); }
    .dot.t-warn { background: var(--chip-warn-fg); }
    .dot.t-bad { background: var(--chip-bad-fg); }

    /*
      Wide screens: a panel over the graph's right edge, only while something is
      selected. Closing it gives the graph its full width back.
    */
    @media (min-width: 1280px) {
      .inspector { position: absolute; top: 0; right: 0; bottom: 0; width: min(40%, 460px); overflow-y: auto;
                   background: var(--white); padding: 16px; border-left: 1px solid var(--line);
                   box-shadow: -8px 0 24px rgba(0, 0, 0, .12); display: none; }
      .inspector.open { display: block; }
      .close { display: block; }
    }
    @media (max-width: 1279px) {
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
  protected readonly tab = signal<'overview' | 'logs' | 'events'>('overview');
  protected readonly filters = ['all', 'warn', 'events'] as const;
  protected follow = true;
  private readonly logBox = viewChild<ElementRef<HTMLElement>>('logBox');

  constructor() {
    effect(() => this.inspector.logsOpen.set(this.tab() === 'logs'
      && this.inspector.selected()?.kind === 'pod'));
    // The graph selects objects directly, without going through open(); a
    // Deployment clicked while a pod's Logs tab was up must not inherit it.
    effect(() => {
      if (this.inspector.selected()?.kind !== 'pod' && this.tab() === 'logs') {
        this.tab.set('overview');
      }
    });
    // Stay at the newest line unless the reader has scrolled up to read.
    effect(() => {
      this.inspector.logLines();
      const box = this.logBox()?.nativeElement;
      if (box && this.follow) {
        queueMicrotask(() => (box.scrollTop = box.scrollHeight));
      }
    });
  }

  protected showLogs(): void {
    this.tab.set('logs');
    this.follow = true;
    // The effect above flips logsOpen on the next tick; ask for lines after it.
    queueMicrotask(() => this.inspector.openLogs());
  }

  protected atBottom(box: HTMLElement): boolean {
    return box.scrollHeight - box.scrollTop - box.clientHeight < 24;
  }

  protected fieldsOf(l: LogLine): [string, string][] {
    return Object.entries(l.fields ?? {});
  }

  /** Malaysia time, like every other clock on the site. */
  protected localTime(at: string | null): string {
    return malaysiaTime(at);
  }

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
    return malaysiaTime(at);
  }
}

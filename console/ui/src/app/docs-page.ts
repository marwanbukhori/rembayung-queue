import { Component, OnInit, computed, effect, inject, output } from '@angular/core';
import { DocsService, GROUP_LABELS } from './docs.service';

/**
 * The documentation list: specs, build notes and plans.
 *
 * <h2>Every row says more than its title</h2>
 * Twenty-four titles in three columns is a filing cabinet, not a reading list.
 * Each row therefore carries its group, the date or number in its filename, and
 * the document's own opening sentence — so a reader can choose one without
 * opening four.
 */
@Component({
  selector: 'rb-docs-page',
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">Documentation</span>
      </div>
      <div>
        <h1>Documentation</h1>
        <p class="lede">
          Specs, build notes and plans, rendered from Markdown baked into the image. The written
          record is the part of this project most likely to be read, so it ships inside the console
          rather than in a repository nobody will clone.
        </p>
      </div>

      @if (docs.listError(); as problem) {
        <p class="reason">{{ problem }}</p>
      } @else if (groups().length) {
        <div class="groups">
          @for (group of groups(); track group.key) {
            <section class="group">
              <div class="group-head">
                <h2 class="group-name">{{ group.label }}</h2>
                <p class="sub">{{ group.blurb }}</p>
              </div>
              @for (doc of group.docs; track doc.id) {
                <button class="row" (click)="open.emit(doc.id)">
                  <span class="row-meta">
                    <span class="row-group eyebrow">{{ group.singular }}</span>
                    <span class="row-stamp mono">{{ stampOf(doc.id) }}</span>
                  </span>
                  <span class="row-title">{{ doc.title }}</span>
                  <span class="row-blurb">{{ blurb(doc.id) }}</span>
                </button>
              }
            </section>
          }
        </div>
      } @else {
        <p class="reason">Loading…</p>
      }
    </div>
  `,
  styles: `
    .stack-24 { display: flex; flex-direction: column; gap: 24px; }
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .groups {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr));
      align-items: start;
    }
    .group {
      min-width: 0;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .group-head { padding: 16px; border-bottom: 1px solid var(--line); }
    .group-name { margin: 0; font-size: 19px; font-weight: 700; }
    .row {
      display: block;
      width: 100%;
      padding: 14px 16px;
      background: var(--white);
      border: 0;
      border-bottom: 1px solid var(--rule);
      font: inherit;
      color: var(--ink);
      text-align: left;
      cursor: pointer;
    }
    .row:last-child { border-bottom: 0; }
    .row:hover { background: var(--highlight); }
    .row-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 4px; }
    .row-group {
      font-size: 11px;
      color: var(--chip-neutral-fg);
      background: var(--chip-neutral-bg);
      border-radius: 999px;
      padding: 2px 8px;
    }
    .row-stamp { font-size: 12px; color: var(--muted); }
    .row-title { display: block; font-size: 15px; font-weight: 700; text-wrap: pretty; }
    .row-blurb { display: block; font-size: 13px; color: var(--ink-soft); margin-top: 4px; text-wrap: pretty; }
  `
})
export class DocsPage implements OnInit {
  readonly home = output<void>();
  readonly open = output<string>();

  protected readonly docs = inject(DocsService);

  private static readonly GROUPS = [
    {
      key: 'specs',
      label: 'Specs',
      blurb: 'What was agreed before anything was written, and why'
    },
    {
      key: 'notes',
      label: 'Build notes',
      blurb: 'How each piece actually works, written while building it'
    },
    {
      key: 'plans',
      label: 'Plans',
      blurb: 'The task-by-task plan each phase was executed against'
    }
  ];

  readonly groups = computed(() => {
    const list = this.docs.summaries() ?? [];
    return DocsPage.GROUPS
      .map((group) => ({
        ...group,
        singular: GROUP_LABELS[group.key],
        docs: list.filter((doc) => doc.group === group.key)
      }))
      .filter((group) => group.docs.length > 0);
  });

  constructor() {
    effect(() => {
      if (this.docs.summaries()) {
        this.docs.loadBlurbs();
      }
    });
  }

  blurb(id: string): string {
    return this.docs.blurbs()[id] ?? 'Opens the rendered document.';
  }

  /**
   * The date or sequence number the file is named with.
   *
   * Specs and plans are dated, build notes are numbered, and both orderings are
   * meaningful to a reader deciding where to start.
   */
  stampOf(id: string): string {
    const dated = id.match(/^(\d{4})-(\d{2})-(\d{2})-/);
    if (dated) {
      return `${dated[1]}-${dated[2]}-${dated[3]}`;
    }
    const numbered = id.match(/^(\d{2})-/);
    return numbered ? `no. ${Number(numbered[1])}` : '';
  }

  ngOnInit(): void {
    this.docs.load();
  }
}

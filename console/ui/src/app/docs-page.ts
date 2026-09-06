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

      <!--
        At the bottom, because it is how these documents came to exist rather
        than what they say. A reader who wants the system reads the build notes;
        a reader who wants the process reads this.
      -->
      <section class="method">
        <h2 class="method-name">How these were written</h2>
        <p class="method-text">
          Each phase started as a <strong>spec</strong>, argued out in conversation with Claude
          using its <span class="mono">brainstorming</span> superpower and committed before any code
          existed — what is being built, what it must not do, and what would count as finished.
        </p>
        <p class="method-text">
          From an approved spec, <span class="mono">writing-plans</span> produced a
          <strong>plan</strong>: numbered tasks, each naming the files it touches, the test to write
          first, and the commit that closes it. That plan is what execution followed, task by task —
          which is why every spec and plan here is dated before the build note beside it.
        </p>
        <p class="method-text">
          The <strong>build notes</strong> came last, written while building: what broke, what the
          measurement said, and which assumption turned out to be wrong. They are the only one of
          the three that could not have been written in advance.
        </p>
      </section>
    </div>
  `,
  styles: `
    .stack-24 { display: flex; flex-direction: column; gap: 24px; }
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .method {
      border-top: 1px solid var(--rule);
      padding-top: 20px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .method-name { margin: 0; font-size: 19px; font-weight: 700; }
    .method-text { margin: 0; font-size: 15px; color: var(--ink-soft); max-width: 78ch; text-wrap: pretty; }

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

  /**
   * Build notes first.
   *
   * They are what a reader actually wants: how each piece works, written while
   * building it. Specs and plans are the method that produced them - worth
   * showing, and not the thing to open with.
   */
  private static readonly GROUPS = [
    {
      key: 'notes',
      label: 'Build notes',
      blurb: 'How each piece actually works, written while building it'
    },
    {
      key: 'specs',
      label: 'Specs',
      blurb: 'What was agreed before anything was written, and why'
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

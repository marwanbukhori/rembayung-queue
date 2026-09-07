import { Component, OnInit, computed, effect, inject, output } from '@angular/core';
import { DocsService } from './docs.service';

/**
 * The build notes.
 *
 * <h2>Why only the notes</h2>
 * This page used to carry three groups: specs, notes and plans, twenty-four
 * documents across three columns. Fourteen of them described what was going to
 * be built and in what order — genuinely useful while building, and not what
 * someone opening a console wants to read. They are still in the repository.
 * What is published is the ten that describe what the thing actually does.
 *
 * <h2>Every row says more than its title</h2>
 * A list of titles is a filing cabinet, not a reading list. Each row carries the
 * note's number and its own opening sentence, so a reader can choose one without
 * opening four.
 */
@Component({
  selector: 'rb-docs-page',
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">Build notes</span>
      </div>
      <div>
        <h1>Build notes</h1>
        <p class="lede">
          One note per phase, written while that phase was being built rather than afterwards:
          what it does, what broke, what the measurement said, and which assumption turned out
          to be wrong. They are Markdown baked into this image, because the written record is
          the part of this project most likely to be read and least likely to be cloned.
        </p>
      </div>

      @if (docs.listError(); as problem) {
        <p class="reason">{{ problem }}</p>
      } @else if (notes().length) {
        <section class="list">
          @for (doc of notes(); track doc.id) {
            <button class="row" (click)="open.emit(doc.id)">
              <span class="row-stamp mono">{{ stampOf(doc.id) }}</span>
              <span class="row-body">
                <span class="row-title">{{ doc.title }}</span>
                <span class="row-blurb">{{ blurb(doc.id) }}</span>
              </span>
            </button>
          }
        </section>
      } @else {
        <p class="reason">Loading…</p>
      }

      <!--
        At the bottom, because it is how these came to exist rather than what
        they say. A reader who wants the system reads the notes; a reader who
        wants the process reads this.
      -->
      <section class="method">
        <h2 class="method-name">How these were written</h2>
        <p class="method-text">
          Each phase started as a <strong>spec</strong>, argued out in conversation with Claude
          using its <span class="mono">brainstorming</span> superpower and committed before any
          code existed — what is being built, what it must not do, and what would count as
          finished. From an approved spec, <span class="mono">writing-plans</span> produced a
          numbered <strong>plan</strong>: each task naming the files it touches, the test to write
          first, and the commit that closes it.
        </p>
        <p class="method-text">
          Those two are working documents and live in the repository, under
          <span class="mono">docs/superpowers</span>. The notes above are the third and the last
          written — the only one of the three that could not have been written in advance,
          because it records what actually happened.
        </p>
      </section>
    </div>
  `,
  styles: `
    .stack-24 { display: flex; flex-direction: column; gap: 24px; }
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }

    .list {
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
    }
    .row {
      display: flex;
      align-items: baseline;
      gap: 14px;
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
    /* Fixed width so the titles line up into a column a reader can scan. */
    .row-stamp { flex: none; width: 56px; font-size: 12px; color: var(--muted); }
    .row-body { min-width: 0; }
    .row-title { display: block; font-size: 15px; font-weight: 700; text-wrap: pretty; }
    .row-blurb { display: block; font-size: 13px; color: var(--ink-soft); margin-top: 4px; text-wrap: pretty; }

    .method {
      border-top: 1px solid var(--rule);
      padding-top: 20px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .method-name { margin: 0; font-size: 19px; font-weight: 700; }
    .method-text { margin: 0; font-size: 15px; color: var(--ink-soft); max-width: 78ch; text-wrap: pretty; }

    @media (max-width: 560px) {
      .row { flex-direction: column; gap: 4px; }
      .row-stamp { width: auto; }
    }
  `
})
export class DocsPage implements OnInit {
  readonly home = output<void>();
  readonly open = output<string>();

  protected readonly docs = inject(DocsService);

  /** The API already sorts by id, which is reading order; this is just the list. */
  readonly notes = computed(() => this.docs.summaries() ?? []);

  constructor() {
    effect(() => {
      if (this.docs.summaries()) {
        this.docs.loadBlurbs();
      }
    });
  }

  blurb(id: string): string {
    return this.docs.blurbs()[id] ?? 'Opens the rendered note.';
  }

  /**
   * The sequence number the file is named with.
   *
   * README has none and gets no stamp rather than a made-up one — it sorts last
   * and reads as the index it is.
   */
  stampOf(id: string): string {
    const numbered = id.match(/^(\d{2})-/);
    return numbered ? `no. ${Number(numbered[1])}` : '';
  }

  ngOnInit(): void {
    this.docs.load();
  }
}

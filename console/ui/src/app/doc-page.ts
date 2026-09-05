import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DocsService } from './docs.service';

/**
 * One rendered document. The title comes from the list the reader clicked
 * through; the body is fetched fresh for this id, because it is read once per
 * open rather than kept warm for a page nobody may return to.
 */
@Component({
  selector: 'rb-doc-page',
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <button class="btn-crumb" (click)="docs.emit()">Documentation</button>
        <span>/</span>
        <span style="color: var(--ink);">{{ title() }}</span>
      </div>
      <article class="panel doc">
        <div class="accent-top"></div>
        <div class="doc-body">
          @if (error(); as problem) {
            <p class="reason">{{ problem }}</p>
          } @else {
            <h1 style="margin: 0 0 20px;">{{ title() }}</h1>
            @if (html(); as body) {
              <div class="markdown-body" [innerHTML]="body"></div>
            } @else {
              <p class="reason">Loading…</p>
            }
          }
          <div class="doc-footer">
            <button class="btn btn-secondary" (click)="docs.emit()">Back to documentation</button>
            <button class="btn-tertiary" (click)="visitor.emit()">Start a simulation instead</button>
          </div>
        </div>
      </article>
    </div>
  `,
  styles: `
    .stack-24 { display: flex; flex-direction: column; gap: 24px; }
    .crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    /*
     * Full content width. It used to be capped at 78ch, which left a third of a
     * wide screen empty and squeezed every table and code block in these
     * documents into a column narrower than the lines they contain. The readable
     * measure now belongs to the prose itself, in .markdown-body, so tables and
     * listings can use the whole panel.
     */
    .doc { width: 100%; }
    .doc-body { padding: 32px 32px 28px; }
    .doc-footer {
      margin-top: 28px;
      padding-top: 20px;
      border-top: 1px solid var(--rule);
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
    }
  `
})
export class DocPage {
  readonly id = input.required<string>();
  readonly home = output<void>();
  readonly docs = output<void>();
  readonly visitor = output<void>();

  private readonly docsService = inject(DocsService);

  readonly html = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly title = computed(() => this.docsService.titleOf(this.id()));

  constructor() {
    effect(() => {
      const id = this.id();
      this.html.set(null);
      this.error.set(null);
      this.docsService.render(id).subscribe({
        // untracked: the title is a convenience for the comparison below, not
        // a reason to re-fetch the document when the list lands.
        next: (body) => this.html.set(withoutRepeatedTitle(body, untracked(this.title))),
        error: () => this.error.set('the console could not open this document')
      });
    });
  }
}

/**
 * Drop the document's own opening heading when the page has already shown it.
 *
 * Every file here starts with the `# ` line the list took its title from, so
 * the reading view opened with the same sentence twice, in two sizes. Anything
 * that is not that exact heading is left alone.
 */
function withoutRepeatedTitle(html: string, title: string): string {
  const first = html.match(/^\s*<h1>([\s\S]*?)<\/h1>/);
  if (!first) {
    return html;
  }
  const heading = first[1].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
  return heading === title.trim() ? html.slice(first[0].length) : html;
}

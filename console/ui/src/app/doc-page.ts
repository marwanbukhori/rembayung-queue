import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
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
        <button class="btn-crumb" (click)="home.emit()">Public</button>
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
            <button class="btn-tertiary" (click)="visitor.emit()">Start a drop instead</button>
          </div>
        </div>
      </article>
    </div>
  `,
  styles: `
    .stack-24 { display: flex; flex-direction: column; gap: 24px; }
    .crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .doc { max-width: 78ch; }
    .doc-body { padding: 32px 24px; }
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
        next: (body) => this.html.set(body),
        error: () => this.error.set('the console could not open this document')
      });
    });
  }
}

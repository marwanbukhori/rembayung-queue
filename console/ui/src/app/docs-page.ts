import { Component, OnInit, computed, inject, output } from '@angular/core';
import { DocsService } from './docs.service';

/**
 * The documentation list: specs, build notes and plans, grouped the way the
 * design groups them. Each row opens into the single document view.
 */
@Component({
  selector: 'rb-docs-page',
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Public</button>
        <span>/</span>
        <span style="color: var(--ink);">Documentation</span>
      </div>
      <div>
        <h1>Documentation</h1>
        <p class="lede">
          Specs, build notes and plans, rendered from Markdown baked into the image. The written
          record is the part of this project most likely to be read.
        </p>
      </div>

      @if (docs.listError(); as problem) {
        <p class="reason">{{ problem }}</p>
      } @else if (groups().length) {
        <div class="groups">
          @for (group of groups(); track group.key) {
            <div class="card group">
              <div class="group-head">{{ group.label }}</div>
              @for (doc of group.docs; track doc.id) {
                <button class="row" (click)="open.emit(doc.id)">
                  <span class="row-title">{{ doc.title }}</span>
                  <span class="row-go">Read</span>
                </button>
              }
            </div>
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
      grid-template-columns: repeat(auto-fit, minmax(min(280px, 100%), 1fr));
      align-items: start;
    }
    .group { padding: 0; overflow: hidden; }
    .group-head {
      padding: 16px;
      font-size: 17px;
      font-weight: 700;
      border-bottom: 1px solid var(--line);
    }
    .row {
      display: flex;
      width: 100%;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      background: var(--white);
      border: 0;
      border-bottom: 1px solid var(--rule);
      font: inherit;
      color: var(--ink);
      text-align: left;
      cursor: pointer;
      min-height: 44px;
    }
    .row:last-child { border-bottom: 0; }
    .row:hover { background: var(--highlight); }
    .row-title { font-size: 14px; font-weight: 700; min-width: 0; }
    .row-go { margin-left: auto; font-size: 14px; font-weight: 700; color: var(--dhl-red); flex: none; }
  `
})
export class DocsPage implements OnInit {
  readonly home = output<void>();
  readonly open = output<string>();

  protected readonly docs = inject(DocsService);

  private static readonly LABELS: Record<string, string> = {
    specs: 'Specs',
    notes: 'Build notes',
    plans: 'Plans'
  };
  private static readonly ORDER = ['specs', 'notes', 'plans'];

  readonly groups = computed(() => {
    const list = this.docs.summaries() ?? [];
    return DocsPage.ORDER
      .map((key) => ({ key, label: DocsPage.LABELS[key], docs: list.filter((doc) => doc.group === key) }))
      .filter((group) => group.docs.length > 0);
  });

  ngOnInit(): void {
    this.docs.load();
  }
}

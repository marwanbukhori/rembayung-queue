import { Component, computed, inject, signal } from '@angular/core';
import { DocPage } from './doc-page';
import { DocsPage } from './docs-page';
import { Operations } from './operations';
import { PodsPage } from './pods-page';
import { PublicHome } from './public-home';
import { StateService } from './state.service';
import { Visitor } from './visitor';
import { hasConsoleKey } from './key';

/** Which surface is on screen. Tiers, then pages within the public tier. */
type Surface = 'home' | 'pods' | 'docs' | 'doc' | 'visitor' | 'ops';

/**
 * The shell: the DHL bar, the tier switcher, and whichever surface is showing.
 *
 * Ported from console/design/demo-console-v3.html, which was already written as
 * Angular. The layout, the copy and the palette are the design's; the wiring is
 * this task's.
 */
@Component({
  selector: 'app-root',
  imports: [PublicHome, PodsPage, DocsPage, DocPage, Visitor, Operations],
  template: `
    <header class="bar">
      <div class="bar-inner">
        <div class="brand">
          <div class="wordmark">Rembayung</div>
          <div class="ns">ns/{{ namespace() }}</div>
        </div>
        <div style="flex: 1 1 20px;"></div>
        <div class="bar-right">
          <nav class="tabs">
            <button class="nav-tab" [class.on]="isPublic()" (click)="show('home')">Public</button>
            <button class="nav-tab" [class.on]="surface() === 'visitor'" (click)="show('visitor')">Visitor</button>
            <button class="nav-tab" [class.on]="surface() === 'ops'" (click)="show('ops')">Operator</button>
          </nav>
          <div class="live">
            <span class="pulse"></span>
            <span>{{ keyLabel() }}</span>
          </div>
        </div>
      </div>
    </header>

    <main>
      @switch (surface()) {
        @case ('home') {
          <rb-public-home (pods)="show('pods')" (visitor)="show('visitor')" (docs)="show('docs')" (open)="openDoc($event)" />
        }
        @case ('pods') {
          <rb-pods-page (home)="show('home')" />
        }
        @case ('docs') {
          <rb-docs-page (home)="show('home')" (open)="openDoc($event)" />
        }
        @case ('doc') {
          <rb-doc-page [id]="selectedDocId()!" (home)="show('home')" (docs)="show('docs')" (visitor)="show('visitor')" />
        }
        @case ('visitor') {
          <rb-visitor />
        }
        @case ('ops') {
          <rb-operations />
        }
      }

      <footer>
        <span>Rembayung booking queue, phase 7. A console a stranger can drive.</span>
        <span class="mono">{{ readingLabel() }}</span>
      </footer>
    </main>
  `,
  styles: `
    .bar { background: #FFCC00; border-bottom: 1px solid #E6B800; }
    .bar-inner {
      max-width: 1120px;
      margin: 0 auto;
      padding: 10px 16px;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px 24px;
      min-height: 64px;
    }
    .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
    .wordmark { font-size: 19px; font-weight: 800; letter-spacing: -0.015em; white-space: nowrap; }
    .ns {
      font-family: var(--mono);
      font-size: 12px;
      background: rgba(0, 0, 0, .08);
      border-radius: 2px;
      padding: 2px 8px;
      white-space: nowrap;
      letter-spacing: .04em;
    }
    .bar-right { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 24px; }
    .tabs { display: flex; gap: 24px; }
    .live {
      display: flex;
      align-items: center;
      gap: 8px;
      font-family: var(--mono);
      font-size: 12px;
      letter-spacing: .04em;
      background: rgba(0, 0, 0, .08);
      border-radius: 2px;
      padding: 3px 8px;
      white-space: nowrap;
    }
    .pulse {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--ink);
      animation: livePulse 2s ease-in-out infinite;
    }
    main {
      max-width: 1120px;
      margin: 0 auto;
      padding: 32px 16px 64px;
      display: flex;
      flex-direction: column;
      gap: 32px;
    }
    footer {
      border-top: 1px solid var(--line);
      padding-top: 16px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px 24px;
      justify-content: space-between;
      font-size: 14px;
      color: var(--muted);
    }
  `
})
export class App {
  private readonly state = inject(StateService);

  /**
   * Whatever namespace the API says it read from, or a dash until it answers.
   *
   * Deliberately not a constant. The header used to read "ns/rembayung" while
   * the backend was reading marwanbukhori-dev, so it named a namespace nobody
   * has — and it looked authoritative while doing it.
   */
  namespace(): string {
    return this.state.view()?.pods?.namespace ?? '—';
  }

  readonly surface = signal<Surface>('home');
  readonly selectedDocId = signal<string | null>(null);

  private static readonly PUBLIC_SURFACES: Surface[] = ['home', 'pods', 'docs', 'doc'];

  isPublic(): boolean {
    return App.PUBLIC_SURFACES.includes(this.surface());
  }

  /**
   * One key opens the whole console — there are no tiers and no read-only
   * view, so this says whether the page has one and nothing more.
   */
  readonly keyLabel = computed(() => {
    if (!hasConsoleKey()) {
      return 'no key';
    }
    return this.state.transportError()?.includes('key') ? 'key refused' : 'keyed';
  });

  /** Which drop and slot are actually on screen, named by the services. */
  readonly readingLabel = computed(() => {
    const drop = this.state.view()?.drop;
    return drop && drop.available
      ? `reading slot ${drop.slotId} and drop ${drop.dropId}`
      : 'nothing read yet';
  });

  show(surface: Surface): void {
    this.surface.set(surface);
    window.scrollTo(0, 0);
  }

  openDoc(id: string): void {
    this.selectedDocId.set(id);
    this.show('doc');
  }
}

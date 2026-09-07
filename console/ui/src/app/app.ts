import { Component, computed, inject, signal } from '@angular/core';
import { ClusterPage } from './cluster-page';
import { DocPage } from './doc-page';
import { DocsPage } from './docs-page';
import { PublicHome } from './public-home';
import { StateService } from './state.service';
import { Visitor } from './visitor';
import { hasConsoleKey } from './key';

/** Which surface is on screen. */
type Surface = 'home' | 'cluster' | 'docs' | 'doc' | 'visitor';

/**
 * The shell: a persistent navbar, and whichever surface is showing.
 *
 * <h2>Why the navbar is its own band</h2>
 * The section switcher used to be three quiet buttons inside the brand strip,
 * which read as part of the page rather than as navigation — someone landing
 * cold could not tell where they were or how to get back. It is now a bar of its
 * own under the brand, sticky, with the current section marked, so the answer to
 * "where am I" is on screen at every scroll position.
 *
 * The palette and the brand strip are from console/design/demo-console-v3.html,
 * which is the repository owner's design and the source of truth for this page.
 */
@Component({
  selector: 'app-root',
  imports: [PublicHome, ClusterPage, DocsPage, DocPage, Visitor],
  template: `
    <header class="navbar">
      <div class="brandband">
        <div class="inner">
          <button class="brand" (click)="show('home')" title="Back to the overview">
            <img class="mark" src="dhl.png" alt="DHL" />
          </button>
          <div style="flex: 1 1 20px;"></div>
          <div class="badges">
            <div class="badge mono">ns/{{ namespace() }}</div>
            <div class="badge mono">
              <span class="pulse"></span>
              <span>{{ keyLabel() }}</span>
            </div>
          </div>
        </div>
      </div>

      <nav class="sections" aria-label="Sections">
        <div class="inner">
          @for (link of links; track link.surface) {
            <button
              class="nav-link"
              [class.on]="link.surface === current()"
              [attr.aria-current]="link.surface === current() ? 'page' : null"
              (click)="show(link.surface)">{{ link.label }}</button>
          }
        </div>
      </nav>
    </header>

    <main>
      @switch (surface()) {
        @case ('home') {
          <rb-public-home (visitor)="show('visitor')" (docs)="show('docs')" />
        }
        @case ('cluster') {
          <rb-cluster-page (home)="show('home')" />
        }
        @case ('docs') {
          <rb-docs-page (home)="show('home')" (open)="openDoc($event)" />
        }
        @case ('doc') {
          <rb-doc-page [id]="selectedDocId()!" (home)="show('home')" (docs)="show('docs')" (visitor)="show('visitor')" />
        }
        @case ('visitor') {
          <rb-visitor (home)="show('home')" (docs)="show('docs')" />
        }
      }
    </main>
  `,
  styles: `
    .navbar { position: sticky; top: 0; z-index: 20; }
    .brandband { background: #FFCC00; border-bottom: 1px solid #E6B800; }
    .inner {
      max-width: 1120px;
      margin: 0 auto;
      padding: 0 16px;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 24px;
    }
    .brandband .inner { padding-top: 8px; padding-bottom: 8px; min-height: 56px; }
    .brand {
      display: flex;
      align-items: baseline;
      gap: 10px;
      min-width: 0;
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      color: var(--ink);
      cursor: pointer;
      text-align: left;
    }
    /*
      The supplied PNG is square with the wordmark occupying a band across the
      middle, so height alone renders a speck surrounded by padding. A fixed box
      with object-fit: cover crops the padding away and shows the mark itself.
    */
    .mark { flex: none; height: 34px; width: 132px; object-fit: cover; display: block; }
    .badges { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .badge {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
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

    .sections { background: var(--white); border-bottom: 1px solid var(--line); }
    .sections .inner { gap: 0 8px; flex-wrap: nowrap; overflow-x: auto; }
    .nav-link {
      background: none;
      border: 0;
      border-bottom: 3px solid transparent;
      padding: 12px 12px 9px;
      font: inherit;
      font-size: 14px;
      font-weight: 700;
      color: var(--ink-soft);
      cursor: pointer;
      white-space: nowrap;
      flex: none;
      transition: color 120ms var(--ease), border-color 120ms var(--ease);
    }
    .nav-link:hover { color: var(--ink); background: var(--rule); }
    .nav-link.on { color: var(--dhl-red); border-bottom-color: var(--dhl-red); }

    main {
      max-width: 1120px;
      margin: 0 auto;
      padding: 32px 16px 64px;
      display: flex;
      flex-direction: column;
      gap: 32px;
    }
  `
})
export class App {
  private readonly state = inject(StateService);

  /** The persistent navigation. Order is the order a first-time reader needs them in. */
  readonly links: { surface: Surface; label: string }[] = [
    { surface: 'home', label: 'Overview' },
    { surface: 'visitor', label: 'Run a simulation' },
    { surface: 'cluster', label: 'Cluster' },
    { surface: 'docs', label: 'Build notes' }
  ];

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

  /** Reading a note is still being in Build notes, so the nav says so. */
  readonly current = computed<Surface>(() => (this.surface() === 'doc' ? 'docs' : this.surface()));

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

  /** Which session and slot are actually on screen, named by the services. */

  show(surface: Surface): void {
    this.surface.set(surface);
    window.scrollTo(0, 0);
  }

  openDoc(id: string): void {
    this.selectedDocId.set(id);
    this.show('doc');
  }
}

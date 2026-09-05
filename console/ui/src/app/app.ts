import { Component, computed, inject, signal } from '@angular/core';
import { ClusterPage } from './cluster-page';
import { DocPage } from './doc-page';
import { DocsPage } from './docs-page';
import { Operations } from './operations';
import { PublicHome } from './public-home';
import { StateService } from './state.service';
import { Visitor } from './visitor';
import { hasConsoleKey } from './key';

/** Which surface is on screen. */
type Surface = 'home' | 'cluster' | 'docs' | 'doc' | 'visitor' | 'ops';

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
  imports: [PublicHome, ClusterPage, DocsPage, DocPage, Visitor, Operations],
  template: `
    <header class="navbar">
      <div class="brandband">
        <div class="inner">
          <button class="brand" (click)="show('home')" title="Back to the overview">
            <svg class="mark" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
              <rect width="24" height="24" rx="3" fill="#1A1A1A" />
              <path d="M4 8h13M4 12h16M4 16h9" stroke="#FFCC00" stroke-width="2.2" stroke-linecap="round" />
            </svg>
            <span class="wordmark">Rembayung</span>
            <span class="tagline">booking queue simulation</span>
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
          <rb-public-home (cluster)="show('cluster')" (visitor)="show('visitor')" (docs)="show('docs')" (open)="openDoc($event)" />
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
    .mark { align-self: center; flex: none; border-radius: 3px; }
    .wordmark { font-size: 19px; font-weight: 800; letter-spacing: -0.015em; white-space: nowrap; }
    .tagline { font-size: 13px; color: #4A4A4A; white-space: nowrap; }
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

  /** The persistent navigation. Order is the order a first-time reader needs them in. */
  readonly links: { surface: Surface; label: string }[] = [
    { surface: 'home', label: 'Overview' },
    { surface: 'visitor', label: 'Run a simulation' },
    { surface: 'cluster', label: 'Cluster' },
    { surface: 'docs', label: 'Documentation' },
    { surface: 'ops', label: 'Operator' }
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

  /** Reading a document is still being in Documentation, so the nav says so. */
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
  readonly readingLabel = computed(() => {
    const drop = this.state.view()?.drop;
    return drop && drop.available
      ? `reading slot ${drop.slotId} of session ${drop.dropId}`
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

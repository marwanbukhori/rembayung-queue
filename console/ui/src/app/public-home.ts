import { Component, OnInit, computed, inject, output } from '@angular/core';
import { CanonicalDrop } from './canonical-drop';
import { DocsService } from './docs.service';
import { PodHealthPanel } from './pod-health';
import { Placeholder } from './placeholder';
import { StateService } from './state.service';

/** The public page: the claim, the canonical drop, and the cluster under it. */
@Component({
  selector: 'rb-public-home',
  imports: [CanonicalDrop, PodHealthPanel, Placeholder],
  template: `
    <div class="stack">
      <section class="panel">
        <div class="accent-top"></div>
        <div class="hero">
          <div class="hero-copy">
            <h1 class="headline">250 seats, never oversold</h1>
            <p class="hero-lede">
              Start your own drop, send two hundred customers at it in one second, and watch the
              invariant hold on your own data. No cluster account, nothing shared with anyone else
              looking at this page.
            </p>
          </div>
          <div class="hero-actions">
            <button class="btn btn-primary" (click)="visitor.emit()">Start a drop</button>
            <button class="btn btn-secondary" (click)="docs.emit()">Read the spec</button>
          </div>
        </div>
      </section>

      <rb-canonical-drop />

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Pod health</h2>
            <p class="sub">Read with a namespace scoped ServiceAccount</p>
          </div>
          <button class="btn-tertiary" (click)="pods.emit()">See more</button>
        </div>
        <rb-pod-health />
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Deploy history</h2>
            <p class="sub">From the Deployments' own annotations</p>
          </div>
        </div>
        <rb-placeholder
          note="Not read yet. The rows come from each Deployment's own rollout annotations rather than a table this console keeps, so they arrive with the cluster reader that task 8 wires up." />
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Documentation</h2>
            <p class="sub">Rendered from Markdown baked into the image</p>
          </div>
          <button class="btn-tertiary" (click)="docs.emit()">See more</button>
        </div>
        @if (featured().length) {
          <div class="doc-cards">
            @for (doc of featured(); track doc.id) {
              <button class="doc-card" (click)="open.emit(doc.id)">
                <span class="doc-card-title">{{ doc.title }}</span>
                <span class="doc-card-go">Read</span>
              </button>
            }
          </div>
        } @else {
          <rb-placeholder note="Loading the documentation baked into the image." />
        }
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Links out</h2>
            <p class="sub">Three places to look when this page is not enough</p>
          </div>
        </div>
        <div class="links">
          <a class="link-card" href="https://console-openshift-console.apps.rm3.7wse.p1.openshiftapps.com" rel="noreferrer">
            <div class="link-name">OpenShift console</div>
            <div class="link-note">Workloads, routes and quota</div>
          </a>
          <div class="link-card">
            <div class="link-name">Dynatrace tenant</div>
            <div class="link-note">Traces through the gate. Trial, may have expired.</div>
          </div>
          <div class="link-card">
            <div class="link-name">Splunk stack</div>
            <div class="link-note">Structured logs and the audit trail. Trial, may have expired.</div>
          </div>
        </div>
      </section>

      @if (state.transportError(); as problem) {
        <p class="reason">The numbers above may be stale: {{ problem }}.</p>
      }
    </div>
  `,
  styles: `
    .hero {
      padding: 32px 24px;
      display: flex;
      flex-wrap: wrap;
      gap: 24px 32px;
      align-items: flex-end;
      justify-content: space-between;
    }
    .hero-copy { flex: 1 1 320px; min-width: 0; }
    .headline {
      margin: 0 0 12px;
      font-size: 36px;
      font-weight: 800;
      letter-spacing: -0.02em;
      line-height: 1.15;
      max-width: 22ch;
    }
    .hero-lede { margin: 0; font-size: 17px; color: var(--ink-soft); max-width: 60ch; text-wrap: pretty; }
    .hero-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
    .links {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
    }
    .link-card {
      min-width: 0;
      display: block;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      padding: 16px;
      color: var(--ink);
      text-decoration: none;
    }
    a.link-card:hover { border-color: var(--ink); color: var(--ink); }
    .link-name { font-size: 17px; font-weight: 700; }
    .link-note { font-size: 14px; color: var(--ink-soft); margin-top: 2px; }
    .doc-cards {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
    }
    .doc-card {
      min-width: 0;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      text-align: left;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      padding: 20px;
      font: inherit;
      color: var(--ink);
      cursor: pointer;
      gap: 8px;
    }
    .doc-card:hover { border-color: var(--ink); }
    .doc-card-title { font-size: 15px; font-weight: 700; text-wrap: pretty; }
    .doc-card-go { font-size: 14px; font-weight: 700; color: var(--dhl-red); }
  `
})
export class PublicHome implements OnInit {
  readonly pods = output<void>();
  readonly docs = output<void>();
  readonly open = output<string>();
  readonly visitor = output<void>();

  protected readonly state = inject(StateService);
  private readonly docsService = inject(DocsService);

  /** Three documents, enough to show the shape of the record without duplicating the full list. */
  readonly featured = computed(() => (this.docsService.summaries() ?? []).slice(0, 3));

  ngOnInit(): void {
    this.docsService.load();
  }
}

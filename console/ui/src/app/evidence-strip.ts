import { Component } from '@angular/core';

/** One captured screen from a tool this project actually runs against. */
interface Shot {
  /** File under public/evidence, served from the web root. */
  src: string;
  tool: string;
  /** What the picture shows, in the words someone would use looking at it. */
  caption: string;
  /** Where to go to see the live version, or null when it needs a login nobody has. */
  href: string | null;
  linkLabel: string;
}

/**
 * Screenshots of the three tools the written record keeps referring to.
 *
 * <h2>Why pictures, when the console already probes all three</h2>
 * The monitoring panel on the cluster page reports whether Splunk and Dynatrace
 * are receiving anything, which is the honest thing it can do from inside the
 * cluster: it holds a write-only ingest token and an install-only agent token,
 * so it cannot read either vendor back. These are the other half — what the data
 * actually looks like once it arrives, which no amount of probing can show.
 *
 * <h2>Why they are static files</h2>
 * Both vendors are trial tenants behind their own logins. A live embed would be
 * an iframe to a sign-in page, and a reader without an account would see nothing
 * at all. A captured screen is honest about being a capture, and it still works
 * when a trial has lapsed — which, for a demo whose whole point is that it keeps
 * working, is the safer choice.
 */
@Component({
  selector: 'rb-evidence-strip',
  template: `
    <section class="evidence">
      <div class="head">
        <h2 class="name">What it looks like in the tools</h2>
        <p class="sub">
          The pipeline that ships this, and the two monitors it ships to. Captured screens rather
          than embeds: both vendors sit behind their own login, so an embed would show a reader
          nothing but a sign-in page.
        </p>
      </div>

      <div class="grid">
        @for (shot of shots; track shot.src) {
          <figure class="shot">
            <a class="frame" [href]="shot.src" target="_blank" rel="noreferrer"
               [attr.aria-label]="'Open the full ' + shot.tool + ' screenshot'">
              <img [src]="shot.src" [alt]="shot.tool + ': ' + shot.caption" loading="lazy" />
            </a>
            <figcaption>
              <span class="tool">{{ shot.tool }}</span>
              <span class="caption">{{ shot.caption }}</span>
              @if (shot.href) {
                <a class="live" [href]="shot.href" target="_blank" rel="noreferrer">
                  {{ shot.linkLabel }} ↗
                </a>
              } @else {
                <span class="needs-login">{{ shot.linkLabel }}</span>
              }
            </figcaption>
          </figure>
        }
      </div>
    </section>
  `,
  styles: `
    .evidence { display: flex; flex-direction: column; gap: 16px; }
    .head { display: flex; flex-direction: column; gap: 4px; }
    .name { margin: 0; font-size: 19px; font-weight: 700; }
    .sub { margin: 0; font-size: 15px; color: var(--ink-soft); max-width: 78ch; text-wrap: pretty; }

    .grid {
      display: grid; gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr));
      align-items: start;
    }

    .shot {
      margin: 0; min-width: 0;
      background: var(--white);
      border: 1px solid var(--line);
      border-radius: 4px;
      overflow: hidden;
      display: flex; flex-direction: column;
    }
    /* A fixed aspect box so three shots of different heights still line up, and
       object-fit keeps the top of each screen — which is where the thing worth
       seeing always is. */
    .frame {
      display: block; aspect-ratio: 16 / 10; background: var(--canvas);
      border-bottom: 1px solid var(--line); overflow: hidden;
    }
    .frame img { display: block; width: 100%; height: 100%; object-fit: cover; object-position: top left; }
    .frame:hover img { opacity: .92; }

    figcaption { padding: 12px 14px; display: flex; flex-direction: column; gap: 4px; }
    .tool { font-size: 15px; font-weight: 700; }
    .caption { font-size: 13px; color: var(--ink-soft); text-wrap: pretty; }
    .live { font-size: 13px; font-weight: 700; color: var(--dhl-red); text-decoration: none; margin-top: 2px; }
    .live:hover { text-decoration: underline; }
    .needs-login { font-size: 13px; color: var(--muted); margin-top: 2px; }
  `
})
export class EvidenceStrip {
  /**
   * Only screens that exist are listed. An entry whose file is missing renders a
   * broken image, which looks worse than not claiming to have the picture.
   */
  protected readonly shots: Shot[] = [
    {
      src: '/evidence/github-actions.png',
      tool: 'GitHub Actions',
      caption:
        'Every commit tested, built and deployed. CI publishes an image per service, '
        + 'CD deploys it and rolls back on its own if the smoke test fails.',
      href: 'https://github.com/marwanbukhori/rembayung-queue/actions',
      linkLabel: 'See the live runs'
    }
  ];
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, from, mergeMap } from 'rxjs';
import { DocSummary } from './docs';

/** How many document bodies to fetch at once while building the one-line summaries. */
const SUMMARY_CONCURRENCY = 3;

/** Long enough to say what a document is about, short enough to stay one line of a card. */
const SUMMARY_CHARS = 150;

/**
 * The documentation baked into the image: a list fetched once, and each
 * document's rendered HTML fetched on demand when a reader opens it.
 *
 * The list does not change while the console runs — it is the set of files the
 * image was built with — so it is fetched once and cached here rather than
 * re-read on every visit to the documentation page.
 *
 * <h2>Why the one-line summaries are derived in the browser</h2>
 * GET /api/docs answers with an id, a title and a group, and that contract is
 * fixed. A bare title is not enough for a stranger to choose between twenty-four
 * documents, so the blurbs come from the documents themselves: the same rendered
 * HTML the reading view uses, with its first paragraph taken as the summary.
 * Nothing is invented here — a document whose body cannot be read simply shows
 * its group and no blurb.
 */
@Injectable({ providedIn: 'root' })
export class DocsService {
  private readonly http = inject(HttpClient);

  readonly summaries = signal<DocSummary[] | null>(null);
  readonly listError = signal<string | null>(null);

  /** id to first paragraph, filled in as the bodies arrive. */
  readonly blurbs = signal<Record<string, string>>({});

  private blurbsRequested = false;

  load(): void {
    if (this.summaries() !== null) {
      return;
    }
    this.http.get<DocSummary[]>('/api/docs').subscribe({
      next: (list) => this.summaries.set(list),
      error: () => this.listError.set('the console could not list the documentation')
    });
  }

  /**
   * Fill in the blurbs, once per session, in the background.
   *
   * Deliberately not part of load(): a page that only needs titles should not
   * pull two dozen document bodies, and the ones that do need blurbs can wait
   * for them to arrive a few at a time rather than blocking on all of them.
   */
  loadBlurbs(): void {
    if (this.blurbsRequested) {
      return;
    }
    this.blurbsRequested = true;
    const list = this.summaries();
    if (!list) {
      // The list has not landed yet; try again on the next call rather than
      // recording that nothing was fetched.
      this.blurbsRequested = false;
      return;
    }
    from(list)
      .pipe(mergeMap((doc) => this.blurbFor(doc.id), SUMMARY_CONCURRENCY))
      .subscribe();
  }

  private blurbFor(id: string): Observable<unknown> {
    return new Observable((observer) => {
      this.render(id).subscribe({
        next: (html) => {
          const blurb = firstParagraph(html);
          if (blurb) {
            this.blurbs.update((all) => ({ ...all, [id]: blurb }));
          }
          observer.complete();
        },
        // A document that will not render still belongs in the list; it just
        // arrives without a blurb.
        error: () => observer.complete()
      });
    });
  }

  /** The rendered HTML body for one document. Fetched fresh, not cached: it is read once per open. */
  render(id: string): Observable<string> {
    return this.http.get(`/api/docs/${id}`, { responseType: 'text' });
  }

  titleOf(id: string): string {
    return this.summaries()?.find((doc) => doc.id === id)?.title ?? id;
  }
}

/** The group names the API uses, in the words the page shows them in. */
export const GROUP_LABELS: Record<string, string> = {
  specs: 'Spec',
  notes: 'Build note',
  plans: 'Plan'
};

/**
 * The first paragraph of a rendered document that actually says something.
 *
 * Two kinds of opening are skipped, because every document in this record has
 * one or the other and neither describes the document:
 *
 * <ul>
 *   <li>a blockquote — the plans open with a note addressed to the agent that
 *       executes them, not to a reader;</li>
 *   <li>a run of bolded labels — <em>Date</em>, <em>Status</em>, <em>Covers</em>,
 *       <em>Commits</em> — which is front matter. Two or more of them in one
 *       paragraph is the tell; a single one is a real sentence, and
 *       <em>Goal:</em> opening a plan is exactly the line worth showing.</li>
 * </ul>
 */
function firstParagraph(html: string): string | null {
  const body = html.replace(/<blockquote>[\s\S]*?<\/blockquote>/g, '');
  const paragraphs = body.match(/<p>[\s\S]*?<\/p>/g);
  if (!paragraphs) {
    return null;
  }
  for (const paragraph of paragraphs) {
    if ((paragraph.match(/<strong>[^<]{1,24}:<\/strong>/g) ?? []).length >= 2) {
      continue;
    }
    const text = decode(paragraph.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
    if (text.length < 24) {
      continue;
    }
    return truncate(text);
  }
  return null;
}

function truncate(text: string): string {
  if (text.length <= SUMMARY_CHARS) {
    return text;
  }
  const cut = text.slice(0, SUMMARY_CHARS);
  const space = cut.lastIndexOf(' ');
  return `${(space > 60 ? cut.slice(0, space) : cut).replace(/[,;:.\s]+$/, '')}…`;
}

/**
 * The handful of entities commonmark emits. A DOM parse would cover more, but
 * this text is only ever inserted as an interpolated string, never as HTML, so
 * there is nothing here that has to be exhaustive to be safe.
 */
function decode(text: string): string {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&');
}

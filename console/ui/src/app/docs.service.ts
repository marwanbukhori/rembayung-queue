import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { DocSummary } from './docs';

/**
 * The documentation baked into the image: a list fetched once, and each
 * document's rendered HTML fetched on demand when a reader opens it.
 *
 * The list does not change while the console runs — it is the set of files the
 * image was built with — so it is fetched once and cached here rather than
 * re-read on every visit to the documentation page.
 */
@Injectable({ providedIn: 'root' })
export class DocsService {
  private readonly http = inject(HttpClient);

  readonly summaries = signal<DocSummary[] | null>(null);
  readonly listError = signal<string | null>(null);

  load(): void {
    if (this.summaries() !== null) {
      return;
    }
    this.http.get<DocSummary[]>('/api/docs').subscribe({
      next: (list) => this.summaries.set(list),
      error: () => this.listError.set('the console could not list the documentation')
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

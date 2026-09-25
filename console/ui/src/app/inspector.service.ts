import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { LogLine, LogPage, ObjectDetail, ObjectRef, ObjectSummary } from './state';

/** Matches the console's two-second cache; faster would only re-read it. */
const POLL_MILLIS = 2000;
const PARAM = 'inspect';

/**
 * Which object the inspector shows, and what it currently says about it.
 *
 * The selection lives in the URL, so a link can point at one pod. It is read
 * and written with history.replaceState rather than a router, because the app
 * has none: pages are switched with a signal.
 *
 * A 404 means the object is gone - usually a pod replaced by a rollout between
 * two polls. That is a state to show, with a way back to its owner, not an
 * error to retry forever.
 */
@Injectable({ providedIn: 'root' })
export class InspectorService {
  private readonly http = inject(HttpClient);

  readonly selected = signal<ObjectRef | null>(InspectorService.fromUrl());
  readonly detail = signal<ObjectDetail | null>(null);
  readonly gone = signal(false);
  readonly recentJobs = signal<ObjectSummary[]>([]);

  /** Set by the inspector while a pod's Logs tab is on screen; only then are logs polled. */
  readonly logsOpen = signal(false);
  readonly logFilter = signal<'all' | 'warn' | 'events'>('all');
  readonly logPage = signal<LogPage | null>(null);
  readonly logLines = signal<LogLine[]>([]);
  private cursor: string | null = null;

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  select(ref: ObjectRef | null): void {
    this.selected.set(ref);
    this.detail.set(null);
    this.gone.set(false);
    this.resetLogs();
    const url = new URL(window.location.href);
    if (ref) {
      url.searchParams.set(PARAM, `${ref.kind}/${ref.name}`);
    } else {
      url.searchParams.delete(PARAM);
    }
    history.replaceState(history.state, '', url);
    this.poll();
  }

  setLogFilter(filter: 'all' | 'warn' | 'events'): void {
    this.logFilter.set(filter);
    this.resetLogs();
    this.pollLogs();
  }

  /** Called when the Logs tab opens, so the reader does not wait a whole poll for the first lines. */
  openLogs(): void {
    this.resetLogs();
    this.pollLogs();
  }

  private resetLogs(): void {
    this.cursor = null;
    this.logLines.set([]);
    this.logPage.set(null);
  }

  private pollLogs(): void {
    const ref = this.selected();
    if (!ref || ref.kind !== 'pod' || !this.logsOpen()) {
      return;
    }
    const params: Record<string, string> = { filter: this.logFilter() };
    if (this.cursor) {
      params['since'] = this.cursor;
    }
    this.http.get<LogPage>(`/api/pods/${encodeURIComponent(ref.name)}/logs`, { params }).subscribe({
      next: (page) => {
        if (this.selected() !== ref) {
          return;
        }
        this.logPage.set(page);
        if (page.available) {
          this.cursor = page.latest;
          // The tab keeps at most 500 lines, newest last, like the server.
          this.logLines.update((was) => [...was, ...page.lines].slice(-500));
        }
      },
      error: () => {}
    });
  }

  private poll(): void {
    this.pollLogs();
    const ref = this.selected();
    if (!ref) {
      this.http.get<ObjectSummary[]>('/api/objects', { params: { kind: 'job' } }).subscribe({
        next: (jobs) => this.recentJobs.set(jobs),
        error: () => {}
      });
      return;
    }
    const path = `/api/objects/${encodeURIComponent(ref.kind)}/${encodeURIComponent(ref.name)}`;
    this.http.get<ObjectDetail>(path).subscribe({
      next: (detail) => {
        // A response for an object no longer selected must not overwrite the new one.
        if (this.selected() === ref) {
          this.detail.set(detail);
          this.gone.set(false);
        }
      },
      error: (e: HttpErrorResponse) => {
        if (this.selected() === ref && e.status === 404) {
          this.gone.set(true);
        }
        // Anything else: keep the last good reading on screen, as ClusterService does.
      }
    });
  }

  private static fromUrl(): ObjectRef | null {
    const raw = new URL(window.location.href).searchParams.get(PARAM);
    const slash = raw?.indexOf('/') ?? -1;
    return raw && slash > 0 ? { kind: raw.slice(0, slash), name: raw.slice(slash + 1) } : null;
  }
}

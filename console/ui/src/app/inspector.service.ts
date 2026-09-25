import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ObjectDetail, ObjectRef, ObjectSummary } from './state';

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

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  select(ref: ObjectRef | null): void {
    this.selected.set(ref);
    this.detail.set(null);
    this.gone.set(false);
    const url = new URL(window.location.href);
    if (ref) {
      url.searchParams.set(PARAM, `${ref.kind}/${ref.name}`);
    } else {
      url.searchParams.delete(PARAM);
    }
    history.replaceState(history.state, '', url);
    this.poll();
  }

  private poll(): void {
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

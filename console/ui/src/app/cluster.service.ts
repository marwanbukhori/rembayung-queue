import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ClusterState } from './state';

/** Matches the console's own two-second cache; polling faster would only re-read it. */
const POLL_MILLIS = 2000;

/**
 * The constraints: the namespace CPU budget, what is spending it, the
 * autoscalers, and the connection pool.
 *
 * A second loop beside StateService rather than one merged poll, because the
 * two answer different questions from different places — the drop's numbers
 * come from two services this project wrote, these come from the Kubernetes
 * API. Merging them would tie the panel that has to keep working when the API
 * server is slow to the API server being slow.
 *
 * A failed request leaves the last good reading on screen. Numbers that stop
 * updating for two seconds are less alarming, and less misleading, than numbers
 * that vanish.
 */
@Injectable({ providedIn: 'root' })
export class ClusterService {
  private readonly http = inject(HttpClient);

  readonly cluster = signal<ClusterState | null>(null);

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  private poll(): void {
    this.http.get<ClusterState>('/api/cluster').subscribe({
      next: (cluster) => this.cluster.set(cluster),
      // The endpoint answers 200 with a reason inside even when the cluster is
      // unreadable, so reaching here means the console itself did not answer.
      // StateService already says so in the header; saying it twice would only
      // take the last good numbers off the screen.
      error: () => {}
    });
  }
}

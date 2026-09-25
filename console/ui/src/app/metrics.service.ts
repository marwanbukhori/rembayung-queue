import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ChartData, ChartKey } from './state';

/** History is cached ten seconds server-side and live readings two, so five is plenty. */
const POLL_MILLIS = 5000;
export const CHARTS: readonly ChartKey[] = ['requests', 'latency', 'pool', 'replicas'];

/**
 * The four charts, polled together. A failed request keeps the last good
 * chart on screen, as the other services on this page do.
 */
@Injectable({ providedIn: 'root' })
export class MetricsService {
  private readonly http = inject(HttpClient);

  readonly charts = signal<Partial<Record<ChartKey, ChartData>>>({});

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  private poll(): void {
    for (const name of CHARTS) {
      this.http.get<ChartData>(`/api/metrics/${name}`, { params: { minutes: 15 } }).subscribe({
        next: (chart) => this.charts.update((was) => ({ ...was, [name]: chart })),
        error: () => {}
      });
    }
  }
}

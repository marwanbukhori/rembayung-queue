import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ObservabilityStatus } from './state';

/**
 * Slower than the other loops on purpose. This endpoint makes an outbound call
 * to Splunk's collector, the console caches the answer for fifteen seconds, and
 * nothing it reports changes between one second and the next.
 */
const POLL_MILLIS = 15000;

/**
 * Whether Splunk and Dynatrace are actually receiving anything.
 *
 * This exists because "the search is empty" is ambiguous, and the ambiguity is
 * expensive: it reads identically whether the pipeline is broken or simply
 * quiet. A panel that says the collector answered and names the JVMs feeding it
 * resolves that without opening either vendor.
 *
 * A failed request leaves the last reading in place, like the other loops here.
 */
@Injectable({ providedIn: 'root' })
export class ObservabilityService {
  private readonly http = inject(HttpClient);

  readonly status = signal<ObservabilityStatus | null>(null);

  /** True while a deliberate re-probe is in flight, so the button can say so. */
  readonly probing = signal(false);

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  private poll(): void {
    this.http.get<ObservabilityStatus>('/api/observability').subscribe({
      // The endpoint answers 200 with the reason inside even when a probe
      // fails, so reaching the error branch means the console did not answer.
      next: (status) => this.status.set(status),
      error: () => {}
    });
  }

  /**
   * Probe both vendors now, bypassing the console's cache.
   *
   * Only this path passes refresh=true. Background polling stays cached, so a
   * page left open on a screen cannot turn a status panel into a steady load
   * generator against a trial Splunk tenant.
   */
  refresh(): void {
    if (this.probing()) {
      return;
    }
    this.probing.set(true);
    this.http.get<ObservabilityStatus>('/api/observability?refresh=true').subscribe({
      next: (status) => {
        this.status.set(status);
        this.probing.set(false);
      },
      // The reading stays as it was; the button just stops spinning. A probe
      // that could not be made is not evidence that the collector is down.
      error: () => this.probing.set(false)
    });
  }
}

import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ConsoleView } from './state';

/** How often the page asks the console for a fresh aggregate. */
const POLL_MILLIS = 2000;

/**
 * One poll loop for the whole page.
 *
 * The service caches its aggregate for a second, so several components reading
 * this signal cost the cluster nothing extra — but a component starting its own
 * interval would still multiply requests through the console's own thread pool,
 * so there is deliberately one loop here and no per-component polling.
 *
 * A failed request leaves the last good state on screen rather than clearing
 * it. Numbers that stop updating for two seconds are less alarming, and less
 * misleading, than numbers that vanish.
 */
@Injectable({ providedIn: 'root' })
export class StateService {
  private readonly http = inject(HttpClient);

  readonly view = signal<ConsoleView | null>(null);
  /** Set when the console itself cannot be reached, as distinct from its dependencies. */
  readonly transportError = signal<string | null>(null);

  private dropId: string | null = null;
  private slotId: number | null = null;

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /** Task 6 calls this when a visitor starts a sandbox of their own. */
  watch(dropId: string | null, slotId: number | null): void {
    this.dropId = dropId;
    this.slotId = slotId;
    this.poll();
  }

  private poll(): void {
    const params: Record<string, string> = {};
    if (this.dropId) {
      params['drop'] = this.dropId;
    }
    if (this.slotId !== null) {
      params['slot'] = String(this.slotId);
    }
    this.http.get<ConsoleView>('/api/state', { params }).subscribe({
      next: (view) => {
        this.view.set(view);
        this.transportError.set(null);
      },
      error: (err) => this.transportError.set(describe(err))
    });
  }
}

function describe(err: unknown): string {
  const status = (err as { status?: number })?.status;
  return status
    ? `the console answered ${status}`
    : 'the console is not answering';
}

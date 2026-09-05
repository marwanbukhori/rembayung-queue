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
  /**
   * When the last successful read landed, as the browser's own clock.
   *
   * The cluster panel shows it so a viewer can tell live numbers from a frozen
   * screenshot. It moves only on a read that actually answered — a failed poll
   * leaves the last good numbers on screen, and it would be a lie for the
   * timestamp beside them to keep ticking.
   */
  readonly updatedAt = signal<Date | null>(null);

  private dropId: string | null = null;

  constructor() {
    this.poll();
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /**
   * Point the loop at a session — a visitor's own sandbox, or null for the
   * shared one.
   *
   * There is no slot to pass. The gate's drop state names the slot the session
   * sells and the console reads it from there, so the page cannot ask for one
   * session's queue beside another session's seats.
   */
  watch(dropId: string | null): void {
    this.dropId = dropId;
    this.poll();
  }

  private poll(): void {
    const params: Record<string, string> = {};
    if (this.dropId) {
      params['drop'] = this.dropId;
    }
    this.http.get<ConsoleView>('/api/state', { params }).subscribe({
      next: (view) => {
        this.view.set(view);
        this.updatedAt.set(new Date());
        this.transportError.set(null);
      },
      error: (err) => this.transportError.set(describe(err))
    });
  }
}

function describe(err: unknown): string {
  const status = (err as { status?: number })?.status;
  if (status === 401) {
    return 'this console needs a key: open the link you were sent, with ?key= on the end';
  }
  return status
    ? `the console answered ${status}`
    : 'the console is not answering';
}

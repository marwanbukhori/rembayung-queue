import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

/** What POST /api/drops returns: a slot, a drop bound to it, and its rate. */
export interface Sandbox {
  dropId: string;
  slotId: number;
  admitRate: number;
}

/**
 * The visitor's own drop, held for as long as the page is open.
 *
 * It lives in a service rather than in the component because the surfaces are
 * swapped out when you switch tabs: a sandbox kept in the component would be
 * forgotten the moment someone looked at the pod list, and they would come back
 * to a "Start a drop" button and no way to reach the drop they already had.
 *
 * The drop id is the whole session — there is nothing else to keep, no account
 * and no ownership record, and both halves expire on their own: the drop with
 * its Redis key after thirty idle minutes, the slot with the sweeper.
 */
@Injectable({ providedIn: 'root' })
export class SandboxService {
  private readonly http = inject(HttpClient);

  readonly sandbox = signal<Sandbox | null>(null);
  readonly starting = signal(false);
  readonly failure = signal<string | null>(null);

  start(onStarted: (sandbox: Sandbox) => void): void {
    this.starting.set(true);
    this.failure.set(null);
    this.http.post<Sandbox>('/api/drops', {}).subscribe({
      next: (sandbox) => {
        this.sandbox.set(sandbox);
        this.starting.set(false);
        onStarted(sandbox);
      },
      error: (err) => {
        this.starting.set(false);
        this.failure.set(describe(err));
      }
    });
  }
}

function describe(err: unknown): string {
  const status = (err as { status?: number })?.status;
  if (status === 401) {
    return 'this console needs a key: open the link you were sent, with ?key= on the end';
  }
  // Spring's error body calls it "message"; a ProblemDetail calls it "detail".
  const body = (err as { error?: { message?: string; detail?: string } })?.error;
  return body?.message || body?.detail || 'the console could not start a sandbox just now';
}

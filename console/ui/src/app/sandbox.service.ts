import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

/**
 * What POST /api/drops returns: a slot, a session bound to it, and its rate.
 *
 * The wire calls it a drop and so does the code; the page calls it a simulation,
 * because that is the word a first-time reader has.
 */
export interface Sandbox {
  dropId: string;
  slotId: number;
  admitRate: number;
}

/**
 * The visitor's own simulation, held for as long as the page is open.
 *
 * It lives in a service rather than in the component because the surfaces are
 * swapped out when you switch sections: a sandbox kept in the component would be
 * forgotten the moment someone looked at the cluster list, and they would come
 * back to a "Start a simulation" button and no way to reach the one they already
 * had.
 *
 * The drop id is the whole session — there is nothing else to keep, no account
 * and no ownership record, and both halves expire on their own: the session with
 * its Redis key after thirty idle minutes, the slot with the sweeper.
 */
@Injectable({ providedIn: 'root' })
export class SandboxService {
  private readonly http = inject(HttpClient);

  readonly sandbox = signal<Sandbox | null>(null);
  readonly starting = signal(false);
  readonly failure = signal<string | null>(null);

  start(admitRate: number, onStarted: (sandbox: Sandbox) => void): void {
    this.starting.set(true);
    this.failure.set(null);
    // The rate is chosen before the sitting exists, so it is sent with the
    // request that opens it rather than applied afterwards - otherwise the first
    // seconds of every run happen at a rate nobody picked.
    this.http.post<Sandbox>('/api/drops', { admitRate }).subscribe({
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
  return body?.message || body?.detail || 'the console could not start a simulation just now';
}

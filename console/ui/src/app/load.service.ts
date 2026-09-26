import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { LoadRun } from './state';

/** Fast enough to catch a Job going Pending while the visitor is still looking at the button. */
const POLL_MILLIS = 2000;

/** Wave 2 of a two-wave rush starts this long after wave 1 (LoadOps.WAVE_GAP_SECONDS). */
export const WAVE_GAP_S = 180;
/** A wave runs about this long: 90 s of patience, then the bookings (LoadOps.WAVE_SECONDS). */
export const WAVE_S = 120;

/**
 * Sending load at a drop, and watching what the cluster does about it.
 *
 * The run's state is polled rather than returned once, because the interesting
 * outcomes arrive after the request has answered: a Job accepted by the API
 * server can still sit Pending for as long as the namespace is full, and that
 * wait — with its reason on screen — is the thing worth watching.
 */
@Injectable({ providedIn: 'root' })
export class LoadService {
  private readonly http = inject(HttpClient);

  readonly run = signal<LoadRun | null>(null);
  readonly starting = signal(false);
  /** Why the console refused to start a run: distinct from why the cluster refused to schedule it. */
  readonly failure = signal<string | null>(null);
  readonly admitRate = signal<number | null>(null);
  readonly rateFailure = signal<string | null>(null);
  /**
   * How many customers the next run sends.
   *
   * Held here rather than in the component because the surfaces are swapped out
   * when you switch tabs, and a choice forgotten on the way to the pod list is
   * a choice the visitor has to make twice.
   */
  /** Matches LoadOps.DEFAULT_VUS. A run that outlasts the script's poll window
   *  reports customers giving up, which says nothing about the system. */
  readonly chosenVus = signal(60);
  /** One wave, or the same wave twice three minutes apart. */
  readonly chosenWaves = signal(1);
  /** When the current run began, in epoch seconds, worked out from its elapsed time when last read. */
  private readonly startedAt = signal<number | null>(null);

  /**
   * Where the two waves of a two-wave run began, for the charts to mark. Shown
   * while the run is live and for fifteen minutes after, from the run itself -
   * so a reload mid-run draws them again.
   */
  readonly waveMarks = computed<{ label: string; t: number }[]>(() => {
    const run = this.run();
    const start = this.startedAt();
    if (!run || run.waves !== 2 || run.phase === 'NONE' || start === null) {
      return [];
    }
    const ended = start + WAVE_GAP_S + WAVE_S;
    if (Date.now() / 1000 - ended > 15 * 60) {
      return [];
    }
    return [{ label: 'W1', t: start }, { label: 'W2', t: start + WAVE_GAP_S }];
  });

  private dropId: string | null = null;

  constructor() {
    const timer = setInterval(() => this.poll(), POLL_MILLIS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /** Follow a drop, or stop following when it goes off screen. */
  watch(dropId: string | null, admitRate: number | null = null): void {
    if (dropId !== this.dropId) {
      this.run.set(null);
      this.failure.set(null);
      this.rateFailure.set(null);
      this.admitRate.set(admitRate);
    }
    this.dropId = dropId;
    this.poll();
  }

  send(vus: number, waves = 1): void {
    const dropId = this.dropId;
    if (!dropId) {
      return;
    }
    this.starting.set(true);
    this.failure.set(null);
    this.http.post<LoadRun>(`/api/drops/${dropId}/load`, { vus, waves }).subscribe({
      next: (run) => {
        this.took(run);
        this.starting.set(false);
      },
      error: (err) => {
        this.starting.set(false);
        this.failure.set(describe(err, 'the console could not start a load run just now'));
      }
    });
  }

  setRate(admitRate: number): void {
    const dropId = this.dropId;
    if (!dropId) {
      return;
    }
    this.rateFailure.set(null);
    this.http.post<{ admitRate: number }>(`/api/drops/${dropId}/rate`, { admitRate }).subscribe({
      next: (rate) => this.admitRate.set(rate.admitRate),
      error: (err) => this.rateFailure.set(describe(err, 'the rate could not be changed just now'))
    });
  }

  private poll(): void {
    if (!this.dropId) {
      return;
    }
    this.http.get<LoadRun>(`/api/drops/${this.dropId}/load`).subscribe({
      next: (run) => this.took(run),
      error: () => {}
    });
  }

  private took(run: LoadRun): void {
    this.run.set(run);
    this.startedAt.set(run.phase === 'NONE' ? null : Date.now() / 1000 - run.secondsElapsed);
  }
}

function describe(err: unknown, fallback: string): string {
  const status = (err as { status?: number })?.status;
  if (status === 401) {
    return 'this console needs a key: open the link you were sent, with ?key= on the end';
  }
  // Spring's error body calls it "message"; a ProblemDetail calls it "detail".
  const body = (err as { error?: { message?: string; detail?: string } })?.error;
  return body?.message || body?.detail || fallback;
}

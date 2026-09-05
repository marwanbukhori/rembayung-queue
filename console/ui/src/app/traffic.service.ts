import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { StateService } from './state.service';

export type EventKind = 'join' | 'admit' | 'seat' | 'open' | 'idle' | 'oversold';

export interface TrafficEvent {
  /** Monotonic, so the list can track by it without colliding on the clock. */
  seq: number;
  at: Date;
  kind: EventKind;
  text: string;
  /** How many of the thing happened, for the ones that come in bursts. */
  count: number;
}

/**
 * What changed since the last read, as a running log.
 *
 * The console already polls one snapshot of the whole system every two seconds.
 * A snapshot says what is true; it does not say what happened, and what happened
 * is the interesting part of a rush - forty people arrived, one got through, a
 * seat went. So this keeps the previous snapshot and turns each difference into
 * a line.
 *
 * Derived rather than collected. These are not the services' own log lines: they
 * are differences between two readings of the numbers those services publish,
 * which is why a line says "8 joined" rather than naming the eight. It costs no
 * extra requests, no new permissions, and cannot disagree with the counters
 * beside it, because it is computed from exactly the same reading.
 */
@Injectable({ providedIn: 'root' })
export class TrafficService {
  private static readonly KEEP = 60;

  private readonly state = inject(StateService);

  private readonly events = signal<TrafficEvent[]>([]);
  private seq = 0;

  /** The last snapshot a line was derived from, per drop. */
  private previous: { dropId: string | null; issued: number; admitted: number; seats: number } | null = null;

  /** Newest first, because a log you watch is read from the top. */
  readonly feed = this.events.asReadonly();

  /** True while anything is actually moving, which drives the animation. */
  readonly flowing = signal(false);
  private quietSince = Date.now();

  readonly idleSeconds = computed(() => this.events().length);

  constructor() {
    effect(() => {
      const view = this.state.view();
      const drop = view?.drop;
      if (!drop) {
        return;
      }

      // A different session is a different story; do not diff across them.
      if (!this.previous || this.previous.dropId !== drop.dropId) {
        this.previous = {
          dropId: drop.dropId,
          issued: drop.ticketsIssued,
          admitted: drop.admitted,
          seats: drop.seatsTaken
        };
        if (drop.available) {
          this.push('open', 'watching ' + (drop.dropId ?? 'the public session'), 0);
        }
        return;
      }

      const joined = drop.ticketsIssued - this.previous.issued;
      const admitted = drop.admitted - this.previous.admitted;
      const seats = drop.seatsTaken - this.previous.seats;

      if (joined > 0) {
        this.push('join', `${joined} joined the queue`, joined);
      }
      if (admitted > 0) {
        this.push('admit', `${admitted} admitted, ${drop.waiting} still waiting`, admitted);
      }
      if (seats > 0) {
        this.push('seat', `${seats} ${seats === 1 ? 'seat' : 'seats'} taken, ${drop.remaining} left`, seats);
      }
      // Never expected, which is exactly why it is logged loudly rather than
      // left to a counter nobody is looking at.
      if (drop.oversold > 0) {
        this.push('oversold', `OVERSOLD by ${drop.oversold}`, drop.oversold);
      }

      const moved = joined > 0 || admitted > 0 || seats > 0;
      if (moved) {
        this.quietSince = Date.now();
        this.flowing.set(true);
      } else if (Date.now() - this.quietSince > 6000) {
        // Six seconds of nothing, not one poll of nothing: a run whose next
        // admission is a second away should not make the page look stopped.
        this.flowing.set(false);
      }

      this.previous = {
        dropId: drop.dropId,
        issued: drop.ticketsIssued,
        admitted: drop.admitted,
        seats: drop.seatsTaken
      };
    });
  }

  private push(kind: EventKind, text: string, count: number): void {
    const event: TrafficEvent = { seq: ++this.seq, at: new Date(), kind, text, count };
    this.events.update((list) => [event, ...list].slice(0, TrafficService.KEEP));
  }

  /** Starting again should not read as a continuation of the last run. */
  clear(): void {
    this.events.set([]);
    this.previous = null;
    this.flowing.set(false);
  }
}

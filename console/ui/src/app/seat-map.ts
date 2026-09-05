import { Component, computed, effect, inject, signal } from '@angular/core';
import { StateService } from './state.service';

/**
 * The sitting, drawn as seats.
 *
 * A bar filling to 48% says less than a room filling up, and the number here is
 * fixed and small enough to draw honestly: 250 seats, one square each, so the
 * picture is the data rather than a summary of it. Seats fill in the order the
 * database committed them, and a seat that has just gone flashes once - a rush
 * you can watch land, which is the whole point of the page.
 *
 * Party of two, which is the thing the numbers beside this never said out loud.
 * 60 bookings and 120 seats taken look contradictory until somebody tells you
 * each booking is for two people, so the caption says it.
 */
@Component({
  selector: 'rb-seat-map',
  template: `
    <div class="wrap">
      <div class="legend">
        <span class="key"><i class="chip taken"></i>taken</span>
        <span class="key"><i class="chip fresh"></i>just booked</span>
        <span class="key"><i class="chip"></i>free</span>
        <span class="caption">{{ caption() }}</span>
      </div>
      <div class="seats" role="img" [attr.aria-label]="caption()">
        @for (seat of seats(); track seat.i) {
          <i class="seat" [class.taken]="seat.taken" [class.fresh]="seat.fresh"></i>
        }
      </div>
    </div>
  `,
  styles: `
    .wrap { display: flex; flex-direction: column; gap: 10px; }

    .legend {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 16px;
      font-size: 12px;
      color: var(--muted);
    }
    .key { display: inline-flex; align-items: center; gap: 6px; }
    .chip {
      width: 10px;
      height: 10px;
      border-radius: 2px;
      background: var(--track);
      display: inline-block;
    }
    .chip.taken { background: var(--ink); }
    .chip.fresh { background: var(--dhl-red); }
    .caption { margin-left: auto; color: var(--ink-soft); }

    /*
      auto-fill rather than a fixed 25 columns: the row length follows the width
      it is given, so the block reflows instead of overflowing on a narrow page.
    */
    .seats {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(12px, 1fr));
      gap: 3px;
    }
    .seat {
      aspect-ratio: 1;
      border-radius: 2px;
      background: var(--track);
      transition: background-color 220ms var(--ease);
    }
    .seat.taken { background: var(--ink); }
    /* One flash as it lands, then it settles to taken like the rest. */
    .seat.fresh { background: var(--dhl-red); animation: land 900ms var(--ease); }
    @keyframes land {
      0% { transform: scale(.4); opacity: .3; }
      45% { transform: scale(1.25); opacity: 1; }
      100% { transform: scale(1); }
    }

    @media (prefers-reduced-motion: reduce) {
      .seat.fresh { animation: none; }
      .seat { transition: none; }
    }
  `
})
export class SeatMap {
  private readonly state = inject(StateService);

  private readonly drop = computed(() => this.state.view()?.drop ?? null);

  /** Seats taken at the previous read, so the difference can be flashed. */
  private readonly previousTaken = signal(0);
  private lastDropId: string | null = null;

  constructor() {
    effect(() => {
      const drop = this.drop();
      if (!drop) {
        return;
      }
      // A new sitting starts empty; do not flash 120 seats that were another
      // run's.
      if (drop.dropId !== this.lastDropId) {
        this.lastDropId = drop.dropId;
        this.previousTaken.set(drop.seatsTaken);
        return;
      }
      const seen = this.previousTaken();
      if (drop.seatsTaken !== seen) {
        // Held for one read so the flash has time to play, then folded in by
        // the next poll.
        setTimeout(() => this.previousTaken.set(drop.seatsTaken), 900);
      }
    });
  }

  protected readonly seats = computed(() => {
    const drop = this.drop();
    const capacity = drop?.available ? drop.capacity : 250;
    const taken = drop?.available ? drop.seatsTaken : 0;
    const before = Math.min(this.previousTaken(), taken);
    return Array.from({ length: capacity }, (_, i) => ({
      i,
      taken: i < taken,
      fresh: i >= before && i < taken
    }));
  });

  protected readonly caption = computed(() => {
    const drop = this.drop();
    if (!drop?.available) {
      return 'No sitting open';
    }
    // Party of two is fixed by the load script, so bookings are always half the
    // seats. Saying so is the whole reason this caption exists.
    const bookings = Math.floor(drop.seatsTaken / 2);
    return `${bookings} bookings, 2 seats each — ${drop.seatsTaken} of ${drop.capacity} taken`;
  });
}

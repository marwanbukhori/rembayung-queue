import { Component, computed, inject, input } from '@angular/core';
import { StateService } from './state.service';

/**
 * The live seat and queue numbers, read from the services that own the data.
 *
 * Nothing is computed here beyond a percentage for a bar. Seats, remaining and
 * oversold arrive already calculated by SlotStateProvider, which is also what
 * the Prometheus gauges and the SlotOversold alert read — so this page cannot
 * show a different answer from the one that would page someone.
 */
@Component({
  selector: 'rb-canonical-drop',
  template: `
    <section class="stack-16">
      <div class="section-head">
        <div style="min-width: 0;">
          <h2>{{ heading() }}</h2>
          <p class="sub">{{ subheading() }}</p>
        </div>
        <span class="meta">refreshes every 2s</span>
      </div>

      <div class="cards">
        <div class="card seats">
          <div class="row-baseline">
            <div class="label">Seats taken</div>
            <div class="meta">capacity {{ capacityLabel() }}</div>
          </div>

          @if (drop(); as d) {
            @if (d.available) {
              <div class="row-baseline figures">
                <div class="figure">{{ d.seatsTaken }}</div>
                <div class="of">/ {{ d.capacity }}</div>
              </div>
              <div class="bar"><span [style.width.%]="seatsPct()"></span></div>
              <div class="footnote">
                <span>{{ d.admitted }} admitted</span>
                <span>{{ d.remaining }} remaining</span>
              </div>
            } @else {
              <div class="figures"><div class="unknown">unknown</div></div>
              <div class="reason">{{ d.detail }}</div>
            }
          } @else {
            <div class="figures"><div class="unknown">…</div></div>
          }
        </div>

        <div class="card queue">
          <div class="label">Queue depth</div>
          @if (drop(); as d) {
            @if (d.available) {
              <div class="row-baseline figures">
                <div class="figure">{{ d.waiting }}</div>
                <div class="of">waiting</div>
              </div>
              <div class="bar"><span class="info" [style.width.%]="queuePct()"></span></div>
              <div class="footnote"><span>{{ d.ticketsIssued }} tickets issued</span></div>
            } @else {
              <div class="figures"><div class="unknown">unknown</div></div>
              <div class="reason">{{ d.detail }}</div>
            }
          } @else {
            <div class="figures"><div class="unknown">…</div></div>
          }
        </div>

        <div class="card oversold">
          <div class="label">Oversold</div>
          @if (drop()?.available) {
            <div class="figure-claim">{{ drop()!.oversold }}</div>
          } @else {
            <div class="figure-claim">—</div>
          }
          <div class="note">{{ oversoldNote }}</div>
        </div>
      </div>
    </section>
  `,
  styles: `
    .cards { display: flex; flex-wrap: wrap; gap: 16px; }
    .seats { flex: 2 1 340px; }
    .queue { flex: 1 1 250px; }
    .oversold {
      flex: 1 1 250px;
      min-width: 0;
      background: #1A1A1A;
      border-radius: 4px;
      padding: 24px;
      color: #FFFFFF;
    }
    .label { font-size: 14px; font-weight: 700; }
    .row-baseline {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      justify-content: space-between;
      align-items: baseline;
    }
    .figures { margin: 12px 0 16px; justify-content: flex-start; gap: 8px; }
    .of { font-family: var(--mono); font-size: 17px; color: var(--muted); }
    .footnote {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 8px;
      margin-top: 8px;
      font-family: var(--mono);
      font-size: 12px;
      color: var(--muted);
      letter-spacing: .04em;
    }
    .note { font-size: 14px; color: var(--on-dark-soft); text-wrap: pretty; }
  `
})
export class CanonicalDrop {
  private readonly state = inject(StateService);

  /** The visitor's own session names itself; everywhere else this is the shared one. */
  readonly heading = input('The public simulation');
  readonly oversoldNote = 'Across every simulation this cluster has run.';

  readonly drop = computed(() => this.state.view()?.drop ?? null);

  /**
   * The slot is named rather than assumed. It used to read "Slot 1" whatever
   * was on screen, which was right for the shared session and wrong for every
   * sandbox — the gate now says which slot a session sells, so the page can say
   * it too.
   */
  readonly subheading = computed(() => {
    const d = this.drop();
    return d && d.available
      ? `One sitting of ${d.capacity} seats, read from the services that own the data`
      : 'Read from the services that own the data';
  });

  /** Capacity is unknown, not zero, when booking-service could not be read. */
  readonly capacityLabel = computed(() => {
    const d = this.drop();
    return d && d.available ? String(d.capacity) : '—';
  });

  readonly seatsPct = computed(() => {
    const d = this.drop();
    if (!d || !d.available || d.capacity === 0) {
      return 0;
    }
    return Math.min(100, Math.round((d.seatsTaken / d.capacity) * 1000) / 10);
  });

  /** 300 is the design's full-scale mark for a queue, not a limit on it. */
  readonly queuePct = computed(() => {
    const d = this.drop();
    return d && d.available ? Math.min(100, Math.round((d.waiting / 300) * 100)) : 0;
  });
}

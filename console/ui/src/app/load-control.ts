import { Component, computed, inject, input } from '@angular/core';
import { LoadService } from './load.service';

/** The Phase 3 ladder, measured against this deployment rather than chosen. */
const LADDER = [
  { offered: 200, arrived: 200, shed: 0 },
  { offered: 1000, arrived: 662, shed: 338 },
  { offered: 3000, arrived: 818, shed: 2182 }
];

/**
 * The two controls that let a visitor cause the numbers rather than read them:
 * how fast the gate admits, and how many customers arrive.
 *
 * <h2>Both offer the setting that breaks something</h2>
 * 200 admissions a second exhausts the connection pool and produces 503 with
 * Retry-After while oversold stays at zero. 1000 virtual users watch a third of
 * their traffic never arrive, because the sandbox router sheds it. Neither is
 * hidden behind a warning, because both are the finding: a control that only
 * offered comfortable values would have nothing to demonstrate, and the second
 * one is labelled as edge shedding so it does not read as the application
 * failing.
 */
@Component({
  selector: 'rb-load-control',
  template: `
    <section class="pair">
      <div class="card pad">
        <div>
          <div class="card-title">Admission rate</div>
          <p class="sub">How fast the gate lets ticket holders reach the database.</p>
        </div>
        <div class="tabs">
          @for (rate of rates; track rate.value) {
            <button class="tab" [class.on]="rate.value === admitRate()"
                    (click)="setRate(rate.value)">{{ rate.label }}</button>
          }
        </div>
        <p class="note">{{ rateNote() }}</p>
        @if (rateFailure(); as reason) {
          <p class="reason" style="margin-top: 0;">{{ reason }}</p>
        }
      </div>

      <div class="card pad">
        <div>
          <div class="card-title">Send customers</div>
          <p class="sub">A k6 Job inside the cluster, so you do not need anything installed.</p>
        </div>
        <div class="tabs">
          @for (option of vuOptions; track option) {
            <button class="tab" [class.on]="option === vus()"
                    (click)="vus.set(option)">{{ option }}</button>
          }
        </div>
        <div class="scroller">
          <table style="min-width: 260px;">
            <thead>
              <tr>
                <th>Offered</th>
                <th class="right">Arrives</th>
                <th class="right">Shed at the edge</th>
              </tr>
            </thead>
            <tbody>
              @for (row of ladder; track row.offered) {
                <tr [class.chosen]="row.offered === vus()">
                  <td class="mono">{{ row.offered }}</td>
                  <td class="mono right muted">{{ row.arrived }}</td>
                  <td class="mono right muted">{{ row.shed }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="note">{{ vuNote() }}</p>
        <div class="send">
          <button class="btn btn-primary" [disabled]="sendDisabled()" (click)="send()">
            {{ sendLabel() }}
          </button>
          <span class="hint">{{ hint() }}</span>
        </div>
      </div>
    </section>
  `,
  styles: `
    .pair {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(340px, 100%), 1fr));
      align-items: start;
    }
    .pad { padding: 24px; display: flex; flex-direction: column; gap: 16px; }
    .card-title { font-size: 19px; font-weight: 700; }
    .tabs { display: flex; gap: 24px; border-bottom: 1px solid var(--line); overflow-x: auto; }
    .tab {
      background: none;
      border: 0;
      border-bottom: 2px solid transparent;
      padding: 8px 0;
      margin-bottom: -1px;
      font: inherit;
      font-size: 14px;
      color: var(--ink);
      opacity: .55;
      cursor: pointer;
      white-space: nowrap;
    }
    .tab.on { border-bottom-color: var(--ink); opacity: 1; font-weight: 700; }
    .note { margin: 0; font-size: 15px; color: var(--ink-soft); text-wrap: pretty; }
    .send { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
    .hint { font-size: 14px; color: var(--muted); flex: 1 1 160px; min-width: 0; text-wrap: pretty; overflow-wrap: anywhere; }
    tr.chosen { background: var(--highlight); }
  `
})
export class LoadControl {
  private readonly loads = inject(LoadService);

  /** The rate the drop was created with, until the visitor changes it. */
  readonly startingRate = input<number>(8);

  readonly ladder = LADDER;
  readonly vuOptions = LADDER.map((row) => row.offered);

  readonly vus = this.loads.chosenVus;
  readonly rateFailure = this.loads.rateFailure;

  readonly rates = [
    {
      value: 1,
      label: '1 per second',
      note: 'Slow enough to watch one person at a time reach the database. The queue barely moves.'
    },
    {
      value: 8,
      label: '8 per second',
      note: 'The production setting, and a measured one: twenty connections divided by a 2.7 second '
        + 'round trip to an Oracle instance a region away. The pool sits around nine of twenty and '
        + 'the queue drains steadily.'
    },
    {
      value: 200,
      label: '200 per second',
      note: 'More than the database can absorb. The pool exhausts, booking-service starts refusing '
        + 'with 503 and Retry-After, and oversold stays at zero anyway. That last part is the whole '
        + 'point, and it is why this option is offered rather than hidden.'
    }
  ];

  readonly admitRate = computed(() => this.loads.admitRate() ?? this.startingRate());

  readonly rateNote = computed(() =>
    this.rates.find((rate) => rate.value === this.admitRate())?.note
    ?? 'This drop was created with a rate the console does not offer as a preset.');

  readonly vuNote = computed(() => {
    const chosen = this.vus();
    if (chosen <= 200) {
      return 'Every request lands and the excess is refused by the application, which is the '
        + 'behaviour worth showing.';
    }
    return 'Roughly a third of this never arrives: it is shed at the sandbox router before it '
      + 'reaches the gate — an edge limit, not an application failure. Throughput plateaus around '
      + 'six to eight hundred whatever you offer, and the application logs zero errors at every '
      + 'rung of that ladder.';
  });

  readonly sendDisabled = computed(() =>
    this.loads.starting() || this.phase() === 'PENDING' || this.phase() === 'RUNNING');

  readonly sendLabel = computed(() => {
    if (this.loads.starting()) {
      return 'Starting…';
    }
    switch (this.phase()) {
      case 'PENDING': return 'Pending';
      case 'RUNNING': return 'Running';
      case 'SUCCEEDED':
      case 'FAILED': return `Send ${this.vus()} again`;
      default: return `Send ${this.vus()} customers`;
    }
  });

  /**
   * The one-line status beside the button.
   *
   * A Pending run gets the scheduler's own reason here as well as in the
   * constraints panel, so someone watching the button does not have to look
   * elsewhere to find out that nothing is wrong.
   */
  readonly hint = computed(() => {
    const failure = this.loads.failure();
    if (failure) {
      return failure;
    }
    const run = this.loads.run();
    if (!run) {
      return `Runs as a Job asking the scheduler for ${cpuFor(this.vus())}m, stopped after five minutes whatever happens.`;
    }
    if (!run.available) {
      return run.detail ?? 'The cluster is not readable from here.';
    }
    switch (run.phase) {
      case 'PENDING':
        return run.message
          ? `${run.reason}: ${run.message}`
          : 'Accepted by the API server, waiting on the scheduler.';
      case 'RUNNING':
        return `${run.vus} customers, ${run.secondsElapsed}s in. It stops by itself after five minutes.`;
      case 'SUCCEEDED':
        return `The run finished. The queue numbers above are the result, not the Job's exit code.`;
      case 'FAILED':
        return run.message ? `${run.reason}: ${run.message}` : 'The run did not complete.';
      default:
        return `Runs as a Job asking the scheduler for ${cpuFor(this.vus())}m, stopped after five minutes whatever happens.`;
    }
  });

  private readonly phase = computed(() => this.loads.run()?.phase ?? 'NONE');

  setRate(value: number): void {
    this.loads.setRate(value);
  }

  send(): void {
    this.loads.send(this.vus());
  }
}

/** Mirrors LoadOps.cpuMillis, so the hint names the number the Job will ask for. */
function cpuFor(vus: number): number {
  if (vus <= 200) {
    return 200;
  }
  return vus <= 1000 ? 400 : 800;
}

import { Component, computed, inject, signal } from '@angular/core';
import { LoadService } from './load.service';
import { SandboxService } from './sandbox.service';
import { StateService } from './state.service';

/** Measured against this deployment rather than chosen. */
const CROWDS = [
  // 60 is the default and the only one that finishes inside the k6 script's
  // ninety-second poll window at one admission a second.
  { offered: 60, arrived: 60, shed: 0 },
  { offered: 200, arrived: 200, shed: 0 },
  { offered: 1000, arrived: 662, shed: 338 },
  { offered: 3000, arrived: 818, shed: 2182 }
];

/**
 * One panel: two settings and a button.
 *
 * This was three numbered steps - start a simulation, send a crowd, change the
 * rate - and it was the wrong shape twice over. It exposed an internal
 * two-phase process, seeding a slot and then creating a Job, as two things a
 * person had to do in order, when their intent is one thing: run a rush. And a
 * wizard is a pattern for long, unfamiliar, high-stakes forms; the guidance is
 * to use one for ten or more fields, and there are two here.
 *
 * Locust, which does this exact job, is one small form - users, spawn rate,
 * host - and one button that says Start swarming. Grafana Cloud k6 is a
 * configure panel and a Create and Run. Neither makes you press start and then
 * hunt for the control that actually does something.
 *
 * The admission rate stays live while a run is in flight, because changing it
 * mid-rush and watching what gives is the interesting thing this console can
 * do - not a step, a dial.
 */
@Component({
  selector: 'rb-run-panel',
  template: `
    <section class="panel">
      <div class="accent-top"></div>
      <div class="body">
        <div class="head">
          <div>
            <h2 class="title">Run a rush</h2>
            <p class="sub">
              A 9pm opening of your own — its own 250 seats, its own queue. Nothing here touches the
              public sitting or anyone else on this page, and running it again is free.
            </p>
          </div>
        </div>

        <div class="settings">
          <div class="setting">
            <div class="label">Customers arriving at once</div>
            <div class="tabs">
              @for (crowd of crowds; track crowd.offered) {
                <button class="tab" [class.on]="crowd.offered === vus()"
                        [disabled]="busy()" (click)="vus.set(crowd.offered)">
                  {{ crowd.offered }}
                </button>
              }
            </div>
            <p class="note">{{ crowdNote() }}</p>
          </div>

          <div class="setting">
            <div class="label">
              Admitted per second
              @if (busy()) { <span class="live mono">live — change it now</span> }
            </div>
            <div class="tabs">
              @for (rate of rates; track rate.value) {
                <button class="tab" [class.on]="rate.value === admitRate()"
                        (click)="chooseRate(rate.value)">{{ rate.value }}</button>
              }
            </div>
            <p class="note">{{ rateNote() }}</p>
          </div>
        </div>

        <div class="go">
          <button class="btn btn-primary" [disabled]="busy()" (click)="run()">{{ label() }}</button>
          <span class="hint">{{ hint() }}</span>
        </div>

        @if (failure(); as reason) {
          <p class="reason">{{ reason }}</p>
        }
      </div>
    </section>
  `,
  styles: `
    .body { padding: 24px; display: flex; flex-direction: column; gap: 20px; }
    .title { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: -0.01em; }
    .sub { margin: 6px 0 0; font-size: 15px; color: var(--ink-soft); max-width: 72ch; text-wrap: pretty; }

    .settings {
      display: grid;
      gap: 20px 32px;
      grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr));
    }
    .setting { min-width: 0; }
    .label { font-size: 14px; font-weight: 700; margin-bottom: 8px; }
    .live {
      margin-left: 8px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .04em;
      background: var(--chip-ok-bg);
      color: var(--chip-ok-fg);
      border-radius: 999px;
      padding: 2px 9px;
    }
    .note { margin: 8px 0 0; font-size: 13px; color: var(--muted); text-wrap: pretty; }

    .tabs { display: flex; flex-wrap: wrap; gap: 6px; }
    .tab {
      font: inherit;
      font-family: var(--mono);
      font-size: 14px;
      padding: 8px 16px;
      border: 1px solid var(--line);
      border-radius: 4px;
      background: var(--white);
      color: var(--ink-soft);
      cursor: pointer;
    }
    .tab:hover:not(:disabled) { border-color: var(--ink); }
    .tab.on { background: var(--ink); border-color: var(--ink); color: var(--white); font-weight: 700; }
    .tab:disabled { opacity: .5; cursor: default; }

    .go { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 16px; }
    .hint { font-size: 14px; color: var(--muted); text-wrap: pretty; }
  `
})
export class RunPanel {
  private readonly sandboxes = inject(SandboxService);
  private readonly loads = inject(LoadService);
  private readonly state = inject(StateService);

  protected readonly crowds = CROWDS;
  protected readonly vus = this.loads.chosenVus;

  protected readonly rates = [
    {
      value: 1,
      note: 'What this database can actually commit. Every booking for a sitting locks the same row '
        + 'and holds it across a round trip to Oracle, so they serialise at about one a second. The '
        + 'queue drains steadily and the seats fill.'
    },
    {
      value: 8,
      note: 'Eight times what the database commits, so the queue empties faster than the seats fill '
        + 'and the overflow is refused. Measured here: a run at this rate created 16 bookings and '
        + 'took 503 Service Unavailable for 184. Nothing is oversold; work is shed.'
    },
    {
      value: 200,
      note: 'Far more than the database can absorb. The pool exhausts at once, booking-service '
        + 'refuses with 503 and Retry-After, and oversold stays at zero anyway. That last part is '
        + 'the whole point, and it is why this option is offered rather than hidden.'
    }
  ];

  /** Chosen before a run exists; the gate's own value once one does. */
  private readonly picked = signal(1);
  protected readonly admitRate = computed(() => this.loads.admitRate() ?? this.picked());

  private readonly phase = computed(() => this.loads.run()?.phase ?? 'NONE');

  protected readonly busy = computed(() =>
    this.sandboxes.starting() || this.loads.starting()
    || this.phase() === 'PENDING' || this.phase() === 'RUNNING');

  protected readonly failure = computed(() => this.sandboxes.failure() ?? this.loads.failure());

  protected readonly crowdNote = computed(() => {
    const crowd = CROWDS.find((c) => c.offered === this.vus());
    if (!crowd) {
      return '';
    }
    return crowd.shed
      ? `${crowd.arrived} of ${crowd.offered} reach the cluster; the sandbox router sheds ${crowd.shed} at the edge.`
      : `All ${crowd.offered} reach the cluster. Nothing is shed before it arrives.`;
  });

  protected readonly rateNote = computed(
    () => this.rates.find((r) => r.value === this.admitRate())?.note ?? ''
  );

  protected readonly label = computed(() => {
    if (this.sandboxes.starting()) { return 'Opening…'; }
    if (this.loads.starting()) { return 'Sending…'; }
    if (this.phase() === 'PENDING') { return 'Waiting for the cluster…'; }
    if (this.phase() === 'RUNNING') { return 'Rush in flight…'; }
    return this.sandboxes.sandbox() ? 'Run another rush' : 'Start the rush';
  });

  protected readonly hint = computed(() => {
    if (this.phase() === 'RUNNING') {
      return 'Change the rate above while it runs and watch what gives.';
    }
    if (this.phase() === 'SUCCEEDED') {
      return 'Running again opens a fresh sitting, so the numbers start from zero.';
    }
    return `Opens a sitting of 250 seats and sends ${this.vus()} customers at it in the same second.`;
  });

  protected chooseRate(rate: number): void {
    this.picked.set(rate);
    // A live run has a drop to change; before one, this is only a preference and
    // is applied when the sitting is opened.
    if (this.sandboxes.sandbox()) {
      this.loads.setRate(rate);
    }
  }

  /**
   * One press: open a sitting and send the crowd at it.
   *
   * A fresh sitting every time rather than reusing the last one, so a second run
   * starts from zero seats instead of continuing a total nobody was watching
   * accumulate.
   */
  protected run(): void {
    const vus = this.vus();
    this.sandboxes.start(this.picked(), (sandbox) => {
      this.state.watch(sandbox.dropId);
      this.loads.watch(sandbox.dropId, sandbox.admitRate);
      this.loads.send(vus);
    });
  }
}

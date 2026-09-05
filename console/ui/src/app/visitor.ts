import { Component, OnDestroy, OnInit, inject, output } from '@angular/core';
import { CanonicalDrop } from './canonical-drop';
import { Constraints } from './constraints';
import { RunBanner } from './run-banner';
import { RunPanel } from './run-panel';
import { SeatMap } from './seat-map';
import { TrafficLog } from './traffic-log';
import { LoadService } from './load.service';
import { SandboxService } from './sandbox.service';
import { StateService } from './state.service';

/**
 * Your own simulation: a session of your own, on a slot of your own.
 *
 * The session id is the whole thing. There is no account and nothing stored on
 * the visitor's behalf — whoever is looking at this page is one of two people
 * who hold the key, and both halves expire by themselves: the session with its
 * Redis key after thirty idle minutes, the slot with the sweeper. Starting again
 * is therefore free, and the button never needs to clean up after the last
 * attempt.
 *
 * "Drop" is what the code and the API call this; the page does not, because
 * nobody arriving cold knows the word.
 */
@Component({
  selector: 'rb-visitor',
  imports: [CanonicalDrop, Constraints, RunBanner, RunPanel, SeatMap, TrafficLog],
  template: `
    <div class="stack">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">Your simulation</span>
      </div>

      <rb-run-panel />

      @if (sandbox()) {
        <rb-run-banner />
        <rb-canonical-drop heading="Your simulation, live" />
        <rb-seat-map />
        <rb-traffic-log />
        <!--
          Said plainly, because the panel above appears full of zeros the moment
          a simulation starts, and a page of zeros reads as broken rather than
          as waiting for you.
        -->
        <p class="reason">
          Every counter above stays at zero until a run from step 2 reaches it. A simulation with
          no traffic against it is idle, not broken. If the namespace is out of CPU the run sits
          Pending and the constraints panel says so in the scheduler's own words — that is the
          demonstration, not a fault.
        </p>
      }

      <rb-constraints />

      <!--
        The way back. Starting a simulation used to be a one-way door: the page
        filled with controls and nothing on it returned to the overview, so the
        only exit was the browser's back button on a page that has no history.
      -->
      <div class="exit">
        <button class="btn btn-secondary" (click)="home.emit()">Back to the overview</button>
        <button class="btn-tertiary" (click)="docs.emit()">Read the documentation</button>
        @if (sandbox()) {
          <span class="exit-note">
            Your simulation keeps running while you look around, and this page comes back to it.
          </span>
        }
      </div>
    </div>
  `,
  styles: `
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .body { padding: 32px 24px; display: flex; flex-direction: column; gap: 20px; }
    .start-step .card-title { font-size: 19px; font-weight: 700; }
    /* Ticked rather than removed: the sequence still has to read 1, 2, 3. */
    .step-num.ticked { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .done-tag {
      margin-left: 10px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: .04em;
      background: var(--chip-ok-bg);
      color: var(--chip-ok-fg);
      border-radius: 999px;
      padding: 3px 10px;
      vertical-align: middle;
    }
    .start-step .sub {
      margin: 6px 0 0;
      font-size: 15px;
      color: var(--ink-soft);
      max-width: 66ch;
      text-wrap: pretty;
    }
    /* Ties the button to the step in the controls that follow it. */
    .step-num {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      margin-right: 8px;
      border-radius: 999px;
      background: var(--dhl-yellow);
      font-size: 12px;
      font-weight: 700;
      vertical-align: middle;
    }

    .exit {
      border-top: 1px solid var(--line);
      padding-top: 20px;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 16px;
    }
    .exit-note { font-size: 14px; color: var(--muted); flex: 1 1 220px; min-width: 0; text-wrap: pretty; }
  `
})
/**
 * The poll loop follows this page: while the visitor's own session is on screen
 * the console reads that session, and leaving puts the loop back on the public
 * one. Without that, walking from here to the overview would show a stranger the
 * visitor's sandbox under the heading "the public simulation".
 */
export class Visitor implements OnInit, OnDestroy {
  readonly home = output<void>();
  readonly docs = output<void>();

  private readonly sandboxes = inject(SandboxService);
  private readonly state = inject(StateService);
  private readonly loads = inject(LoadService);

  readonly sandbox = this.sandboxes.sandbox;
  readonly starting = this.sandboxes.starting;
  readonly failure = this.sandboxes.failure;

  ngOnInit(): void {
    const existing = this.sandbox();
    this.state.watch(existing?.dropId ?? null);
    this.loads.watch(existing?.dropId ?? null, existing?.admitRate ?? null);
  }

  /**
   * Leaving puts the state loop back on the public session and stops the load
   * loop entirely.
   *
   * The two differ on purpose. The public slot always has numbers worth showing,
   * so the state loop follows it; there is no public load run, and polling for
   * one would ask about a Job nobody started once every two seconds forever.
   */
  ngOnDestroy(): void {
    this.state.watch(null);
    this.loads.watch(null);
  }

}

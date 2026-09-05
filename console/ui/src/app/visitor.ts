import { Component, OnDestroy, OnInit, inject, output } from '@angular/core';
import { CanonicalDrop } from './canonical-drop';
import { Constraints } from './constraints';
import { LoadControl } from './load-control';
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
  imports: [CanonicalDrop, LoadControl, Constraints],
  template: `
    <div class="stack">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">Your simulation</span>
      </div>

      <section class="panel">
        <div class="accent-top"></div>
        <div class="body">
          <div>
            <h1>Your simulation</h1>
            <p style="margin: 0; font-size: 17px; color: var(--ink-soft); max-width: 66ch; text-wrap: pretty;">
              A 9pm opening of your own: its own slot row of 250 seats, its own ticket counter, its
              own admission window. Nothing you do here touches the public slot or anyone else
              looking at this page, and starting again is free.
            </p>
          </div>
          <div class="start-step">
            <div class="card-title"><span class="step-num mono">1</span>Start a simulation</div>
            <p class="sub">
              Seeds a fresh 250-seat slot and a ticket counter of your own, in about a second. This
              opens an <strong>empty</strong> queue — step 2 below is what puts people into it.
            </p>
          </div>
          <div>
            <button class="btn btn-primary" [disabled]="starting()" (click)="start()">
              {{ starting() ? 'Starting…' : sandbox() ? 'Start another simulation' : 'Start a simulation' }}
            </button>
            @if (sandbox(); as s) {
              <p class="reason">
                Your own sitting of 250 seats is open, admitting {{ s.admitRate }} a second.
                It clears itself up after thirty idle minutes, and nothing you do here touches
                anyone else looking at this page.
              </p>
            } @else if (failure(); as reason) {
              <p class="reason">{{ reason }}</p>
            } @else {
              <p class="reason">
                Seeds a slot on booking-service and opens a session bound to it on the gate. Both
                expire on their own, so there is nothing to tidy up afterwards.
              </p>
            }
          </div>
        </div>
      </section>

      @if (sandbox(); as s) {
        <rb-load-control [startingRate]="s.admitRate" />
        <rb-canonical-drop heading="Your simulation, live" />
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

  start(): void {
    // The page reads the session; the slot comes back with it from the gate.
    this.sandboxes.start((sandbox) => {
      this.state.watch(sandbox.dropId);
      this.loads.watch(sandbox.dropId, sandbox.admitRate);
    });
  }
}

import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CanonicalDrop } from './canonical-drop';
import { Constraints } from './constraints';
import { LoadControl } from './load-control';
import { LoadService } from './load.service';
import { SandboxService } from './sandbox.service';
import { StateService } from './state.service';

/**
 * Your sandbox: a drop of your own, on a slot of your own.
 *
 * The drop id is the whole session. There is no account and nothing stored on
 * the visitor's behalf — whoever is looking at this page is one of two people
 * who hold the key, and both halves of a sandbox expire by themselves: the drop
 * with its Redis key after thirty idle minutes, the slot with the sweeper.
 * Starting again is therefore free, and the button never needs to clean up
 * after the last attempt.
 */
@Component({
  selector: 'rb-visitor',
  imports: [CanonicalDrop, LoadControl, Constraints],
  template: `
    <div class="stack">
      <section class="panel">
        <div class="accent-top"></div>
        <div class="body">
          <div>
            <h1>Your sandbox</h1>
            <p style="margin: 0; font-size: 17px; color: var(--ink-soft); max-width: 64ch; text-wrap: pretty;">
              A drop of your own: its own slot row of 250 seats, its own ticket counter, its own
              admission window. Nothing you do here touches slot 1 or anyone else looking at this
              page, and starting again is free.
            </p>
          </div>
          <ol class="steps">
            <li>Start a drop, which seeds a slot and a counter in about a second.</li>
            <li>Open it, then send two hundred customers at it at once.</li>
            <li>Push the admission rate up until the connection pool gives out, and watch oversold stay at zero.</li>
            <li>If the namespace is out of CPU your run will sit Pending, and the constraints panel will say so in the scheduler's own words. That is the demonstration, not a fault.</li>
          </ol>
          <div>
            <button class="btn btn-primary" [disabled]="starting()" (click)="start()">
              {{ starting() ? 'Starting…' : sandbox() ? 'Start another drop' : 'Start a drop' }}
            </button>
            @if (sandbox(); as s) {
              <p class="reason">
                Watching drop <span class="mono">{{ s.dropId }}</span> on slot
                <span class="mono">{{ s.slotId }}</span>, admitting {{ s.admitRate }} a second.
                It expires by itself after thirty idle minutes, and so does the slot behind it.
              </p>
            } @else if (failure(); as reason) {
              <p class="reason">{{ reason }}</p>
            } @else {
              <p class="reason">
                Seeds a slot on booking-service and creates a drop bound to it on the gate. Both
                expire on their own, so there is nothing to tidy up afterwards.
              </p>
            }
          </div>
        </div>
      </section>

      @if (sandbox(); as s) {
        <rb-canonical-drop heading="Your drop" />
        <rb-load-control [startingRate]="s.admitRate" />
      }

      <rb-constraints />
    </div>
  `,
  styles: `
    .body { padding: 32px 24px; display: flex; flex-direction: column; gap: 20px; }
    .steps {
      margin: 0;
      padding-left: 22px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      font-size: 15px;
      max-width: 64ch;
    }
  `
})
/**
 * The poll loop follows this page: while the visitor's own drop is on screen
 * the console reads that drop, and leaving puts the loop back on the canonical
 * one. Without that, walking from here to the public page would show a stranger
 * the visitor's sandbox under the heading "the canonical drop".
 */
export class Visitor implements OnInit, OnDestroy {
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
   * Leaving puts the state loop back on the canonical drop and stops the load
   * loop entirely.
   *
   * The two differ on purpose. The canonical drop always has numbers worth
   * showing, so the state loop follows it; there is no canonical load run, and
   * polling for one would ask about a Job nobody started once every two seconds
   * forever.
   */
  ngOnDestroy(): void {
    this.state.watch(null);
    this.loads.watch(null);
  }

  start(): void {
    // The page reads the drop; the slot comes back with it from the gate.
    this.sandboxes.start((sandbox) => {
      this.state.watch(sandbox.dropId);
      this.loads.watch(sandbox.dropId, sandbox.admitRate);
    });
  }
}

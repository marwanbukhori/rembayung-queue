import { Component } from '@angular/core';

/**
 * Your sandbox.
 *
 * The controls are present and disabled rather than absent: the layout the
 * design calls for is here, and the button says plainly that the endpoint
 * behind it does not exist yet. POST /internal/drops arrives with task 6, and
 * the load ladder and constraints panel with task 7.
 */
@Component({
  selector: 'rb-visitor',
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
          </ol>
          <div>
            <button class="btn btn-primary" disabled>Start a drop</button>
            <p class="reason">
              Not wired yet. Creating a drop needs POST /internal/drops on the gate, which task 6
              adds; the gate today serves the canonical drop and per-visitor sandboxes read through
              the same state endpoint the public page uses.
            </p>
          </div>
        </div>
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Constraints</h2>
            <p class="sub">What the cluster will and will not give you, live</p>
          </div>
        </div>
        <div class="card">
          <div class="reason" style="margin-top: 0;">
            Not read yet. The namespace CPU budget, the autoscalers and the Oracle pool arrive with
            task 7, alongside the load runs that make them move.
          </div>
        </div>
      </section>
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
export class Visitor {}

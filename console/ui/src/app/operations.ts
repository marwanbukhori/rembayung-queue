import { Component } from '@angular/core';

/**
 * Operations, the operator tier.
 *
 * A fixed list of named operations, each mapping to one Kubernetes call or one
 * parameterised statement — no command box, no SQL from the browser. None of
 * them is wired here: the keys that authorise them are task 6's, and wiring a
 * button before the thing that checks who pressed it would be the wrong order.
 */
@Component({
  selector: 'rb-operations',
  template: `
    <div class="stack">
      <section class="head">
        <div style="min-width: 0;">
          <h1>Operations</h1>
          <p class="lede">
            Global actions, gated on your own key. A fixed list of named operations, each mapping to
            one Kubernetes call or one parameterised statement. No command box, no SQL from the
            browser.
          </p>
        </div>
      </section>

      <section class="stack-16">
        <h2>Cluster</h2>
        <div class="panel">
          @for (op of ops; track op.name) {
            <div class="op">
              <div style="flex: 1 1 260px; min-width: 0;">
                <div class="op-name">{{ op.name }}</div>
                <div class="op-impl">{{ op.impl }}</div>
                <div class="op-why">{{ op.why }}</div>
              </div>
              <button class="btn btn-secondary" disabled>{{ op.action }}</button>
            </div>
          }
        </div>
        <p class="reason">
          Every action here is disabled until task 6 issues the keys that authorise them. The list
          is fixed on purpose: an operation the console cannot name is one it will not run.
        </p>
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Canonical slot</h2>
            <p class="sub">The one action with a blast radius outside your own session</p>
          </div>
        </div>
        <div class="warn">
          <div class="warn-bar"></div>
          <div class="warn-body">
            <div class="warn-title">This one is not a sandbox</div>
            <div class="warn-text">
              Slot 1 is what the public page reads. Resetting it is the only action here with a
              blast radius outside your own session, so it will name the slot before it runs.
            </div>
          </div>
        </div>
      </section>

      <section class="stack-16">
        <div class="section-head">
          <div style="min-width: 0;">
            <h2>Audit trail</h2>
            <p class="sub">One structured line per action, carrying the key that ran it</p>
          </div>
        </div>
        <div class="card">
          <div class="reason" style="margin-top: 0;">
            Empty until there are actions to record. Each line is emitted as structured JSON by the
            service that performed the operation, so the trail cannot disagree with the logs.
          </div>
        </div>
      </section>
    </div>
  `,
  styles: `
    .head { display: flex; flex-wrap: wrap; gap: 16px 24px; align-items: flex-start; justify-content: space-between; }
    .op {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px 16px;
      padding: 16px;
      border-bottom: 1px solid var(--rule);
    }
    .op:last-child { border-bottom: 0; }
    .op-name { font-size: 17px; font-weight: 700; }
    .op-impl {
      font-family: var(--mono);
      font-size: 13px;
      color: var(--ink-soft);
      margin-top: 2px;
      overflow-wrap: anywhere;
    }
    .op-why { font-size: 14px; color: var(--muted); margin-top: 4px; text-wrap: pretty; }
    .warn {
      display: grid;
      grid-template-columns: 4px 1fr;
      gap: 16px;
      border-radius: 4px;
      background: var(--chip-bad-bg);
      overflow: hidden;
    }
    .warn-bar { background: var(--dhl-red); }
    .warn-body { padding: 16px 16px 16px 0; }
    .warn-title { font-weight: 700; font-size: 14px; }
    .warn-text { font-size: 14px; color: var(--ink-soft); margin-top: 2px; max-width: 70ch; text-wrap: pretty; }
  `
})
export class Operations {
  readonly ops = [
    {
      name: 'Deploy a tag',
      impl: 'trigger rembayung-cd workflow',
      why: 'Safe to show: a bad tag rolls itself back and the site keeps serving 200.',
      action: 'Deploy'
    },
    {
      name: 'Roll back',
      impl: 'trigger rembayung-cd with previous tag',
      why: 'The same workflow with an earlier tag. No separate path to maintain.',
      action: 'Roll back'
    },
    {
      name: 'Pre scale the gate',
      impl: 'scale deployment/queue-gate to 10',
      why: 'Takes 1200m of the budget, which is how visitor load jobs end up Pending.',
      action: 'Scale to 10'
    },
    {
      name: 'Scale back',
      impl: 'scale deployment/queue-gate to 2',
      why: 'Returns the budget. Any Pending load job schedules on the next poll.',
      action: 'Scale to 2'
    },
    {
      name: 'Keepalive job',
      impl: 'trigger cronjob/oracle-keepalive',
      why: 'Keeps the Always Free database from idling out between demos.',
      action: 'Run'
    }
  ];
}

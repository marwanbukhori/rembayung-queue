import { Component, output, signal } from '@angular/core';
import { CAPTURED_RUNS, CapturedRun } from './cicd-runs';
import { PipelineDiagram } from './pipeline-diagram';
import { TIME_ZONE_LABEL, malaysiaTime } from './time';

/**
 * How a commit reaches a pod, shown with the real thing.
 *
 * Not a status page: the runs below are a copy of real ones, captured once by
 * deploy/scripts/capture-cicd-runs.py, so the page needs no GitHub call and no
 * credential. They are laid out the way GitHub's run page lays them out, a row
 * per step with its duration, because that is what a reader who has used
 * Actions already knows how to read; each step adds the reason it exists.
 *
 * The third run is the failure worth showing: a deploy that did not become
 * ready, rolled itself back and kept the site up. It starts collapsed so the
 * page reads as the normal path first.
 */
@Component({
  selector: 'rb-cicd-page',
  imports: [PipelineDiagram],
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">CI/CD</span>
      </div>
      <div>
        <h1>CI/CD</h1>
        <p class="lede">
          Every push to main runs two workflows. <b>ci</b> tests the three services, booking-service
          against a real Oracle and queue-gate against a real Redis, then builds and pushes one image
          per service, tagged with the commit. <b>CD</b> starts when ci succeeds: an Ansible playbook deploys that tag to
          OpenShift, smoke-tests it, and rolls back by itself if anything fails. Below are real
          runs of each, copied from GitHub, with what every step is for.
        </p>
      </div>

      <rb-pipeline-diagram />

      @for (run of runs; track run.runId + '-' + run.attempt; let i = $index) {
        <section class="card run" [class.failed]="run.result !== 'success'">
          <button class="run-head" (click)="toggleRun(i)" [attr.aria-expanded]="runOpen(i)">
            <span class="mark" [class.bad]="run.result !== 'success'">{{ run.result === 'success' ? '✓' : '✕' }}</span>
            <span class="run-title">
              <span class="run-name">{{ titleOf(run) }}</span>
              <span class="run-meta mono">
                {{ run.workflow }} · {{ run.job }} · {{ verb(run) }} in {{ duration(run.seconds) }}
                · {{ when(run.startedAt) }} {{ zone }} · commit {{ run.commit }}{{ run.attempt > 1 || run.kind === 'rollback' ? ' · attempt ' + run.attempt : '' }}
              </span>
            </span>
            <span class="chev" aria-hidden="true">{{ runOpen(i) ? '▾' : '▸' }}</span>
          </button>

          @if (runOpen(i)) {
            @if (run.note) {
              <p class="note">{{ run.note }}</p>
            }
            <ol class="steps">
              @for (step of run.steps; track step.name; let s = $index) {
                <li>
                  <button class="step" (click)="toggleStep(i, s)" [attr.aria-expanded]="stepOpen(i, s)">
                    <span class="chev" aria-hidden="true">{{ stepOpen(i, s) ? '▾' : '▸' }}</span>
                    <span class="tick" [class.bad]="step.result !== 'success'">{{ step.result === 'success' ? '✓' : '✕' }}</span>
                    <span class="step-name">{{ step.name }}</span>
                    <span class="step-time mono">{{ duration(step.seconds) }}</span>
                  </button>
                  @if (stepOpen(i, s)) {
                    @if (step.log.length) {
                      <pre class="log"><code>@for (line of step.log; track $index) {<span class="ln">{{ line[0] ?? '' }}</span><span class="lt" [class.gap]="line[0] === null">{{ line[1] }}</span>
}</code></pre>
                    }
                    @if (step.explain) {
                      <p class="explain">{{ step.explain }}</p>
                    }
                  }
                </li>
              }
            </ol>
            <a class="gh" [href]="run.url" target="_blank" rel="noopener">View this run on GitHub →</a>
          }
        </section>
      }

      <section>
        <h2 class="guard-head">What it guards against</h2>
        <div class="guards">
          <button class="card guard" (click)="openRollback()">
            <span class="guard-name">Automatic rollback</span>
            <span class="guard-what">
              A deploy that does not become ready, or fails its smoke test, restores each service to
              the tag it was running and fails loudly. The third run above is one.
            </span>
          </button>
          <a class="card guard" [href]="driftScript" target="_blank" rel="noopener">
            <span class="guard-name">Drift check</span>
            <span class="guard-what">
              check-drift.sh renders the manifests from git and diffs them against the cluster, so a
              change that was committed but never applied is loud instead of invisible.
            </span>
          </a>
          <button class="card guard" (click)="open.emit('07-continuous-delivery')">
            <span class="guard-name">What CD may not do</span>
            <span class="guard-what">
              CD's ServiceAccount cannot read Secrets or change RBAC. Permissions are applied by hand,
              so the pipeline can never grant itself more access.
            </span>
          </button>
        </div>
        <p class="more">
          The reasoning behind each is in
          <button class="link" (click)="open.emit('06-continuous-integration')">note 06, continuous integration</button>
          and <button class="link" (click)="open.emit('07-continuous-delivery')">note 07, continuous delivery</button>.
        </p>
      </section>
    </div>
  `,
  styles: `
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .run { padding: 0; overflow: hidden; }
    .run.failed { border-left: 4px solid var(--chip-bad-fg); }
    .run-head { display: flex; align-items: flex-start; gap: 12px; width: 100%; text-align: left; font: inherit;
                background: none; border: 0; padding: 16px 20px; cursor: pointer; color: var(--ink); }
    .run-title { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 0; }
    .run-name { font-size: 17px; font-weight: 700; }
    .run-meta { font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }
    .mark, .tick { flex: none; width: 20px; height: 20px; border-radius: 50%; display: inline-grid; place-items: center;
                   font-size: 12px; font-weight: 700; color: #fff; background: var(--chip-ok-fg); }
    .mark.bad, .tick.bad { background: var(--chip-bad-fg); }
    .chev { flex: none; color: var(--muted); width: 12px; }
    .note { margin: 0 20px 12px; padding: 10px 14px; font-size: 14px; background: var(--chip-bad-bg);
            color: var(--ink); border-radius: 4px; text-wrap: pretty; }
    .steps { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--line); }
    .steps li { border-bottom: 1px solid var(--line); }
    .step { display: flex; align-items: center; gap: 10px; width: 100%; text-align: left; font: inherit; font-size: 14px;
            background: none; border: 0; padding: 9px 20px; cursor: pointer; color: var(--ink); }
    .step:hover { background: var(--canvas); }
    .step-name { flex: 1; min-width: 0; overflow-wrap: anywhere; }
    .step-time { font-size: 12px; color: var(--muted); }
    .log { margin: 0 20px 0 42px; max-height: 420px; overflow: auto; background: #1d1d1d; color: #d7e6df;
           border-radius: 6px; padding: 10px 0; font-family: var(--mono); font-size: 12px; line-height: 1.55; }
    .log code { display: grid; grid-template-columns: max-content minmax(0, 1fr); }
    .ln { color: #6f7f78; text-align: right; padding: 0 12px; user-select: none; }
    .lt { white-space: pre-wrap; overflow-wrap: anywhere; padding-right: 12px; }
    .lt.gap { color: #8a9; font-style: italic; }
    .explain { margin: 10px 20px 14px 42px; font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }
    .gh { display: inline-block; margin: 12px 20px 16px; font-size: 14px; }
    .guard-head { font-size: 19px; margin: 8px 0 12px; }
    .guards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
    .guard { display: flex; flex-direction: column; gap: 6px; text-align: left; font: inherit; cursor: pointer;
             padding: 16px 18px; color: var(--ink); }
    .guard:hover { border-color: var(--ink); }
    a.guard { text-decoration: none; }
    .guard-name { font-weight: 700; }
    .guard-what { font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }
    .more { font-size: 14px; color: var(--ink-soft); }
    .link { background: none; border: 0; padding: 0; font: inherit; color: var(--dhl-red); cursor: pointer;
            text-decoration: underline; text-underline-offset: .15em; }
    @media (max-width: 799px) { .guards { grid-template-columns: minmax(0, 1fr); } }
    @media (max-width: 599px) {
      .log { margin-left: 12px; margin-right: 12px; }
      .explain { margin-left: 12px; margin-right: 12px; }
      .step, .run-head { padding-left: 12px; padding-right: 12px; }
    }
  `
})
export class CicdPage {
  readonly home = output<void>();
  readonly open = output<string>();

  protected readonly runs = CAPTURED_RUNS;
  protected readonly zone = TIME_ZONE_LABEL;
  protected readonly driftScript =
    'https://github.com/marwanbukhori/rembayung-queue/blob/main/deploy/scripts/check-drift.sh';
  /** The normal runs start open; the rollback starts closed, so the page reads as the normal path first. */
  private readonly runsOpen = signal<Set<number>>(
    new Set(CAPTURED_RUNS.flatMap((run, i) => (run.kind === 'rollback' ? [] : [i]))));
  /** Opens on the step a reader most wants: the tests for ci, the deploy for CD. */
  private readonly stepsOpen = signal<Set<string>>(new Set(CAPTURED_RUNS.map((run, i) =>
    `${i}:${run.kind === 'ci' ? 'Test booking-service' : 'Deploy'}`)));

  protected runOpen(i: number): boolean {
    return this.runsOpen().has(i);
  }

  protected toggleRun(i: number): void {
    this.runsOpen.update(s => flip(s, i));
  }

  protected stepOpen(i: number, s: number): boolean {
    return this.stepsOpen().has(`${i}:${this.runs[i].steps[s].name}`);
  }

  protected toggleStep(i: number, s: number): void {
    this.stepsOpen.update(set => flip(set, `${i}:${this.runs[i].steps[s].name}`));
  }

  protected openRollback(): void {
    const i = this.runs.findIndex(run => run.kind === 'rollback');
    if (i < 0) {
      return;
    }
    this.runsOpen.update(s => new Set(s).add(i));
    queueMicrotask(() => document.querySelectorAll('rb-cicd-page .run')[i]?.scrollIntoView({ behavior: 'smooth' }));
  }

  protected titleOf(run: CapturedRun): string {
    if (run.kind === 'rollback') {
      return 'A deploy that rolled itself back';
    }
    return run.workflow === 'ci' ? 'Test, build and publish' : 'Deploy to OpenShift';
  }

  protected verb(run: CapturedRun): string {
    return run.result === 'success' ? 'succeeded' : 'failed';
  }

  protected when(at: string): string {
    return malaysiaTime(at, false) + ', ' + new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Kuala_Lumpur', day: 'numeric', month: 'short', year: 'numeric'
    }).format(new Date(at));
  }

  protected duration(seconds: number): string {
    if (seconds < 1) {
      return '0s';
    }
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return m ? `${m}m ${s}s` : `${s}s`;
  }
}

function flip<T>(set: Set<T>, value: T): Set<T> {
  const next = new Set(set);
  if (!next.delete(value)) {
    next.add(value);
  }
  return next;
}

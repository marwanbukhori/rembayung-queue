import { Component, computed, inject, input } from '@angular/core';
import { StateService } from './state.service';

/**
 * Pods in the namespace, read through a ServiceAccount that can see workloads
 * and not Secrets.
 *
 * `detail` mode is the ordinary state on a laptop and a brief state in the
 * cluster, so it renders as a sentence in the panel rather than an error.
 */
@Component({
  selector: 'rb-pod-health',
  template: `
    @if (full()) {
      <div class="scroller">
        <table style="min-width: 640px;">
          <thead>
            <tr>
              <th>Workload</th>
              <th>Ready</th>
              <th class="right">CPU request</th>
              <th class="right">Restarts</th>
              <th class="right">Age</th>
            </tr>
          </thead>
          <tbody>
            @for (pod of pods(); track pod.name) {
              <tr>
                <td class="mono">{{ pod.name }}</td>
                <td>
                  <span class="chip" [class.chip-ok]="pod.healthy" [class.chip-warn]="!pod.healthy">
                    <span class="dot"></span>{{ pod.ready }}
                  </span>
                </td>
                <td class="mono right muted">{{ pod.cpu }}</td>
                <td class="mono right muted">{{ pod.restarts }}</td>
                <td class="mono right muted">{{ pod.age }}</td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="muted">{{ emptyText() }}</td></tr>
            }
          </tbody>
        </table>
      </div>
    } @else {
      @if (pods().length) {
        <div class="grid">
          @for (pod of pods(); track pod.name) {
            <div class="card pod">
              <span class="chip" [class.chip-ok]="pod.healthy" [class.chip-warn]="!pod.healthy">
                <span class="dot"></span>{{ pod.healthy ? 'Ready' : 'Not ready' }}
              </span>
              <span class="name">{{ pod.name }}</span>
              <span class="ready">{{ pod.ready }}</span>
            </div>
          }
        </div>
      } @else {
        <div class="card"><div class="reason" style="margin-top: 0;">{{ emptyText() }}</div></div>
      }
    }
  `,
  styles: `
    .grid {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
    }
    .pod { padding: 16px; display: flex; align-items: center; gap: 12px; }
    .name { font-family: var(--mono); font-size: 14px; min-width: 0; overflow-wrap: anywhere; }
    .ready { margin-left: auto; font-family: var(--mono); font-size: 14px; color: var(--ink-soft); }
  `
})
export class PodHealthPanel {
  /** The home page shows cards; the pod page shows the whole table. */
  readonly full = input(false);

  private readonly state = inject(StateService);

  private readonly health = computed(() => this.state.view()?.pods ?? null);

  readonly pods = computed(() => this.health()?.pods ?? []);

  readonly emptyText = computed(() => {
    const health = this.health();
    if (!health) {
      return 'Reading the namespace…';
    }
    return health.available
      ? 'No pods in this namespace.'
      : health.detail ?? 'The cluster is not readable from here.';
  });
}

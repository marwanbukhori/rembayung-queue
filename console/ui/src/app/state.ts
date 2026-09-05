/**
 * The shapes GET /api/state returns.
 *
 * `available` is a field on each half rather than one flag for the whole
 * response because they fail independently: a sandbox drop expiring should not
 * blank the pod list, and a lapsed Kubernetes token should not hide the seats.
 */
export interface DemoState {
  available: boolean;
  detail: string | null;
  dropId: string | null;
  /** Which slot the drop sells. The gate says; the page does not assume. */
  slotId: number;
  capacity: number;
  seatsTaken: number;
  remaining: number;
  oversold: number;
  ticketsIssued: number;
  admitted: number;
  waiting: number;
}

export interface PodStatus {
  name: string;
  ready: string;
  healthy: boolean;
  cpu: string;
  restarts: number;
  age: string;
}

export interface PodHealth {
  available: boolean;
  detail: string | null;
  pods: PodStatus[];
}

export interface ConsoleView {
  drop: DemoState;
  pods: PodHealth;
}

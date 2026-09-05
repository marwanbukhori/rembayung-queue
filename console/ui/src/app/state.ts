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
  /** Running, Succeeded, Pending or Failed. 0/1 alone cannot tell them apart. */
  phase: string;
}

export interface PodHealth {
  available: boolean;
  detail: string | null;
  /** The namespace the pods were read from. Reported by the API, never assumed here. */
  namespace: string | null;
  pods: PodStatus[];
}

export interface ConsoleView {
  drop: DemoState;
  pods: PodHealth;
}

/**
 * The shapes GET /api/cluster returns: what the cluster will and will not give
 * you.
 *
 * Same `available` contract as the panels above, and for the same reason — the
 * Kubernetes API being unreadable is ordinary on a laptop and brief inside a
 * cluster, and neither should blank the drop's own numbers.
 */
export interface Quota {
  name: string;
  usedMillis: number;
  hardMillis: number;
  freeMillis: number;
  percent: number;
}

/** One workload's share of the namespace CPU budget. */
export interface Consumer {
  name: string;
  millis: number;
  pods: number;
}

export interface Autoscaler {
  name: string;
  current: number;
  desired: number;
  min: number;
  max: number;
  currentPercent: number | null;
  targetPercent: number | null;
  /** The HPA's own ScalingLimited message, when it is being held back. */
  note: string | null;
}

/** Replicas times the Hikari pool, against Oracle's session cap. */
export interface Pool {
  deployment: string;
  replicas: number;
  perReplica: number;
  cap: number;
  connections: number;
  saturated: boolean;
  percent: number;
}

export interface ClusterState {
  available: boolean;
  detail: string | null;
  quota: Quota | null;
  consumers: Consumer[];
  autoscalers: Autoscaler[];
  pool: Pool | null;
}

export type LoadPhase = 'NONE' | 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/**
 * One load run.
 *
 * `reason` and `message` are the scheduler's and the quota controller's own
 * words — "Insufficient cpu", "exceeded quota: compute-deploy, requested:
 * requests.cpu=800m" — carried through unchanged. They are the content of the
 * constraints panel, not an error to be tidied away.
 */
export interface LoadRun {
  available: boolean;
  detail: string | null;
  dropId: string;
  jobName: string | null;
  phase: LoadPhase;
  vus: number;
  cpuMillis: number;
  reason: string | null;
  message: string | null;
  secondsElapsed: number;
}

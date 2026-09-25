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
  /** The Deployment or Job, from ownerReferences rather than a name split. */
  workload: string;
  ownerKind: string;
  node: string;
  podIp: string;
  /** Guaranteed, Burstable or BestEffort. */
  qos: string;
  /** The image tag, shortened to seven characters when it is a commit SHA. */
  image: string;
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

/** One Service, and the Route publishing it if anything does. */
export interface Endpoint {
  name: string;
  type: string;
  ports: string;
  selector: string;
  /** null when nothing publishes it — which is the interesting case. */
  route: string | null;
}

export interface ClusterState {
  available: boolean;
  detail: string | null;
  quota: Quota | null;
  consumers: Consumer[];
  autoscalers: Autoscaler[];
  pool: Pool | null;
  endpoints: Endpoint[];
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

/**
 * The shapes GET /api/observability returns.
 *
 * Two vendors, reported the same way: is the pipeline alive, and which
 * workloads feed it. Neither carries logs or traces — reading those back needs
 * credentials this cluster does not hold — so a feed says what is wired, and
 * the vendors' own UIs say what arrived.
 */
export interface Feed {
  service: string;
  on: boolean;
  detail: string;
}

export interface SplunkStatus {
  endpoint: string;
  reachable: boolean;
  detail: string;
  /** How long the collector took to answer, or -1 when it did not answer. */
  latencyMs: number;
  shippers: Feed[];
  /** Why Splunk is switched off on purpose, or null when it is meant to be on. */
  disabled: string | null;
}

export interface DynatraceStatus {
  tenant: string;
  /** Which OneAgent flavour, because it decides what there is to look at. */
  mode: string;
  instrumented: Feed[];
  detail: string;
  /** Why the agent is switched off on purpose, or null when it is meant to be on. */
  disabled: string | null;
}

export interface ObservabilityStatus {
  splunk: SplunkStatus;
  dynatrace: DynatraceStatus;
  checkedAt: string;
}

/** One object the inspector can show, as it appears in a URL: `?inspect=pod/queue-gate-x`. */
export interface ObjectRef {
  kind: string;
  name: string;
}

export type Tone = 'ok' | 'warn' | 'bad' | 'neutral';

export interface ObjectFact {
  label: string;
  value: string;
  tone: Tone | null;
}

export interface ObjectLink {
  kind: string;
  name: string;
  label: string;
  tone: Tone | null;
}

export interface ObjectEvent {
  at: string | null;
  type: string;
  reason: string;
  message: string;
  count: number;
}

export interface ObjectDetail {
  kind: string;
  name: string;
  available: boolean;
  detail: string | null;
  tone: Tone;
  headline: string;
  facts: ObjectFact[];
  related: ObjectLink[];
  events: ObjectEvent[];
}

export interface ObjectSummary {
  kind: string;
  name: string;
  tone: Tone;
  headline: string;
  at: string | null;
}

export interface LogLine {
  at: string | null;
  level: string | null;
  message: string;
  event: string | null;
  fields: Record<string, string>;
}

export interface LogPage {
  pod: string;
  available: boolean;
  detail: string | null;
  filter: 'all' | 'warn' | 'events';
  restricted: boolean;
  note: string | null;
  lines: LogLine[];
  latest: string | null;
}

export type ChartKey = 'requests' | 'latency' | 'pool' | 'replicas';
export interface ChartSeries { label: string; points: [number, number][]; }
export interface ChartReading { label: string; value: number; }
export interface ChartData {
  name: ChartKey;
  unit: string;
  history: string | null;
  series: ChartSeries[];
  live: string | null;
  now: ChartReading[];
}

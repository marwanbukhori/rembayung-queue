import { HttpClient } from '@angular/common/http';
import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';

/** The fault running now, if any. */
export interface ActiveFault {
  fault: string;
  startedAt: string;
  until: string;
}

export interface SloReading {
  at: string;
  available: boolean;
  detail: string | null;
  hasTraffic: boolean;
  successRatio: number | null;
  p95Seconds: number | null;
}

export interface Slo {
  now: SloReading;
  history: SloReading[];
  targets: { success: number; p95Seconds: number };
}

export interface IncidentSummary {
  id: string;
  kind: 'drill' | 'breach';
  fault: string | null;
  status: 'open' | 'mitigating' | 'resolved' | 'unresolved';
  openedAt: string;
  resolvedAt: string | null;
  proposals: number;
  pendingProposal: boolean;
}

export interface IncidentEvent { at: string; source: string; text: string; }
export interface IncidentFact { id: string; source: string; label: string; value: string; }
export interface Diagnosis {
  at: string;
  cause: string;
  confidence: 'low' | 'medium' | 'high';
  claims: { text: string; facts: string[] }[];
  facts: IncidentFact[];
}
export interface Proposal {
  n: number;
  at: string;
  action: string;
  target: string;
  replicas: number | null;
  reason: string;
  facts: string[];
  status: 'pending' | 'approved' | 'dismissed' | 'failed';
  decidedAt: string | null;
}
export interface Postmortem {
  summary: string;
  impact: string;
  rootCause: string;
  fix: string;
  followUps: string[];
  timeToDetectSeconds: number;
  timeToRecoverSeconds: number;
  source: 'model' | 'fallback';
}
export interface Incident {
  id: string;
  kind: 'drill' | 'breach';
  fault: string | null;
  openedAt: string;
  detectedAt: string | null;
  resolvedAt: string | null;
  status: IncidentSummary['status'];
  timeline: IncidentEvent[];
  diagnoses: Diagnosis[];
  proposals: Proposal[];
  reverts: { hpa: string; minReplicas: number; at: string }[];
  postmortem: Postmortem | null;
  cycles: number;
}

/** The three drills, as the page names them. */
export const FAULTS: { id: string; name: string; effect: string }[] = [
  { id: 'kill-booking-pod', name: 'Kill a booking-service pod',
    effect: 'Half the database connections vanish until Kubernetes replaces the pod.' },
  { id: 'slow-database', name: 'Slow the database',
    effect: 'Every booking holds its connection 400 ms longer; the queue backs up.' },
  { id: 'squeeze-pool', name: 'Squeeze the pool',
    effect: 'Each booking-service pod gets 1 connection instead of 5; 503s follow fast.' }
];

export function faultName(id: string | null | undefined): string {
  return FAULTS.find((f) => f.id === id)?.name ?? id ?? '';
}

export function isOpen(status: string | undefined): boolean {
  return status === 'open' || status === 'mitigating';
}

/** What an approved proposal would do, in words. */
export function describe(p: Proposal): string {
  switch (p.action) {
    case 'scale-booking': return `Scale booking-service to ${p.replicas ?? 3}`;
    case 'raise-hpa-min': return `Raise ${p.target}'s autoscaler minimum to ${p.replicas ?? 3} for 10 minutes`;
    case 'restart-booking': return 'Restart booking-service';
    case 'end-fault': return 'End the fault now';
    default: return p.action;
  }
}

/**
 * Drills and incidents for every page: the active fault, the incident list,
 * the open incident in full and the SLOs.
 *
 * One poll for the whole site, every 5 seconds while something is happening and
 * every 15 while nothing is, so the banner, the chaos card, the chart markers
 * and the Incidents view never disagree about what is open.
 */
@Injectable({ providedIn: 'root' })
export class IncidentService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stopped = false;

  readonly active = signal<ActiveFault | null>(null);
  readonly incidents = signal<IncidentSummary[]>([]);
  readonly current = signal<Incident | null>(null);
  readonly slo = signal<Slo | null>(null);
  /** The browser's clock, ticking each second, for countdowns and elapsed times. */
  readonly now = signal(Date.now());
  /** Bumped when something asks for the Incidents view, so an open agent page switches to it. */
  readonly focus = signal(0);

  readonly open = computed(() => this.incidents().find((i) => isOpen(i.status)) ?? null);

  /** Where a fault started and where a fix was applied, for the charts. */
  readonly marks = computed(() => {
    const out: { label: string; t: number }[] = [];
    const i = this.current();
    if (i) {
      const fault = i.timeline.find((e) => e.source === 'chaos');
      if (fault) {
        out.push({ label: 'fault', t: Date.parse(fault.at) / 1000 });
      }
      for (const p of i.proposals) {
        if (p.status === 'approved' && p.decidedAt) {
          out.push({ label: 'fix', t: Date.parse(p.decidedAt) / 1000 });
        }
      }
    }
    return out;
  });

  private readonly clock = setInterval(() => this.now.set(Date.now()), 1000);

  constructor() {
    this.poll();
  }

  ngOnDestroy(): void {
    this.stopped = true;
    clearInterval(this.clock);
    if (this.timer) {
      clearTimeout(this.timer);
    }
  }

  /** Read everything again now, e.g. after a click changed it. */
  refresh(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.poll();
  }

  private poll(): void {
    let pending = 2;
    const done = () => {
      if (--pending === 0 && !this.stopped) {
        const busy = !!this.active() || !!this.open();
        this.timer = setTimeout(() => this.poll(), busy ? 5000 : 15000);
      }
    };
    this.http.get<ActiveFault | Record<string, never>>('/api/chaos').subscribe({
      next: (a) => this.active.set('fault' in a ? (a as ActiveFault) : null),
      error: () => done(),
      complete: done
    });
    this.http.get<IncidentSummary[]>('/api/incidents').subscribe({
      next: (list) => {
        this.incidents.set(list);
        const focus = list.find((i) => isOpen(i.status)) ?? list[0];
        if (focus) {
          this.get(focus.id).subscribe({ next: (i) => this.current.set(i), error: () => {} });
          if (isOpen(focus.status) || !this.slo()) {
            this.loadSlo();
          }
        } else {
          this.current.set(null);
        }
      },
      error: () => done(),
      complete: done
    });
  }

  loadSlo(): void {
    this.http.get<Slo>('/api/slo').subscribe({ next: (s) => this.slo.set(s), error: () => {} });
  }

  get(id: string): Observable<Incident> {
    return this.http.get<Incident>(`/api/incidents/${encodeURIComponent(id)}`);
  }

  inject(fault: string): Observable<ActiveFault> {
    return this.http.post<ActiveFault>('/api/chaos', { fault });
  }

  end(): Observable<unknown> {
    return this.http.delete('/api/chaos');
  }

  decide(id: string, n: number, verdict: 'approve' | 'dismiss'): Observable<{ result: string }> {
    return this.http.post<{ result: string }>(
      `/api/incidents/${encodeURIComponent(id)}/proposals/${n}/${verdict}`, null);
  }
}

/** "2m 05s", or "—" before there is anything to count. */
export function duration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || seconds < 0 || !isFinite(seconds)) {
    return '—';
  }
  const s = Math.floor(seconds);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s`;
}

export function percent(ratio: number | null | undefined): string {
  return ratio === null || ratio === undefined ? '—' : `${(ratio * 100).toFixed(ratio >= 0.999 ? 1 : 2)}%`;
}

export function seconds(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${value.toFixed(2)} s`;
}

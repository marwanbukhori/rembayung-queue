import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The run agent's output, as GET /api/analyses/{job} serves it. */
export interface AgentFact { id: string; source: string; label: string; value: string }
export interface AgentClaim { text: string; facts: string[] }
export interface AgentReport {
  summary?: AgentClaim[];
  customers?: AgentClaim[];
  capacity?: AgentClaim[];
  errors?: AgentClaim[];
  lookAt?: AgentClaim[];
  /** Wave 1 beside wave 2, for a two-wave run. */
  beforeAfter?: AgentClaim[];
  /** Reports stored before the five sections. */
  wentWell?: AgentClaim[];
  caught?: AgentClaim[];
}
export interface AgentStep { tool: string; args: string; why: string; factId: string; via?: string | null }
export interface Analysis {
  job: string;
  dropId: string;
  start: string;
  end: string;
  facts: AgentFact[];
  trail: AgentStep[];
  report: AgentReport;
  model: string;
  source: 'model' | 'fallback';
  note: string | null;
  problems: string[];
  analysedAt: string;
  millis: number;
}
export interface AnalysisSummary {
  key: string;
  job: string;
  dropId: string;
  start: string;
  end: string;
  analysedAt: string;
  source: 'model' | 'fallback';
  model: string;
  note: string | null;
  claims: number;
  customers: number | null;
  booked: number | null;
  seats: number | null;
  oversold: number | null;
  /** 1, or 2 for a two-wave rush. */
  waves?: number;
  /** How long the analysis took. */
  millis?: number;
}

@Injectable({ providedIn: 'root' })
export class AnalysisService {
  private readonly http = inject(HttpClient);

  list(): Observable<AnalysisSummary[]> {
    return this.http.get<AnalysisSummary[]>('/api/analyses');
  }

  get(job: string): Observable<Analysis> {
    return this.http.get<Analysis>(`/api/analyses/${encodeURIComponent(job)}`);
  }

  rerun(job: string): Observable<unknown> {
    return this.http.post(`/api/analyses/${encodeURIComponent(job)}/rerun`, null);
  }
}

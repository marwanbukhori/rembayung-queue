import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The run agent's output, as GET /api/analyses/{job} serves it. */
export interface AgentFact { id: string; source: string; label: string; value: string }
export interface AgentClaim { text: string; facts: string[] }
export interface AgentReport { wentWell: AgentClaim[]; caught: AgentClaim[]; lookAt: AgentClaim[] }
export interface AgentStep { tool: string; args: string; why: string; factId: string }
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

import { Component, OnInit, output, signal } from '@angular/core';
import { CheckId, Coverage, GAPS, IN_ONE_MINUTE, LIVE_CHECKS, OWASP_LLM, OWASP_WEB, PRACTICE_COUNT, Practice, REPO, SECTIONS }
  from './security-content';

type CheckState = { status: 'running' | 'pass' | 'fail'; detail: string };

/**
 * Every security practice in the project, written for someone skimming first
 * and someone checking second: a plain headline and the harm it prevents, with
 * the technical detail and the file behind a "How" that opens on demand.
 *
 * Four practices are checked live from the visitor's browser as the page
 * loads. Both OWASP Top 10 lists - the web one and the one for LLM
 * applications - are answered row by row, each row linking to the practices
 * that cover it. The gaps come last, because a list with no gaps is a list
 * nobody should trust.
 */
@Component({
  selector: 'rb-security-page',
  template: `
    <div class="stack-24">
      <div class="crumbs">
        <button class="btn-crumb" (click)="home.emit()">Overview</button>
        <span>/</span>
        <span style="color: var(--ink);">Security</span>
      </div>
      <div>
        <h1>Security</h1>
        <p class="lede">
          How this system protects itself, its data and its visitors, from the AI agent to the pipeline that ships
          it. {{ count }} practices in plain words, four checked live as you read, both OWASP Top 10 lists answered,
          and the gaps stated.
        </p>
      </div>

      <section class="card pad minute">
        <h2>In one minute</h2>
        <ul>
          @for (m of minute; track m.lead) {
            <li><b>{{ m.lead }}</b> {{ m.text }}</li>
          }
        </ul>
      </section>

      <section class="card pad">
        <h2>Checked just now, from your browser</h2>
        <p class="note">Not a claim: your browser just tried these against the live console.</p>
        <div class="checks">
          @for (c of liveChecks; track c.id) {
            @let s = state()[c.id];
            <div [class]="'check ' + (s?.status ?? 'running')">
              <span class="mark">{{ s?.status === 'pass' ? '✓' : s?.status === 'fail' ? '✕' : '…' }}</span>
              <span class="check-body">
                <span class="check-title">{{ c.title }}</span>
                <span class="check-detail mono">{{ s?.detail ?? 'checking…' }}</span>
              </span>
            </div>
          }
        </div>
      </section>

      @for (sec of sections; track sec.question) {
        <section class="card pad">
          <h2>{{ sec.question }}</h2>
          <p class="answer">{{ sec.answer }}</p>
          <ul class="practices">
            @for (p of sec.items; track p.id) {
              <li class="practice" [id]="'p-' + p.id" [class.flash]="flash() === p.id">
                <div class="p-title">
                  {{ p.title }}
                  @if (p.check && state()[p.check]?.status === 'pass') { <span class="live">✓ checked live</span> }
                </div>
                <div class="protects"><span class="k">Stops</span> {{ p.protects }}</div>
                <details [open]="opened() === p.id">
                  <summary>How</summary>
                  <p class="how">{{ p.how }}
                    @if (p.file) { <a class="mono" [href]="repo + p.file" target="_blank" rel="noopener">{{ short(p.file) }}</a> }
                  </p>
                </details>
              </li>
            }
          </ul>
        </section>
      }

      <section class="card pad">
        <h2>Checked against OWASP</h2>
        <p class="note">
          OWASP publishes the most common ways web applications, and now AI applications, get attacked. Each item
          below says, in plain words, what it means and how this project answers it. Click a practice to see it.
        </p>
        @for (list of owasp; track list.title) {
          <h3 class="list-title">{{ list.title }}
            <span class="tally">{{ tally(list.rows) }}</span>
          </h3>
          <ul class="owasp">
            @for (r of list.rows; track r.code) {
              <li class="row">
                <span class="code mono">{{ r.code }}</span>
                <span class="what">
                  <span class="plain">{{ r.plain }}</span>
                  <span class="official">{{ r.name }}</span>
                </span>
                <span [class]="'status ' + tone(r.status)">{{ r.status }}</span>
                <span class="why">{{ r.why }}
                  @if (r.refs.length) {
                    <span class="refs">
                      @for (id of r.refs; track id) {
                        <button class="ref" (click)="show(id)">{{ titleOf(id) }}</button>
                      }
                    </span>
                  }
                </span>
              </li>
            }
          </ul>
        }
      </section>

      <section class="card pad gaps">
        <h2>What is not done yet</h2>
        <p class="note">Stated rather than hidden, with the reason for each.</p>
        <ul>
          @for (gap of gaps; track gap.title) {
            <li><b>{{ gap.title }}.</b> {{ gap.text }}</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .crumbs { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted); }
    .lede { max-width: 72ch; text-wrap: pretty; }
    .pad { padding: 20px 24px; }
    h2 { font-size: 20px; margin: 0 0 6px; }
    .note { font-size: 14px; color: var(--ink-soft); margin: 0 0 14px; max-width: 78ch; text-wrap: pretty; }
    .answer { font-size: 15px; color: var(--ink-soft); margin: 0 0 14px; max-width: 72ch; text-wrap: pretty; }
    .mono { font-family: var(--mono); }

    .minute { border-left: 4px solid var(--dhl-yellow); }
    .minute ul { margin: 8px 0 0; padding: 0; list-style: none; display: grid; gap: 10px; font-size: 15px;
                 color: var(--ink-soft); max-width: 80ch; }
    .minute b { color: var(--ink); }

    .checks { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 10px; }
    .check { display: flex; gap: 10px; align-items: flex-start; padding: 12px 14px; border-radius: 8px; border: 1px solid var(--line); }
    .check.pass { background: var(--chip-ok-bg); border-color: var(--chip-ok-fg); }
    .check.fail { background: var(--chip-bad-bg); border-color: var(--chip-bad-fg); }
    .mark { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; font-weight: 700;
            background: var(--white); flex: none; }
    .check.pass .mark { color: var(--chip-ok-fg); }
    .check.fail .mark { color: var(--chip-bad-fg); }
    .check-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .check-title { font-weight: 700; font-size: 14px; }
    .check-detail { font-size: 12px; color: var(--ink-soft); overflow-wrap: anywhere; }

    .practices { list-style: none; margin: 0; padding: 0; display: grid; gap: 0 32px;
                 grid-template-columns: repeat(auto-fill, minmax(min(340px, 100%), 1fr)); }
    .practice { padding: 12px 0; border-top: 1px solid var(--line); display: flex; flex-direction: column; gap: 4px;
                scroll-margin-top: 120px; transition: background .6s; }
    .practice.flash { background: var(--chip-warn-bg); }
    .p-title { font-weight: 700; font-size: 16px; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .live { font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 999px; background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .protects { font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }
    .k { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .05em; color: var(--muted); margin-right: 4px; }
    details summary { cursor: pointer; font-size: 13px; color: var(--muted); width: fit-content; }
    details summary:hover { color: var(--ink); }
    .how { margin: 6px 0 2px; font-size: 13px; color: var(--ink); text-wrap: pretty; }
    .how a { font-size: 12px; margin-left: 4px; overflow-wrap: anywhere; }

    .list-title { font-size: 16px; margin: 18px 0 8px; display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px; }
    .tally { font-size: 13px; font-weight: 400; color: var(--muted); }
    .owasp { list-style: none; margin: 0; padding: 0; }
    .row { display: grid; grid-template-columns: 56px minmax(0, 1.1fr) 116px minmax(0, 2fr); gap: 6px 14px;
           align-items: start; padding: 10px 0; border-top: 1px solid var(--line); font-size: 14px; }
    .code { font-size: 13px; color: var(--muted); padding-top: 2px; }
    .what { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .plain { font-weight: 700; }
    .official { font-size: 12px; color: var(--muted); }
    .status { font-size: 12px; font-weight: 700; padding: 2px 9px; border-radius: 999px; width: fit-content; white-space: nowrap; }
    .status.ok { background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .status.warn { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .status.na { background: var(--chip-neutral-bg); color: var(--chip-neutral-fg); }
    .why { color: var(--ink-soft); text-wrap: pretty; }
    .refs { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
    .ref { font: inherit; font-size: 12px; margin: 0; padding: 1px 8px; border: 1px solid var(--line);
           border-radius: 999px; background: var(--white); color: var(--ink); cursor: pointer; }
    .ref:hover { border-color: var(--ink); }
    @media (max-width: 759px) {
      .row { grid-template-columns: 52px minmax(0, 1fr) auto; }
      .why { grid-column: 2 / -1; }
    }

    .gaps { border-left: 4px solid var(--chip-warn-fg); }
    .gaps ul { margin: 0; padding-left: 18px; display: grid; gap: 8px; font-size: 14px; color: var(--ink-soft); max-width: 80ch; }
    .gaps b { color: var(--ink); }
    @media (max-width: 599px) { .pad { padding: 16px; } }
  `
})
export class SecurityPage implements OnInit {
  readonly home = output<void>();
  protected readonly repo = REPO;
  protected readonly count = PRACTICE_COUNT;
  protected readonly minute = IN_ONE_MINUTE;
  protected readonly liveChecks = LIVE_CHECKS;
  protected readonly sections = SECTIONS;
  protected readonly gaps = GAPS;
  protected readonly owasp = [
    { title: 'OWASP Top 10 (2025): web applications', rows: OWASP_WEB },
    { title: 'OWASP Top 10 for LLM Applications (2025): the AI agent', rows: OWASP_LLM }
  ];
  protected readonly state = signal<Partial<Record<CheckId, CheckState>>>({});
  /** The practice a reference was followed to: opened, and briefly highlighted. */
  protected readonly opened = signal<string | null>(null);
  protected readonly flash = signal<string | null>(null);
  private readonly byId = new Map<string, Practice>(SECTIONS.flatMap(s => s.items).map(p => [p.id, p]));

  ngOnInit(): void {
    void this.run('reads-open', async () => {
      const r = await fetch('/api/state');
      return [r.ok, `GET /api/state → ${r.status} without a key`];
    });
    void this.run('writes-keyed', async () => {
      const r = await fetch('/api/drops', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
      return [r.status === 401, `POST /api/drops without a key → ${r.status}`];
    });
    void this.run('demo-key', async () => {
      const r = await fetch('/api/demo-key');
      return [r.status === 200 || r.status === 404,
        r.status === 200 ? 'GET /api/demo-key → 200: sharing is on, by design' : 'GET /api/demo-key → 404: sharing is off'];
    });
    void this.run('mcp-keyed', async () => {
      const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
      const init = await fetch('/mcp', { method: 'POST', headers, body: JSON.stringify({ jsonrpc: '2.0', id: 1,
        method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'security-page', version: '1' } } }) });
      const session = init.headers.get('Mcp-Session-Id') ?? '';
      const h2 = { ...headers, 'Mcp-Session-Id': session };
      await fetch('/mcp', { method: 'POST', headers: h2, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
      const call = await fetch('/mcp', { method: 'POST', headers: h2, body: JSON.stringify({ jsonrpc: '2.0', id: 2,
        method: 'tools/call', params: { name: 'start_rush', arguments: { customers: 1 } } }) });
      const text = await call.text();
      const refused = text.includes('"isError":true') && text.includes('console key');
      return [refused, refused ? 'tools/call start_rush without a key → isError: needs the console key' : 'unexpected answer'];
    });
  }

  private async run(id: CheckId, check: () => Promise<[boolean, string]>): Promise<void> {
    this.state.update(s => ({ ...s, [id]: { status: 'running', detail: 'checking…' } }));
    try {
      const [ok, detail] = await check();
      this.state.update(s => ({ ...s, [id]: { status: ok ? 'pass' : 'fail', detail } }));
    } catch (e) {
      this.state.update(s => ({ ...s, [id]: { status: 'fail', detail: (e as Error).message } }));
    }
  }

  protected titleOf(id: string): string {
    return this.byId.get(id)?.title ?? id;
  }

  protected tone(status: Coverage): string {
    return status === 'Covered' ? 'ok' : status === 'Partly' ? 'warn' : 'na';
  }

  protected tally(rows: { status: Coverage }[]): string {
    const n = (s: Coverage) => rows.filter(r => r.status === s).length;
    const na = n('Not applicable');
    return `${n('Covered')} covered · ${n('Partly')} partly` + (na ? ` · ${na} not applicable` : '');
  }

  protected show(id: string): void {
    this.opened.set(id);
    this.flash.set(id);
    document.getElementById('p-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setTimeout(() => this.flash.set(null), 1600);
  }

  protected short(path: string): string {
    return path.split('/').slice(-2).join('/');
  }
}

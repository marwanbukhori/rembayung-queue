import { Component, computed, inject, signal } from '@angular/core';
import { DemoKeyService, consoleKey } from './key';

interface ToolCard { name: string; what: string; key?: 'always' | 'raw' }
interface Preset { label: string; tool: string; args: Record<string, unknown> }
interface Frame { dir: 'sent' | 'received'; method: string; body: string; ms?: number }

/**
 * The console as an MCP server: who connects, how, what they can ask, and a
 * live call to prove it.
 *
 * The Try-a-tool panel is a real MCP client written in a few lines of fetch:
 * it opens a session on /mcp, calls a read tool and shows the JSON-RPC going
 * each way. Nothing is mocked, so what a visitor sees here is exactly what
 * Claude Code or Claude Desktop get.
 */
@Component({
  selector: 'rb-agent-mcp',
  template: `
    <div class="stack-24">
      <section class="card pad">
        <h2>Who talks to it</h2>
        <div class="flow">
          <div class="col">
            <div class="node client">Claude Code<span>claude mcp add</span></div>
            <div class="node client">Claude Desktop<span>config file</span></div>
            <div class="node agent">The run agent<span>inside the console, over loopback</span></div>
          </div>
          <div class="wire" aria-hidden="true"></div>
          <div class="col mid">
            <div class="node server">/mcp<span>rembayung-console · 10 tools · Streamable HTTP</span></div>
          </div>
          <div class="wire" aria-hidden="true"></div>
          <div class="col">
            <div class="node source">Kubernetes API<span>pods, events, autoscalers</span></div>
            <div class="node source">Prometheus<span>requests, latency, pool, replicas</span></div>
            <div class="node source">queue-gate · booking-service<span>the sitting, the queue, oversold</span></div>
          </div>
        </div>
        <p class="note">
          One tool set for everyone: the agent that writes the reports asks the same questions, through the same
          endpoint, as any MCP client you connect.
        </p>
      </section>

      <section class="card pad">
        <h2>Connect</h2>
        <div class="label">Claude Code</div>
        <pre class="code"><code>{{ command() }}</code></pre>
        @if (!key() && demoKey.shareable()) {
          <p class="note"><button class="get-key" (click)="demoKey.open()">Get the demo key</button>
            to include it, so start_rush works. Reads need nothing.</p>
        }
        <div class="label">Claude Desktop · claude_desktop_config.json</div>
        <pre class="code"><code>{{ desktop() }}</code></pre>
        <p class="note">Then ask: "Run a two-wave rush of 200 customers at 8 per second, then explain what the autoscaler did."</p>
      </section>

      <section class="card pad">
        <h2>The ten tools</h2>
        @for (group of groups; track group.title) {
          <div class="label">{{ group.title }}</div>
          <div class="tools">
            @for (t of group.tools; track t.name) {
              <div class="tool">
                <span class="tool-name mono">{{ t.name }}</span>
                @if (t.key === 'always') { <span class="needs">needs key</span> }
                @if (t.key === 'raw') { <span class="needs soft">raw lines need key</span> }
                <span class="tool-what">{{ t.what }}</span>
              </div>
            }
          </div>
        }
      </section>

      <section class="card pad">
        <h2>Try a tool</h2>
        <p class="note">This page calls the live server over MCP from your browser: a session, then one tool call.</p>
        <div class="presets" role="group" aria-label="Tool to call">
          @for (p of presets; track p.label) {
            <button class="preset" [class.on]="p === preset()" (click)="preset.set(p)">{{ p.label }}</button>
          }
        </div>
        <button class="run" [disabled]="busy()" (click)="tryIt()">{{ busy() ? 'Calling…' : 'Run ' + preset().tool }}</button>
        @if (frames().length) {
          <ol class="frames">
            @for (f of frames(); track $index) {
              <li [class]="f.dir">
                <div class="frame-head mono">
                  <span>{{ f.dir === 'sent' ? '→' : '←' }} {{ f.method }}</span>
                  @if (f.ms !== undefined) { <span class="ms">{{ f.ms }} ms</span> }
                </div>
                <pre class="code small"><code>{{ f.body }}</code></pre>
              </li>
            }
          </ol>
        }
        @if (failure()) { <p class="pill bad">{{ failure() }}</p> }
      </section>
    </div>
  `,
  styles: `
    .pad { padding: 20px 24px; }
    h2 { font-size: 19px; margin: 0 0 12px; }
    .note { font-size: 14px; color: var(--ink-soft); margin: 10px 0 0; text-wrap: pretty; }
    .mono { font-family: var(--mono); }
    .label { font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); margin: 14px 0 6px; }
    .flow { display: grid; grid-template-columns: minmax(0, 1fr) 40px minmax(0, 1fr) 40px minmax(0, 1fr); align-items: center; }
    .col { display: grid; gap: 10px; }
    .node { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border-radius: 8px; font-weight: 700;
            font-size: 14px; border: 1px solid var(--line); background: var(--white); }
    .node span { font-weight: 400; font-size: 12px; color: var(--ink-soft); }
    .node.client { border-color: var(--ink); }
    .node.agent { border-color: var(--chip-info-fg); background: var(--chip-info-bg); }
    .node.server { border: 2px solid var(--dhl-red); padding: 18px 14px; font-family: var(--mono); font-size: 18px; }
    .node.source { background: var(--canvas); }
    .wire { height: 2px; background: var(--muted); position: relative; }
    .wire::after { content: ''; position: absolute; right: -1px; top: -4px; border: 5px solid transparent; border-left-color: var(--muted); }
    .code { margin: 0; background: #1d1d1d; color: #d7e6df; border-radius: 6px; padding: 10px 12px; font-size: 12px;
            font-family: var(--mono); white-space: pre-wrap; overflow-wrap: anywhere; }
    .code.small { max-height: 260px; overflow: auto; font-size: 11px; }
    .get-key { font: inherit; font-size: 13px; font-weight: 700; background: var(--dhl-red); color: #fff; border: 0;
               border-radius: 4px; padding: 4px 10px; cursor: pointer; }
    .tools { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
    .tool { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 10px 12px; border: 1px solid var(--line);
            border-radius: 8px; background: var(--white); }
    .tool-name { font-size: 13px; font-weight: 700; }
    .tool-what { flex-basis: 100%; font-size: 13px; color: var(--ink-soft); }
    .needs { font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 999px; background: var(--chip-bad-bg); color: var(--chip-bad-fg); }
    .needs.soft { background: var(--chip-warn-bg); color: var(--chip-warn-fg); }
    .presets { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
    .preset { font: inherit; font-size: 13px; border: 1px solid var(--line); background: var(--white); color: var(--ink);
              border-radius: 999px; padding: 4px 12px; cursor: pointer; }
    .preset.on { border-color: var(--ink); font-weight: 700; }
    .run { font: inherit; font-weight: 700; background: var(--ink); color: var(--white); border: 0; border-radius: 4px;
           padding: 8px 16px; cursor: pointer; }
    .run:disabled { opacity: .6; cursor: wait; }
    .frames { list-style: none; margin: 14px 0 0; padding: 0; display: grid; gap: 8px; }
    .frame-head { display: flex; justify-content: space-between; font-size: 12px; margin-bottom: 4px; }
    .frames .sent .frame-head { color: var(--chip-info-fg); }
    .frames .received .frame-head { color: var(--chip-ok-fg); }
    .ms { color: var(--muted); }
    @media (max-width: 799px) {
      .flow { grid-template-columns: minmax(0, 1fr); gap: 8px; }
      .wire { width: 2px; height: 18px; justify-self: center; }
      .wire::after { right: -4px; top: auto; bottom: -6px; border-left-color: transparent; border-top-color: var(--muted); }
    }
    @media (max-width: 599px) { .pad { padding: 16px; } }
  `
})
export class AgentMcp {
  protected readonly demoKey = inject(DemoKeyService);
  protected readonly key = computed(() => consoleKey());
  protected readonly endpoint = `${window.location.origin}/mcp`;
  protected readonly command = computed(() => `claude mcp add --transport http rembayung ${this.endpoint}`
    + (this.key() ? ` --header "X-Console-Key: ${this.key()}"` : ''));
  protected readonly desktop = computed(() => JSON.stringify({ mcpServers: { rembayung: {
    type: 'http', url: this.endpoint, ...(this.key() ? { headers: { 'X-Console-Key': this.key() } } : {}) } } }, null, 2));

  protected readonly groups: { title: string; tools: ToolCard[] }[] = [
    { title: 'State and reports', tools: [
      { name: 'get_state', what: 'Seats, the queue, oversold and admit rate; pods and the CPU budget.' },
      { name: 'list_runs', what: 'Every rush the agent analysed, with its headline numbers.' },
      { name: 'get_report', what: 'One run in full: facts, sections, the trail.', key: 'raw' },
      { name: 'describe_object', what: 'What the inspector shows for any object.' }
    ] },
    { title: 'Cluster', tools: [
      { name: 'metric', what: 'Requests, latency, pool or replicas over a window.' },
      { name: 'events', what: 'Kubernetes events for one object.' },
      { name: 'pod_status', what: 'Phase, readiness, restarts, node.' },
      { name: 'endpoints', what: 'The pods ready behind a Service.' },
      { name: 'pod_logs', what: 'App events; raw lines with the key.', key: 'raw' }
    ] },
    { title: 'Action', tools: [
      { name: 'start_rush', what: 'Start a one- or two-wave rush on a fresh sitting.', key: 'always' }
    ] }
  ];

  protected readonly presets: Preset[] = [
    { label: 'The sitting now', tool: 'get_state', args: {} },
    { label: 'Analysed runs', tool: 'list_runs', args: {} },
    { label: 'queue-gate Deployment', tool: 'describe_object', args: { kind: 'deployment', name: 'queue-gate' } },
    { label: 'Replicas, 15 min', tool: 'metric', args: { chart: 'replicas' } }
  ];
  protected readonly preset = signal<Preset>(this.presets[0]);
  protected readonly frames = signal<Frame[]>([]);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected async tryIt(): Promise<void> {
    const p = this.preset();
    this.busy.set(true);
    this.failure.set(null);
    this.frames.set([]);
    try {
      const init = { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18',
        capabilities: {}, clientInfo: { name: 'rembayung-page', version: '1' } } };
      const opened = await this.post(init, null);
      const session = opened.session;
      await this.post({ jsonrpc: '2.0', method: 'notifications/initialized' }, session, true);
      const call = { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: p.tool, arguments: p.args } };
      await this.post(call, session);
    } catch (e) {
      this.failure.set(`The call failed: ${(e as Error).message}`);
    } finally {
      this.busy.set(false);
    }
  }

  /** One JSON-RPC exchange, recorded both ways. Answers may come as JSON or as one SSE event. */
  private async post(body: Record<string, unknown>, session: string | null, quiet = false)
      : Promise<{ session: string | null }> {
    const method = String(body['method']);
    if (!quiet) {
      this.frames.update(f => [...f, { dir: 'sent', method, body: JSON.stringify(body, null, 2) }]);
    }
    const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
    if (session) {
      headers['Mcp-Session-Id'] = session;
    }
    const started = performance.now();
    const res = await fetch('/mcp', { method: 'POST', headers, body: JSON.stringify(body) });
    const ms = Math.round(performance.now() - started);
    if (!res.ok && res.status !== 202) {
      throw new Error(`HTTP ${res.status}`);
    }
    const text = await res.text();
    if (!quiet && text) {
      const data = text.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5)).pop() ?? text;
      let shown = data;
      try {
        const parsed = JSON.parse(data);
        const inner = parsed?.result?.content?.[0]?.text;
        if (inner) {
          try { parsed.result.content[0].text = JSON.parse(inner); } catch { /* plain text result */ }
        }
        shown = JSON.stringify(parsed, null, 2);
      } catch { /* not JSON; show as is */ }
      this.frames.update(f => [...f, { dir: 'received', method, body: shown.length > 6000 ? shown.slice(0, 6000) + '\n…' : shown, ms }]);
    }
    return { session: res.headers.get('Mcp-Session-Id') ?? session };
  }
}

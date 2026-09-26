import { Component, computed, inject } from '@angular/core';
import { DemoKeyService, consoleKey } from './key';

/**
 * How to use the console from Claude: the MCP endpoint, the one command that
 * adds it to Claude Code, and the same for Claude Desktop.
 *
 * The key is filled in when the visitor has it, because start_rush and raw
 * logs need it and a command that silently lacks it would look broken. Reads
 * work without it, like everywhere else on this site.
 */
@Component({
  selector: 'rb-use-from-claude',
  template: `
    <section class="card pad">
      <h2>Use it from Claude</h2>
      <p class="note">
        The console is also an MCP server. Add it to Claude Code or Claude Desktop and ask about the live
        system in plain words - the same tools the run agent uses. Reads need nothing; starting a rush and
        raw logs need the console key, sent as a header.
      </p>
      <div class="label">Endpoint</div>
      <pre class="code"><code>{{ endpoint() }}</code></pre>
      <div class="label">Claude Code</div>
      <pre class="code"><code>{{ command() }}</code></pre>
      @if (!key() && demoKey.shareable()) {
        <p class="note">
          <button class="get-key" (click)="demoKey.open()">Get the demo key</button>
          to include it, so start_rush works.
        </p>
      }
      <div class="label">Claude Desktop (claude_desktop_config.json)</div>
      <pre class="code"><code>{{ desktop() }}</code></pre>
      <div class="label">Tools</div>
      <ul class="tools">
        @for (t of tools; track t.name) {
          <li><span class="mono">{{ t.name }}</span> {{ t.what }}</li>
        }
      </ul>
      <p class="note">Try: "Run a two-wave rush of 200 customers at 8 per second, then explain what the autoscaler did."</p>
    </section>
  `,
  styles: `
    .pad { padding: 20px 24px; }
    h2 { font-size: 19px; margin: 0 0 10px; }
    .note { font-size: 14px; color: var(--ink-soft); margin: 0 0 10px; text-wrap: pretty; }
    .label { font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); margin: 12px 0 4px; }
    .code { margin: 0; background: #1d1d1d; color: #d7e6df; border-radius: 6px; padding: 10px 12px; font-size: 12px;
            font-family: var(--mono); white-space: pre-wrap; overflow-wrap: anywhere; }
    .tools { margin: 0; padding-left: 18px; font-size: 14px; display: grid; gap: 4px; color: var(--ink-soft); }
    .mono { font-family: var(--mono); color: var(--ink); font-size: 13px; }
    .get-key { font: inherit; font-size: 13px; font-weight: 700; background: var(--dhl-red); color: #fff; border: 0;
               border-radius: 4px; padding: 4px 10px; cursor: pointer; }
    @media (max-width: 599px) { .pad { padding: 16px; } }
  `
})
export class UseFromClaude {
  protected readonly demoKey = inject(DemoKeyService);
  protected readonly key = computed(() => consoleKey());
  protected readonly endpoint = computed(() => `${window.location.origin}/mcp`);
  protected readonly command = computed(() => {
    const k = this.key();
    return `claude mcp add --transport http rembayung ${this.endpoint()}` + (k ? ` --header "X-Console-Key: ${k}"` : '');
  });
  protected readonly desktop = computed(() => JSON.stringify({
    mcpServers: {
      rembayung: {
        type: 'http',
        url: this.endpoint(),
        ...(this.key() ? { headers: { 'X-Console-Key': this.key() } } : {})
      }
    }
  }, null, 2));

  protected readonly tools = [
    { name: 'get_state', what: 'seats, the queue, oversold, pods and the CPU budget' },
    { name: 'list_runs', what: 'every rush the agent analysed' },
    { name: 'get_report', what: 'one run in full: facts, sections, trail' },
    { name: 'describe_object', what: 'what the inspector shows for any object' },
    { name: 'metric', what: 'requests, latency, pool or replicas over a window' },
    { name: 'events', what: 'Kubernetes events for one object' },
    { name: 'pod_status', what: 'phase, readiness, restarts, node' },
    { name: 'endpoints', what: 'the pods ready behind a Service' },
    { name: 'pod_logs', what: 'app events, or raw lines with the key' },
    { name: 'start_rush', what: 'start a one- or two-wave rush (needs the key)' }
  ];
}

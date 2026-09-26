import { Component, OnInit, output, signal } from '@angular/core';

interface Practice { title: string; why: string; evidence: string; file?: string; check?: CheckId }
interface Group { title: string; lede: string; items: Practice[] }
type CheckId = 'reads-open' | 'writes-keyed' | 'demo-key' | 'mcp-keyed';
type CheckState = { status: 'running' | 'pass' | 'fail'; detail: string };

const REPO = 'https://github.com/marwanbukhori/rembayung-queue/blob/main/';

/**
 * Every security practice in the project, with the evidence for each: the file
 * that does it, and for four of them a check this page runs live from the
 * visitor's browser against the real console.
 *
 * It ends with what is deliberately not done, because a list with no gaps is
 * a list nobody should trust - and naming the trade-offs is itself the practice.
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
          What protects this system, from the AI agent to the pipeline that ships it. Each practice names the file that
          does it; four are checked live from your browser as this page loads. The gaps are at the end.
        </p>
      </div>

      <section class="card pad">
        <h2>Checked just now, from your browser</h2>
        <div class="checks">
          @for (c of liveChecks; track c.id) {
            @let s = state()[c.id];
            <div class="check" [class]="'check ' + (s?.status ?? 'running')">
              <span class="mark">{{ s?.status === 'pass' ? '✓' : s?.status === 'fail' ? '✕' : '…' }}</span>
              <span class="check-body">
                <span class="check-title">{{ c.title }}</span>
                <span class="check-detail mono">{{ s?.detail ?? 'checking…' }}</span>
              </span>
            </div>
          }
        </div>
      </section>

      @for (g of groups; track g.title) {
        <section class="card pad">
          <h2>{{ g.title }}</h2>
          <p class="note">{{ g.lede }}</p>
          <div class="practices">
            @for (p of g.items; track p.title) {
              <article class="practice">
                <h3>{{ p.title }}
                  @if (p.check && state()[p.check]?.status === 'pass') { <span class="live">✓ checked live</span> }
                </h3>
                <p class="why">{{ p.why }}</p>
                <p class="evidence">{{ p.evidence }}
                  @if (p.file) { <a class="mono" [href]="repo + p.file" target="_blank" rel="noopener">{{ short(p.file) }}</a> }
                </p>
              </article>
            }
          </div>
        </section>
      }

      <section class="card pad gaps">
        <h2>Known gaps, stated</h2>
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
    .pad { padding: 20px 24px; }
    h2 { font-size: 19px; margin: 0 0 6px; }
    .note { font-size: 14px; color: var(--ink-soft); margin: 0 0 14px; text-wrap: pretty; }
    .mono { font-family: var(--mono); }
    .checks { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 10px; margin-top: 10px; }
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
    .practices { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .practice { border: 1px solid var(--line); border-radius: 8px; padding: 12px 14px; background: var(--white);
                display: flex; flex-direction: column; gap: 6px; }
    h3 { font-size: 15px; margin: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
    .live { font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 999px; background: var(--chip-ok-bg); color: var(--chip-ok-fg); }
    .why { margin: 0; font-size: 14px; color: var(--ink-soft); text-wrap: pretty; }
    .evidence { margin: 0; font-size: 13px; color: var(--ink); }
    .evidence a { font-size: 12px; margin-left: 4px; overflow-wrap: anywhere; }
    .gaps { border-left: 4px solid var(--chip-warn-fg); }
    .gaps ul { margin: 8px 0 0; padding-left: 18px; display: grid; gap: 8px; font-size: 14px; color: var(--ink-soft); }
    .gaps b { color: var(--ink); }
    @media (max-width: 599px) { .pad { padding: 16px; } }
  `
})
export class SecurityPage implements OnInit {
  readonly home = output<void>();
  protected readonly repo = REPO;
  protected readonly state = signal<Partial<Record<CheckId, CheckState>>>({});

  protected readonly liveChecks: { id: CheckId; title: string }[] = [
    { id: 'reads-open', title: 'Reads are open' },
    { id: 'writes-keyed', title: 'A write without the key is refused' },
    { id: 'demo-key', title: 'The demo key is shared on purpose' },
    { id: 'mcp-keyed', title: 'MCP start_rush needs the key' }
  ];

  protected readonly groups: Group[] = [
    {
      title: 'AI and the run agent',
      lede: 'An 8B model writes fluent text whether or not it is right, so the agent is bounded, checked and read-only.',
      items: [
        { title: 'Bounded on every side', why: 'A looping or slow model cannot run away with the shared model or the run.',
          evidence: 'At most 5 tool calls, 60 s a call, 3 minutes a run, one retry.', file: 'console/src/main/java/dev/marwan/console/agent/Analyst.java' },
        { title: 'Every number must come from a cited fact', why: 'The model cannot put a number in front of a reader that the run did not produce.',
          evidence: 'Claims that state a number no cited fact contains are rejected; two rejections and the report is built from the facts alone.',
          file: 'console/src/main/java/dev/marwan/console/agent/Validator.java' },
        { title: 'A report even when the model fails', why: 'Fail-safe, not fail-open: a timeout or bad JSON never costs a run its report.',
          evidence: 'A rules-built fallback, labelled as such.', file: 'console/src/main/java/dev/marwan/console/agent/Fallback.java' },
        { title: 'Read-only tools, through MCP', why: 'The agent can look but not change anything.',
          evidence: 'Its five tools only read; each answer is bounded (40 lines, 30 points, 20 events).',
          file: 'console/src/main/java/dev/marwan/console/agent/Tools.java' },
        { title: 'The agent proposes, a human approves', why: 'During an incident the AI diagnoses and suggests a fix, but cannot apply one.',
          evidence: 'Fixes come from a fixed menu of four; there is no MCP tool that approves. Only a key holder\'s click in the console applies a fix, through narrow, named grants applied by hand.',
          file: 'console/src/main/java/dev/marwan/console/incident/Remediation.java' },
        { title: 'Drills that end themselves', why: 'A chaos drill must not leave the system broken if the console dies mid-drill.',
          evidence: 'One fault at a time behind a lock in a ConfigMap; booking-service reverts its own fault at the deadline, at most 2 minutes.',
          file: 'booking-service/src/main/java/dev/marwan/booking/chaos/ChaosState.java' },
        { title: 'Personal data masked before the model sees it', why: 'Customer phone numbers never reach the model, the trail or a stored report.',
          evidence: 'Every log line, tool answer and trail step passes through the same masker.', file: 'console/src/main/java/dev/marwan/console/objects/LogLines.java' },
        { title: 'Its credential goes to one place only', why: 'The model is a shared service; the console\'s token must not leak elsewhere.',
          evidence: 'The ServiceAccount token is sent only to an https *.svc.cluster.local URL, re-read per call, and redirects are never followed.',
          file: 'console/src/main/java/dev/marwan/console/agent/OpenAiModel.java' }
      ]
    },
    {
      title: 'Web, API and MCP',
      lede: 'One rule everywhere: reading is open, changing anything needs the console key.',
      items: [
        { title: 'Reads open, writes keyed', why: 'A visitor can see everything; only a key holder can start load or change the system.',
          evidence: 'Every GET passes; every other method needs the key.', file: 'console/src/main/java/dev/marwan/console/auth/KeyFilter.java', check: 'writes-keyed' },
        { title: 'Constant-time key comparison', why: 'The key cannot be guessed a byte at a time from response timing.',
          evidence: 'MessageDigest.isEqual over every byte.', file: 'console/src/main/java/dev/marwan/console/auth/AccessKey.java' },
        { title: 'The key kept out of URLs and storage that lasts', why: 'URLs end up in logs and history.',
          evidence: 'Sent as the X-Console-Key header; kept in sessionStorage, which ends with the tab.', file: 'console/ui/src/app/key.ts' },
        { title: 'Raw logs for key holders only', why: 'Stack traces and errors can say more than a stranger should read.',
          evidence: 'Without the key: structured app events only, and raw log lines are redacted from stored reports.',
          file: 'console/src/main/java/dev/marwan/console/objects/PodLogs.java' },
        { title: 'MCP enforces the same rules', why: 'A second door must not have a weaker lock.',
          evidence: 'Each tool checks the key itself; start_rush and raw logs refuse without it, with a sentence saying why.',
          file: 'console/src/main/java/dev/marwan/console/mcp/McpTools.java', check: 'mcp-keyed' },
        { title: 'Nothing reflected, nothing leaked', why: 'Error pages are a classic injection and disclosure point.',
          evidence: 'A reflected-input 404 body was closed; error bodies return fixed codes, never the key or a stack trace.',
          file: 'console/src/main/java/dev/marwan/console/objects/ObjectsController.java' }
      ]
    },
    {
      title: 'Platform',
      lede: 'OpenShift\'s defaults kept, and least privilege added on top.',
      items: [
        { title: 'Only two doors to the internet', why: 'Everything not exposed cannot be attacked from outside.',
          evidence: 'Routes exist for the console and queue-gate only; booking-service, Redis and every /internal path are never routed.',
          file: 'deploy/base/queue-gate/route.yaml' },
        { title: 'Network policies between services', why: 'A compromised pod cannot reach the database service or Redis directly.',
          evidence: 'booking-service and Redis accept traffic from queue-gate (and the console for reads) only.', file: 'deploy/base/networkpolicy.yaml' },
        { title: 'Non-root, no privileges', why: 'A container escape starts with as little as possible.',
          evidence: 'Pods run under OpenShift\'s restricted-v2 SCC: an arbitrary non-root UID, all capabilities dropped, no privilege escalation (read from the live pod).' },
        { title: 'Least-privilege service account', why: 'The console reads the cluster on a visitor\'s behalf, so what it can do is what a visitor can reach.',
          evidence: 'Scoped to this namespace, no access to Secrets, write access only to load Jobs and the ConfigMaps it owns.',
          file: 'deploy/base/console/rbac.yaml' },
        { title: 'Secrets stay Secrets', why: 'Credentials are not in git, images or logs.',
          evidence: 'The Oracle password and wallet, and the console key, come from Kubernetes Secrets, created by hand.',
          file: 'deploy/base/booking-service/deployment.yaml' },
        { title: 'A fixed budget', why: 'No run, however large, can take the namespace down.',
          evidence: 'CPU and memory quotas (3 CPUs, 30Gi) and one live load run per sitting.' }
      ]
    },
    {
      title: 'CI/CD and supply chain',
      lede: 'The pipeline can ship code, but cannot widen its own access or ship something it did not test.',
      items: [
        { title: 'No stored registry password', why: 'Nothing long-lived to leak or rotate.',
          evidence: 'Images are pushed with the job\'s own short-lived GITHUB_TOKEN.', file: '.github/workflows/ci.yml' },
        { title: 'Immutable tags', why: 'What is running can always be named and rolled back to.',
          evidence: 'Every image is tagged with its full commit SHA, never latest.', file: '.github/workflows/ci.yml' },
        { title: 'CD cannot grant itself rights', why: 'A leaked deploy token must not become cluster admin.',
          evidence: 'CD\'s ServiceAccount cannot read Secrets or change RBAC; permissions are applied by hand.',
          file: 'deploy/openshift/cd-serviceaccount.yaml' },
        { title: 'Scoped secrets', why: 'The cluster token is available only to the deploy job.',
          evidence: 'Stored in a GitHub Environment, not as a bare repository secret, and never echoed.', file: '.github/workflows/cd.yml' },
        { title: 'Automatic rollback and drift checks', why: 'A bad deploy does not stay live, and the cluster cannot silently differ from git.',
          evidence: 'CD restores each service\'s previous tag on failure; check-drift.sh diffs every kind, RBAC included.',
          file: 'deploy/scripts/check-drift.sh' },
        { title: 'No secrets in published logs', why: 'The CI/CD page shows real logs, so they must be clean.',
          evidence: 'The capture script refuses to write if any line looks like a token or the console key.', file: 'deploy/scripts/capture-cicd-runs.py' }
      ]
    },
    {
      title: 'Data integrity',
      lede: 'The one promise the system makes - no seat sold twice - is enforced by the database, not just the code.',
      items: [
        { title: 'Overselling is impossible to persist', why: 'A bug in any service cannot write an oversold sitting.',
          evidence: 'A CHECK constraint on the slot: seats taken never exceed capacity.', file: 'booking-service/src/main/resources/db/migration' },
        { title: 'Idempotent bookings', why: 'A retry or a double click books once.',
          evidence: 'Each booking carries an idempotency key, unique in the database.', file: 'booking-service/src/main/java/dev/marwan/booking/service/BookingService.java' },
        { title: 'Admission enforced on the server', why: 'A client cannot book without being let through the queue.',
          evidence: 'queue-gate checks the admission token before forwarding a booking.', file: 'queue-gate/src/main/java' }
      ]
    }
  ];

  protected readonly gaps = [
    { title: 'The demo key is public', text: 'Deliberately, so a visitor can start a rush. The guards that stand in for it are one live run at a time, the CPU quota and sandboxes that expire.' },
    { title: 'No rate limiting yet', text: 'Nothing limits how often a key holder or an MCP client may start rushes beyond one at a time.' },
    { title: 'Readiness does not check the database', text: 'Taken out so a slow Oracle cannot pull every pod from the Service at once; the deploy smoke test is therefore weaker, and says so.' },
    { title: 'One shared key, no identities', text: 'There are no user accounts or per-client keys, so actions cannot be attributed to a person.' }
  ];

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

  protected short(path: string): string {
    return path.split('/').slice(-2).join('/');
  }
}

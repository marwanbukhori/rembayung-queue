/**
 * What the Security page says, kept apart from how it is drawn.
 *
 * Every practice is written twice: a plain headline and the harm it prevents,
 * for someone skimming, and the technical "how" with the file that does it,
 * for someone checking. The OWASP tables point back at practices by id, so a
 * claim of coverage is always one click from its evidence.
 */

export type CheckId = 'reads-open' | 'writes-keyed' | 'demo-key' | 'mcp-keyed';

export interface Practice {
  id: string;
  /** Plain words: what is true. */
  title: string;
  /** Plain words: what it protects against. */
  protects: string;
  /** The technical detail, for anyone who opens it. */
  how: string;
  file?: string;
  check?: CheckId;
}

export interface Section { question: string; answer: string; items: Practice[] }

export type Coverage = 'Covered' | 'Partly' | 'Not applicable';

export interface OwaspRow { code: string; name: string; plain: string; status: Coverage; why: string; refs: string[] }

export const REPO = 'https://github.com/marwanbukhori/rembayung-queue/blob/main/';

export const IN_ONE_MINUTE: { lead: string; text: string }[] = [
  { lead: 'The AI can look, never touch.',
    text: 'It reads the cluster through read-only tools. When it suggests a fix, a person has to approve it.' },
  { lead: 'Anyone can watch; only a key holder can act.',
    text: 'Every page is open. Starting load, breaking things on purpose or approving a fix needs the console key.' },
  { lead: 'A seat cannot be sold twice.',
    text: 'The database itself refuses it, so even a bug in the code cannot oversell a sitting.' },
  { lead: 'A bad release does not stay live.',
    text: 'Every deploy is a named, tested image, and a failed one is rolled back automatically.' },
  { lead: 'The gaps are written down.',
    text: 'What is not done yet is listed at the bottom, with the reason.' }
];

export const LIVE_CHECKS: { id: CheckId; title: string }[] = [
  { id: 'reads-open', title: 'Anyone can look' },
  { id: 'writes-keyed', title: 'Changing anything needs the key' },
  { id: 'demo-key', title: 'The demo key is handed out on purpose' },
  { id: 'mcp-keyed', title: 'The AI tool door has the same lock' }
];

export const SECTIONS: Section[] = [
  {
    question: 'Can the AI do damage?',
    answer: 'No. It can read and it can suggest; it cannot change anything, and what it writes is checked against the facts.',
    items: [
      { id: 'ai-read-only', title: 'It can only read', protects: 'An AI that decides to "fix" things on its own.',
        how: 'Its tools only read, through MCP, and each answer is bounded (40 lines, 30 points, 20 events). Write tools are not on its allowlist.',
        file: 'console/src/main/java/dev/marwan/console/agent/Tools.java' },
      { id: 'ai-human-approves', title: 'A person approves every fix', protects: 'A wrong diagnosis turning into a wrong change.',
        how: 'Fixes come from a fixed menu of four, with replicas held between 2 and 4. No MCP tool approves; only a key holder\'s click does, through narrow grants applied by hand.',
        file: 'console/src/main/java/dev/marwan/console/incident/Remediation.java' },
      { id: 'ai-cited-numbers', title: 'Every number it states is checked', protects: 'A confident report with made-up numbers.',
        how: 'A claim that states a number no cited fact contains is rejected. After two rejections the report is built from the facts alone.',
        file: 'console/src/main/java/dev/marwan/console/agent/Validator.java' },
      { id: 'ai-fallback', title: 'A report even when the model fails', protects: 'Losing the record of a run because the model timed out.',
        how: 'A rules-built report, labelled as such, replaces the model\'s whenever it fails or is refused.',
        file: 'console/src/main/java/dev/marwan/console/agent/Fallback.java' },
      { id: 'ai-bounded', title: 'It cannot run up the bill', protects: 'A looping model hogging a shared service.',
        how: 'At most 5 tool calls, 60 s a call, 3 minutes a run and one retry; an incident gets 3 calls a cycle and 10 cycles.',
        file: 'console/src/main/java/dev/marwan/console/agent/Analyst.java' },
      { id: 'ai-masking', title: 'Customer details never reach it', protects: 'Phone numbers leaking into a model, a trail or a stored report.',
        how: 'Every log line, tool answer and trail step passes through the same masker first.',
        file: 'console/src/main/java/dev/marwan/console/objects/LogLines.java' },
      { id: 'ai-credential', title: 'Its password goes to one place only', protects: 'The console\'s cluster token being sent somewhere it should not go.',
        how: 'The ServiceAccount token is sent only to an https *.svc.cluster.local address, re-read per call, and redirects are never followed.',
        file: 'console/src/main/java/dev/marwan/console/agent/OpenAiModel.java' },
      { id: 'ai-drills', title: 'Drills clean up after themselves', protects: 'A practice outage becoming a real one.',
        how: 'One fault at a time, only during a rush, behind a lock in a ConfigMap. booking-service undoes its own fault within 2 minutes, even if the console dies.',
        file: 'booking-service/src/main/java/dev/marwan/booking/chaos/ChaosState.java' }
    ]
  },
  {
    question: 'Can a stranger change anything?',
    answer: 'They can see everything and change nothing. One rule holds on every door: reading is open, acting needs the key.',
    items: [
      { id: 'web-keyed', title: 'Looking is free; acting needs the key', protects: 'A passer-by starting load or breaking the system.',
        how: 'Every GET passes; every other method needs the X-Console-Key header.',
        file: 'console/src/main/java/dev/marwan/console/auth/KeyFilter.java', check: 'writes-keyed' },
      { id: 'web-mcp', title: 'The AI tool door has the same lock', protects: 'A second way in with a weaker lock.',
        how: 'Each MCP tool checks the key itself; start_rush, inject_fault and raw logs refuse without it, saying why.',
        file: 'console/src/main/java/dev/marwan/console/mcp/McpTools.java', check: 'mcp-keyed' },
      { id: 'web-timing', title: 'The key cannot be guessed bit by bit', protects: 'Working out the key from how fast wrong guesses fail.',
        how: 'Keys are compared in constant time (MessageDigest.isEqual over every byte).',
        file: 'console/src/main/java/dev/marwan/console/auth/AccessKey.java' },
      { id: 'web-no-urls', title: 'The key never appears in a link', protects: 'The key turning up in browser history or server logs.',
        how: 'Sent as a header and kept in sessionStorage, which ends with the tab.',
        file: 'console/ui/src/app/key.ts' },
      { id: 'web-raw-logs', title: 'Raw logs are for key holders', protects: 'Error details telling a stranger how the system works inside.',
        how: 'Without the key: structured events only. Raw lines are redacted from stored reports.',
        file: 'console/src/main/java/dev/marwan/console/objects/PodLogs.java' },
      { id: 'web-errors', title: 'Errors give nothing away', protects: 'Error pages used to inject content or leak internals.',
        how: 'Error bodies are fixed codes; input is never echoed back, and no stack trace or key is ever returned.',
        file: 'console/src/main/java/dev/marwan/console/objects/ObjectsController.java' },
      { id: 'web-https', title: 'Everything travels encrypted', protects: 'Someone on the network reading or altering traffic.',
        how: 'Both public routes terminate TLS at the OpenShift router and redirect plain HTTP; the database is reached over TLS with an Oracle wallet.',
        file: 'deploy/base/console/route.yaml' }
    ]
  },
  {
    question: 'What if something inside is compromised?',
    answer: 'It gets very little: few doors, walls between services, no root, and only the permissions each part needs.',
    items: [
      { id: 'plat-doors', title: 'Only two doors to the internet', protects: 'Attacks on services nobody outside needs to reach.',
        how: 'Routes exist for the console and queue-gate only; booking-service, Redis and every /internal path are never exposed.',
        file: 'deploy/base/queue-gate/route.yaml' },
      { id: 'plat-netpol', title: 'Walls between services', protects: 'One broken pod reaching the database service or Redis.',
        how: 'Network policies: booking-service and Redis accept traffic from queue-gate (and the console for reads) only.',
        file: 'deploy/base/networkpolicy.yaml' },
      { id: 'plat-non-root', title: 'Nothing runs as root', protects: 'A container escape starting with full power.',
        how: 'OpenShift\'s restricted-v2 policy: an arbitrary non-root user, every capability dropped, no privilege escalation.' },
      { id: 'plat-rbac', title: 'The console can do only what it needs', protects: 'A compromised console taking over the namespace.',
        how: 'Its ServiceAccount is scoped to this namespace, cannot read Secrets, and can write only load Jobs and its own ConfigMaps.',
        file: 'deploy/base/console/rbac.yaml' },
      { id: 'plat-secrets', title: 'Passwords stay out of code', protects: 'Credentials leaking through git, images or logs.',
        how: 'The Oracle password and wallet, and the console key, come from Kubernetes Secrets created by hand.',
        file: 'deploy/base/booking-service/deployment.yaml' },
      { id: 'plat-budget', title: 'A fixed budget', protects: 'One huge run taking the whole namespace down.',
        how: 'CPU and memory quotas (3 CPUs, 30Gi) and one live load run per sitting.' }
    ]
  },
  {
    question: 'Can a bad release reach you, or stay live?',
    answer: 'Every release is a tested, named image; the pipeline cannot give itself more power; a failed deploy is undone.',
    items: [
      { id: 'ci-tags', title: 'Every release has a name', protects: 'Not knowing what is running, or having nothing to roll back to.',
        how: 'Every image is tagged with its full commit SHA, never "latest". The badge at the top of each page shows it.',
        file: '.github/workflows/ci.yml' },
      { id: 'ci-token', title: 'No stored registry password', protects: 'A long-lived password leaking.',
        how: 'Images are pushed with the job\'s own short-lived GITHUB_TOKEN.', file: '.github/workflows/ci.yml' },
      { id: 'ci-no-escalation', title: 'The pipeline cannot give itself power', protects: 'A leaked deploy token becoming cluster admin.',
        how: 'CD\'s ServiceAccount cannot read Secrets or change permissions; permissions are applied by hand.',
        file: 'deploy/openshift/cd-serviceaccount.yaml' },
      { id: 'ci-scoped', title: 'The deploy key opens one job', protects: 'Other workflows using the cluster token.',
        how: 'Kept in a GitHub Environment, not a bare repository secret, and never echoed.', file: '.github/workflows/cd.yml' },
      { id: 'ci-rollback', title: 'A failed deploy is undone', protects: 'A broken release staying live, or the cluster quietly drifting from git.',
        how: 'CD restores each service\'s previous tag on failure; check-drift.sh compares every kind of object, permissions included.',
        file: 'deploy/scripts/check-drift.sh' },
      { id: 'ci-clean-logs', title: 'Published logs are clean', protects: 'A secret leaking through the CI/CD page.',
        how: 'The capture script refuses to write if any line looks like a token or the console key.',
        file: 'deploy/scripts/capture-cicd-runs.py' }
    ]
  },
  {
    question: 'Can a seat be sold twice?',
    answer: 'No. The one promise this system makes is enforced by the database, not only by the code.',
    items: [
      { id: 'data-check', title: 'The database refuses overselling', protects: 'A bug in any service writing more bookings than seats.',
        how: 'A CHECK constraint on the slot: seats taken can never exceed capacity.',
        file: 'booking-service/src/main/resources/db/migration' },
      { id: 'data-idempotent', title: 'A double click books once', protects: 'Retries and impatient clicks creating duplicate bookings.',
        how: 'Each booking carries an idempotency key that is unique in the database.',
        file: 'booking-service/src/main/java/dev/marwan/booking/service/BookingService.java' },
      { id: 'data-admission', title: 'No skipping the queue', protects: 'A client booking without being let through.',
        how: 'queue-gate checks the admission token on the server before forwarding a booking.', file: 'queue-gate/src/main/java' },
      { id: 'data-sql', title: 'No SQL built from input', protects: 'SQL injection.',
        how: 'Every query uses bind parameters through Spring Data; no query is assembled from request text.',
        file: 'booking-service/src/main/java/dev/marwan/booking/repository/BookingRepository.java' }
    ]
  }
];

/** OWASP Top 10:2025 (https://owasp.org/Top10/2025/). */
export const OWASP_WEB: OwaspRow[] = [
  { code: 'A01', name: 'Broken Access Control', plain: 'People doing what they should not be allowed to', status: 'Covered',
    why: 'Acting needs the key on every door, and each service account has only what it needs.', refs: ['web-keyed', 'web-mcp', 'plat-rbac', 'plat-netpol'] },
  { code: 'A02', name: 'Security Misconfiguration', plain: 'Unsafe settings left on', status: 'Covered',
    why: 'Only two routes, no root, fixed error bodies, and drift from git is checked.', refs: ['plat-doors', 'plat-non-root', 'web-errors', 'ci-rollback'] },
  { code: 'A03', name: 'Software Supply Chain Failures', plain: 'Trusting code or images you did not check', status: 'Partly',
    why: 'Images are named by commit and pushed without stored passwords, but dependencies and images are not scanned yet.', refs: ['ci-tags', 'ci-token', 'ci-no-escalation'] },
  { code: 'A04', name: 'Cryptographic Failures', plain: 'Weak or missing encryption', status: 'Covered',
    why: 'TLS on both routes and to the database; no customer passwords or card numbers are stored at all.', refs: ['web-https'] },
  { code: 'A05', name: 'Injection', plain: 'Input treated as commands', status: 'Covered',
    why: 'Bind parameters for every query, and nothing a visitor sends is echoed back.', refs: ['data-sql', 'web-errors'] },
  { code: 'A06', name: 'Insecure Design', plain: 'A flaw in the plan, not the code', status: 'Covered',
    why: 'Overselling, double booking and queue-skipping are ruled out by design, in the database and on the server.', refs: ['data-check', 'data-idempotent', 'data-admission'] },
  { code: 'A07', name: 'Authentication Failures', plain: 'Weak proof of who you are', status: 'Partly',
    why: 'The key is compared safely and kept out of links, but it is one shared key, not user accounts.', refs: ['web-timing', 'web-no-urls'] },
  { code: 'A08', name: 'Software or Data Integrity Failures', plain: 'Data or releases changed without anyone noticing', status: 'Covered',
    why: 'Seat counts guarded by the database, releases pinned by commit, and a failed deploy is undone.', refs: ['data-check', 'ci-tags', 'ci-rollback'] },
  { code: 'A09', name: 'Security Logging and Alerting Failures', plain: 'Not noticing an attack', status: 'Partly',
    why: 'Structured logs, alerts on outages and overselling, and a timeline for every incident; refused keys are not alerted on.', refs: ['web-raw-logs', 'ai-drills'] },
  { code: 'A10', name: 'Mishandling of Exceptional Conditions', plain: 'Breaking badly when something goes wrong', status: 'Covered',
    why: 'Overload is shed with a clear 503, the AI falls back to a rules report, and drills undo themselves.', refs: ['ai-fallback', 'ai-drills', 'plat-budget'] }
];

/** OWASP Top 10 for LLM Applications 2025 (https://genai.owasp.org/llm-top-10/). */
export const OWASP_LLM: OwaspRow[] = [
  { code: 'LLM01', name: 'Prompt Injection', plain: 'Text that talks the AI into something', status: 'Partly',
    why: 'Pod logs reach the model, so a crafted line could steer it. It can only read and suggest, so the worst case is a wrong suggestion a person dismisses.', refs: ['ai-read-only', 'ai-human-approves'] },
  { code: 'LLM02', name: 'Sensitive Information Disclosure', plain: 'The AI repeating private data', status: 'Covered',
    why: 'Customer details are masked before the model sees anything, and raw logs are for key holders.', refs: ['ai-masking', 'web-raw-logs'] },
  { code: 'LLM03', name: 'Supply Chain', plain: 'Trusting a model you did not vet', status: 'Partly',
    why: 'The model is a shared service run by the platform; the console sends its token to that address only.', refs: ['ai-credential'] },
  { code: 'LLM04', name: 'Data and Model Poisoning', plain: 'Bad training data', status: 'Not applicable',
    why: 'Nothing here trains or fine-tunes a model.', refs: [] },
  { code: 'LLM05', name: 'Improper Output Handling', plain: 'Trusting what the AI writes', status: 'Covered',
    why: 'Its answer must be valid JSON, every number is checked, fixes come from a fixed menu, and text is shown as text.', refs: ['ai-cited-numbers', 'ai-human-approves'] },
  { code: 'LLM06', name: 'Excessive Agency', plain: 'An AI allowed to do too much', status: 'Covered',
    why: 'Read-only tools, no approve tool, and a person\'s click for every change.', refs: ['ai-read-only', 'ai-human-approves'] },
  { code: 'LLM07', name: 'System Prompt Leakage', plain: 'Secrets hidden in the AI\'s instructions', status: 'Covered',
    why: 'The prompts hold no secrets; they are public in the repository.', refs: [] },
  { code: 'LLM08', name: 'Vector and Embedding Weaknesses', plain: 'Poisoned search indexes', status: 'Not applicable',
    why: 'No embeddings or vector search are used.', refs: [] },
  { code: 'LLM09', name: 'Misinformation', plain: 'The AI stating things that are not true', status: 'Covered',
    why: 'Every number must come from a cited fact; if the model cannot manage that, a labelled rules report replaces it.', refs: ['ai-cited-numbers', 'ai-fallback'] },
  { code: 'LLM10', name: 'Unbounded Consumption', plain: 'The AI running up cost or load', status: 'Covered',
    why: 'Caps on tool calls, time per call, time per run and retries.', refs: ['ai-bounded'] }
];

export const GAPS: { title: string; text: string }[] = [
  { title: 'The demo key is public', text: 'On purpose, so a visitor can try a rush. What stands in for it: one live run at a time, a CPU quota, and sittings that expire.' },
  { title: 'One shared key, no accounts', text: 'Nobody signs in, so an action cannot be traced to a person.' },
  { title: 'No rate limiting yet', text: 'Nothing limits how often a key holder or an MCP client starts rushes, beyond one at a time.' },
  { title: 'No dependency or image scanning', text: 'CI builds and tests every commit but does not scan libraries or images for known vulnerabilities yet.' },
  { title: 'Prompt injection is contained, not prevented', text: 'Log text reaches the model. The defence is that the AI cannot act, not that it cannot be fooled.' },
  { title: 'Readiness does not check the database', text: 'Taken out so a slow Oracle cannot pull every pod from service at once; the deploy smoke test is weaker as a result.' }
];

export const PRACTICE_COUNT = SECTIONS.reduce((n, s) => n + s.items.length, 0);

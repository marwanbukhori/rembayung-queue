# MCP Server and an MCP-Driven Run Agent — Design

**Date:** 2026-09-26
**Status:** Draft, awaiting review
**Builds on:**
- the run agent (`2026-09-25-cluster-inspector-and-run-agent-design.md` §8);
- the funnel reports (`2026-09-26-agent-reports-funnel-design.md`);
- the two-wave rush (`2026-09-26-two-wave-rush-design.md`).

**Part of:** the three-part follow-up agreed on 2026-09-26. This is part 3.

---

## 1. Purpose

The console already has a set of read-only tools that its run agent uses, plus the services behind the inspector. This design serves them over the Model Context Protocol, for two users:
1. **Any MCP client.** Claude Code or Claude Desktop, pointed at the public console, can inspect the live cluster, read the agent's reports and, with the key, start a rush. Someone can then ask Claude "run a two-wave rush and explain what the autoscaler did".
2. **The run agent itself.** It calls its tools through the same MCP endpoint. One tool set serves our agent and everyone else's.

**Success:**
- `claude mcp add --transport http rembayung <console>/mcp` works from any machine.
- The ten tools are listed, and the reads answer without a key.
- `start_rush` works with the key.
- A run analysed after deploy shows "via MCP" in its trail.

### Out of scope

- MCP resources and prompts. Tools only.
- Write tools other than `start_rush`.
- An MCP server outside the console.

---

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Library | The official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`), with the servlet Streamable HTTP transport | Tracks the protocol for us. A servlet mounts in Spring Boot 4.1 without Spring AI, whose MCP starter targets Boot 3 |
| Endpoint | `/mcp` on the public console route | One step for anyone to connect |
| Access | Reads are public; raw logs and `start_rush` need the console key | The same rules as the site |
| Key | The `X-Console-Key` request header, read per call from the transport context | What the UI already sends; MCP clients can set headers |
| Agent path | An MCP client over loopback to the console's own `/mcp` | The agent and outside clients use the same tools; loopback avoids the public route's timeouts |
| Fallback | If the MCP session fails, the agent calls the same code in-process and says so in the trail | A run never loses its report over transport |

---

## 3. Server

The server name is `rembayung-console`, versioned with the build. The transport is `HttpServletStreamableServerTransportProvider` with `mcpEndpoint("/mcp")`, registered as a `ServletRegistrationBean`.

`KeyFilter` treats `/mcp` like the API: every method passes, and the tools decide. The filter's POST rule must not refuse MCP's POSTs; the tools enforce the key themselves.

### 3.1 Tools

| Tool | Args | Key | Returns |
|---|---|---|---|
| `get_state` | `drop?` | no | Seats taken, capacity, queue waiting and admitted, oversold, admit rate; pods with readiness |
| `list_runs` | — | no | Analysed runs, newest first: key, when, customers, booked, seats, oversold, waves, model or fallback |
| `get_report` | `run` | no; raw log facts need it | The analysis JSON: facts, sections, trail |
| `describe_object` | `kind`, `name` | no | The inspector's detail for that object: headline, facts, related, events |
| `metric` | `chart`, `pod?`, `from?`, `to?` | no | The chart over the window, 30 points a series |
| `events` | `kind`, `name`, `from?`, `to?` | no | Up to 20 events |
| `pod_status` | `pod` | no | Phase, ready, restarts, node, owner |
| `endpoints` | `service` | no | The ready pods behind the Service |
| `pod_logs` | `pod`, `level?`, `contains?`, `from?`, `to?` | raw lines need it | Up to 40 lines, phones masked; without the key, app events only |
| `start_rush` | `customers`, `waves?`, `admit_rate?` | yes | Drop id, Job, expected duration |

**Windows:**
- A missing `from`/`to` means the last 15 minutes.
- A window longer than 60 minutes is clamped, and the result says so.

**Implementation:**
- The last five tools reuse `agent.Tools`. Its methods gain an explicit window instead of a `RunWindow` where needed.
- `get_state` reuses `DemoStateProvider` and `ClusterStateProvider`.
- `list_runs` and `get_report` reuse `AnalysisStore` and the `AnalysesController` logic, including `withoutRawLogs`.
- `describe_object` reuses `ObjectsProvider.describe`.
- `start_rush` calls `DropOps.create` and then `LoadOps.start`, and adds one guard: it refuses while any load Job is live in the namespace.

**Errors:** bad arguments, a missing key, or a refused rush return an MCP tool result with `isError: true` and a sentence saying what to do. Those that need the key point to `/api/demo-key`.

---

## 4. The run agent over MCP

- At startup, and lazily on failure, the console opens an MCP client session with the SDK client's Streamable HTTP transport to `http://localhost:<server.port>/mcp`, sending the console key.
- **The menu:** the prompt's tool menu is built from `tools/list`, filtered to `pod_logs`, `metric`, `events`, `pod_status` and `endpoints`, using each tool's declared description and input schema.
- **Each call** is `tools/call` with the model's args plus the run window as `from`/`to`. The text result becomes the fact, as today, and the trail step records `via: "mcp"`.
- **If the session fails** (can't connect, or a transport error), the call runs in-process through `agent.Tools`, the trail step records `via: "in-process"`, and the analysis `note` says "MCP unavailable, tools called in-process".
- The Validator, the fallback and storage are unchanged.

---

## 5. Pages

The AI Agent page gains a **"Use it from Claude"** card, below "How it works". It shows:
- the endpoint `https://<console>/mcp`;
- the Claude Code command `claude mcp add --transport http rembayung https://<console>/mcp --header "X-Console-Key: <key>"`, with the key filled in when the visitor has it and "Get the demo key" when they don't;
- a Claude Desktop JSON snippet for the same;
- the ten tools, one line each;
- a note that reads are public, and that raw logs and `start_rush` need the key.

**Report trails:** a trail step shows "via MCP" or "in-process".

---

## 6. Testing

**Server**, with a `@SpringBootTest(webEnvironment = RANDOM_PORT)` and the SDK's sync client:
- the session initialises and `tools/list` has the ten names, with input schemas;
- `get_state`, `list_runs`, `get_report` and `describe_object` return what their services return (the services are mocked);
- `pod_logs` without the key is events-only, and raw with the key;
- `start_rush` without the key is an error, starts a run with the key (with `DropOps` and `LoadOps` mocked), and is an error while a run is live;
- a window over 60 minutes is clamped.

**The agent:**
- with a scripted model, calls over MCP produce the same fact values as the in-process path, and the trail says `mcp`;
- with the MCP client unable to connect, it falls back, and the trail says `in-process`.

**Live:**
- After deploy: `claude mcp add` from this machine, list the tools, and call `get_state` and `list_runs`.
- `start_rush` with the key starts a run.
- A run analysed after deploy has a trail marked "via MCP".

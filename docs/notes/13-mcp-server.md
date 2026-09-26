# 13 — The MCP server

**Covers:**
- The console as a Model Context Protocol server at `/mcp`.
- Why it is built on the official Java SDK and not a framework starter.
- The ten tools and the key rules they share with the site.
- How the run agent became a client of its own console.
- How it was verified, live.

---

## What it is

`https://<console>/mcp` speaks MCP over Streamable HTTP. Anyone can add it to Claude Code with one command:

```
claude mcp add --transport http rembayung https://<console>/mcp --header "X-Console-Key: <key>"
```

or to Claude Desktop with the same URL. Then they can ask about the live system in plain words: "run a two-wave rush of 200 at 8 per second, then explain what the autoscaler did".

There is no charge beyond the client's own Claude usage. The server runs inside the console on the free Developer Sandbox.

## Why the official SDK

The console is on Spring Boot 4.1. Spring AI's MCP starter targeted Boot 3 at the time, which was a compatibility gamble on a live demo. A hand-written JSON-RPC endpoint would have meant owning the handshake, the sessions and every protocol revision.

The official Java SDK (`io.modelcontextprotocol.sdk`, 2.0.1) needs neither:
- Its core module ships the servlet Streamable HTTP transport, mounted with one `ServletRegistrationBean`.
- `mcp-json-jackson3` matches the console's Jackson 3.

`KeyFilter` guards `/api/*` only. `/mcp` reaches the SDK directly, and each tool enforces the key itself.

## The tools

| Tool | Answers | Key |
|---|---|---|
| `get_state` | Seats, the queue, oversold, admit rate; pods and the CPU budget | no |
| `list_runs` | Every analysed rush with its headline numbers | no |
| `get_report` | One run in full: facts, sections, trail | raw log facts need it |
| `describe_object` | What the inspector shows for any object | no |
| `metric` | A chart over a window (default 15 minutes, at most 60) | no |
| `events` | Kubernetes events for one object | no |
| `pod_status` | Phase, readiness, restarts, node | no |
| `endpoints` | The pods ready behind a Service | no |
| `pod_logs` | App events; raw lines with the key | raw needs it |
| `start_rush` | Start a one- or two-wave rush on a fresh sitting | **yes** |

**The rules are the site's rules.**
- Reads are public.
- Raw logs and starting a rush need the key.
- The key arrives as the request's `X-Console-Key` header. A `contextExtractor` on the transport copies it into the MCP transport context, where each tool reads it.
- A refusal is a tool result with `isError: true` and a sentence saying what to do, pointing to `/api/demo-key`. It is never a protocol error the client cannot explain.
- `start_rush` also refuses while any load Job is live in the namespace, so an eager client cannot stack rushes.

The tools are thin adapters over the services the pages use: `DemoStateProvider`, `AnalysisStore`, `ObjectsProvider`, `PodLogs`, the agent's `Tools`, `DropOps` and `LoadOps`. A tool therefore answers exactly what the site would show.

## The agent as its own client

The run agent (note 12) asks its five questions through `/mcp`, over loopback (`http://localhost:8082/mcp`), with the console key. It uses the SDK's sync client. The run window travels as each tool's `from`/`to`.

So there is one tool set, not an internal one and a published one that can drift apart. What the agent sees is what Claude Code sees.

If the MCP session fails, that call runs in-process through the same `Tools` code. The trail records `in-process` instead of `mcp`, and the run still gets its report.

## Verified

- **Tests:** the SDK's own client against the console started on a random port. They cover:
  - two independent sessions;
  - all ten tools listed with schemas;
  - each tool against its service;
  - `pod_logs` events-only without the key and raw with it;
  - `start_rush` refused without the key and refused while a run is live;
  - windows over 60 minutes shortened;
  - the agent's facts identical over MCP and in-process;
  - an unreachable server falling back.
- **Live, with curl as a bare MCP client:**
  - `initialize` returned a session;
  - `tools/list` returned the ten tools;
  - `get_state` answered;
  - `start_rush` without the key returned the key error.
- **Live, end to end:** a rush was started through `start_rush` with the key. The agent's report on it shows all five tool calls "via MCP".
- **In the page:** the AI Agent & MCP page has a Try-a-tool panel that makes the same calls from the browser and shows the JSON-RPC each way: `initialize` about 480 ms, `tools/call` about 430 ms on the sandbox.

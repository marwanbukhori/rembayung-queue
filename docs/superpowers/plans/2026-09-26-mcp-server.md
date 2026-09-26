# MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The console serves ten tools at `/mcp` over the Model Context Protocol, and the run agent calls its tools through that same endpoint.

**Architecture:**
- The official MCP Java SDK 2.0.1: a servlet Streamable HTTP transport, and a sync server whose tool handlers are thin adapters over existing services.
- The run agent's tool calls go through an `McpSyncClient` on loopback, behind a `ToolCaller` interface. The in-process `Tools` stays as the fallback.
- The AI Agent page gains a "Use it from Claude" card.

**Tech Stack:** `io.modelcontextprotocol.sdk` 2.0.1 (with `mcp-json-jackson3`), Spring Boot 4.1, Angular 20.

**Spec:** `docs/superpowers/specs/2026-09-26-mcp-server-design.md`

## Global Constraints

- The endpoint is `/mcp`; the server is named `rembayung-console`.
- The ten tools, exactly: `get_state`, `list_runs`, `get_report`, `describe_object`, `metric`, `events`, `pod_status`, `endpoints`, `pod_logs`, `start_rush`.
- The key is read from the `X-Console-Key` header of the MCP request.
  - `start_rush` and raw `pod_logs` need it.
  - `get_report` redacts raw log facts without it.
- A tool error is a `CallToolResult` with `isError` true and a sentence saying what to do.
- `start_rush` refuses while any load Job is live in the namespace.
- The run agent calls over loopback. On an MCP failure, it calls in-process and records `via: in-process`.

## Review Focus

1. **An MCP client without the key calling `start_rush` or raw `pod_logs`.** Expected: a clear tool error, and no rush started. Tested in Task 2.
2. **KeyFilter blocking MCP's POSTs.** Expected: `/mcp` reaches the SDK, and the tools enforce the key. Tested in Task 1 through a real HTTP client.
3. **The loopback MCP session dying mid-analysis.** Expected: that call falls back in-process, and the run still gets a report. Tested in Task 3.
4. **A window over 60 minutes, or an unknown chart or kind.** Expected: a tool error or clamping, never an exception. Tested in Task 2.
5. **Two MCP clients at once** (a visitor's Claude and the agent). Expected: independent sessions. The SDK handles this; covered by Task 1's two-client test.

---

### Task 1: The dependency, the server at `/mcp`, and KeyFilter
- Add the SDK's BOM 2.0.1 and the modules providing `HttpServletStreamableServerTransportProvider`, `McpServer`, the JDK HTTP client transport, and the Jackson 3 JSON mapper. Confirm the module names from the jars.
- Add `mcp/McpConfiguration.java`:
  - the transport provider bean at `/mcp`;
  - `ServletRegistrationBean` mapped to `/mcp`, `/mcp/*`;
  - an `McpSyncServer` bean named `rembayung-console`, with tool capability.
- `KeyFilter` lets `/mcp` through for every method.
- Test `McpServerTest` (`@SpringBootTest(RANDOM_PORT)`, with external services mocked):
  - an SDK sync client initializes against `http://localhost:{port}/mcp` and lists the tools;
  - a second client works independently.

### Task 2: The ten tools
- `mcp/McpTools.java` builds `SyncToolSpecification`s with JSON input schemas. The handlers delegate to:
  - `DemoStateProvider`, `ClusterStateProvider` (`get_state`);
  - `AnalysisStore` plus `AnalysesController.withoutRawLogs` (`list_runs`, `get_report`);
  - `ObjectsProvider.describe`;
  - `agent.Tools` (`metric`, `events`, `pod_status`, `endpoints`), with a window from `from`/`to` (default the last 15 minutes, clamped to 60);
  - `PodLogs.read`, keyed (`pod_logs`);
  - `DropOps.create` and `LoadOps.start` (`start_rush`), guarded by the key and by "no live load Job".
- The key is taken from the request's `X-Console-Key` through the SDK's transport context.
- Tests (`McpToolsTest`, through a real client):
  - each tool returns its service's answer;
  - `pod_logs` is events-only without the key, raw with it;
  - `start_rush` is an error without the key, starts with it, and is an error while a Job is live;
  - a window over 60 minutes is clamped, and an unknown chart is an error.

### Task 3: The run agent over MCP
- `agent/ToolCaller.java`: `Fact call(String tool, JsonNode args, RunWindow w, Facts facts)`.
  - `InProcessTools` wraps `Tools`.
  - `McpTools` client (`agent/McpToolCaller.java`) keeps a lazily initialized `McpSyncClient` to `http://localhost:${server.port}/mcp` with the key header. It calls `callTool` with the args plus `from`/`to`, and on any exception resets the client and delegates to `InProcessTools`, reporting `via: "in-process"`.
- `TrailStep` gains `via`.
- `Analyst` takes a `ToolCaller`. The menu comes from the MCP server's `listTools` when available, else `Tools.MENU`.
- `Analysis.note` says "MCP unavailable, tools called in-process" when any step fell back.
- Tests:
  - through MCP, the facts match the in-process ones and the trail says `mcp`;
  - with an unreachable URL, the trail says `in-process` and the report is still produced.

### Task 4: The page
- A "Use it from Claude" card on the AI Agent page, below "How it works". It shows:
  - the endpoint;
  - the `claude mcp add --transport http rembayung <origin>/mcp --header "X-Console-Key: <key>"` command, with the key filled in when present, else "Get the demo key";
  - a Claude Desktop JSON snippet;
  - the ten tools with one line each;
  - a note on the key.
- Report trails show "via MCP" or "in-process".
- Build, and check in the browser at 1680 and 390.

### Task 5: Deploy and verify live
- Merge, push, and wait for CI and CD.
- With curl against the live `/mcp`:
  - JSON-RPC `initialize`, then `tools/list`: ten tools;
  - `tools/call get_state`: an answer;
  - `start_rush` without the key: an error.
- A run analysed after the deploy has a trail marked `mcp`.

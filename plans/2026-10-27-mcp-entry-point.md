# MCP entry point: `MCP.server` and `MCP.client`

## Goals

- Give MCP one entry point that reads like `Http.client`: start from `MCP.server` or `MCP.client`, chain `withXXX(...)`, then finish with an action (`serveStdio()`, `httpHandler`, `connect(uri)`).
- Make the snippet in the "Introducing Uni" post (#801) read the Uni way:

  ```scala
  @main def greeterMcp(): Unit = MCP.server
    .withName("greeter")
    .withVersion("0.1.0")
    .withTools[GreeterService](GreeterServiceImpl())
    .serveStdio()
  ```

- Ship the MCP client from #697 behind the same entry point, so people and AI agents learn one shape for both sides.

## Background

- `Http` is Uni's model: `Http.client` returns an immutable `HttpClientConfig`; `withXXX` returns a copy; `newSyncClient` / `newAsyncClient` creates the client. AGENTS.md asks config classes to follow `withXXX` / `noXXX`.
- `MCPServer` is already an immutable case class with `withName`, `withVersion`, `withTools`, `withRouter`, `withAllowedOrigins`. Only the entry point differs: users write `MCPServer()`.
- #697 (open since 2026-09-12, CI green, merges cleanly into main) adds `MCPClient` with `MCPClient.connect(uri)` and `MCPClient.connect(httpClient, uri)`. The client name and version sent in `initialize` are hard-coded (`uni-mcp-client`, `0.1.0`). There is no config object, so there is nothing to chain.

## Design

Lane: **Standard** — adds public API and changes the documented way to build MCP servers and clients.

- New `object MCP` in `wvlet.uni.mcp`:
  - `def server: MCPServer = MCPServer()`
  - `def client: MCPClientConfig = MCPClientConfig()`
- New `case class MCPClientConfig` (immutable, `withXXX` for every field):
  - `name` / `version` → `clientInfo` in `initialize` (today hard-coded)
  - `protocolVersion` (default `MCPServer.LatestProtocolVersion`)
  - `httpClientConfig: HttpClientConfig` (default `Http.client.withMaxRetry(0)`; retries stay off because `tools/call` is not idempotent), with `withHttpClientConfig(...)` for headers, timeouts, filters
  - `connect(serverUri: String): Rx[MCPClient]` — handshake as in #697
- Usage:

  ```scala
  RxResource
    .fromAutoCloseable(MCP.client.withName("my-agent").connect("http://localhost:8080/mcp"))
    .use { client => client.listTools() }
  ```

- `MCPClient.connect(...)` from #697 becomes a thin delegate to `MCP.client.connect(...)` (or is dropped, since it has never been released — see questions). `MCPServer()` stays public; it is a case class and tests use it.
- Bring #697 into this PR: merge its branch (keeps its commits and authorship), then add the config on top. Close #697 as superseded with a link.
- Docs, in the same PR:
  - `docs/mcp/index.md`, `docs/guide/for-ai-agents.md`, examples (`examples/mcp-server`), and the `uni` plugin skill (`SKILL.md`, `references/core.md`) switch to `MCP.server` / `MCP.client`; bump the plugin `version`; `docSnippets` compiles the skill fences.
  - The ADR from #697 gets a short note about the `MCP` entry point.
- #801 is a separate PR: after this merges, update its snippet to `MCP.server` (one-line change, compiled by `docSnippets`).

## Alternatives and Why Not?

- **Rename `MCPServer` to `MCPServerConfig`** to mirror `HttpClientConfig` exactly. Breaks every existing user for a naming nicety; the server value already is the config.
- **Land #697 first, then a follow-up for `MCP`.** Two review rounds, and #697 would publish `MCPClient.connect` as the public shape just before we replace it.
- **Only add `MCP.server` now.** Leaves the client without an entry point and keeps the API asymmetric, which is what the request wants to fix.

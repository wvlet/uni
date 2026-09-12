# MCP client (`MCPClient`): consume MCP servers from Scala

Date: 2026-09-11 · Status: Proposed

## Goals

- Complete Uni's MCP story: today `wvlet.uni.mcp` can *build* MCP servers (tools, stdio +
  Streamable HTTP), but nothing in Scala can *consume* one. Add an MCP client so Scala programs
  can connect to any MCP server — Uni-built or third-party — discover its tools and call them.
- Keep it cross-platform (JVM / Scala.js / Scala Native) over Streamable HTTP by reusing Uni's
  existing HTTP client. Zero new dependencies (`uni` stays dependency-free).
- Enable self-testing: an in-repo client lets us E2E-test the MCP server on every platform, and
  gives users the same for their own servers.

## Background

- Server side: `wvlet.uni.mcp` in `uni` (see `adr/2026-07-20-mcp-stdio-server.md`, PRs #659/#660):
  tools-only, protocol `2025-06-18` (also accepts `2025-03-26`), strict Origin check, stateless
  (no `Mcp-Session-Id`). Docs: `docs/mcp/index.md`.
- An MCP client appears nowhere in the ADR's deferred list or the docs' roadmap — genuinely new
  scope.
- Building blocks that already exist: `Http.client` (`HttpSyncClient.send`,
  `HttpAsyncClient.send: Rx[HttpResponse]`, `sendSSE`; per-platform channels for JVM/JS/Native),
  `wvlet.uni.json` (`JSON`/`JSONValue`, incl. `JSONObject(Seq[(String, JSONValue)])`),
  `wvlet.uni.weaver` codecs, `wvlet.uni.rx.Rx` (`await`/`run`), the MCP package's own `JsonRpc`
  JSON-RPC layer (currently `private[mcp]`), and `MCPTool`/`JsonSchema` for tool metadata.
- No cross-platform process-spawn primitive exists in `uni`, so a stdio *client* transport would
  be new per-platform code (see Alternatives).

## Design

### Scope (v1)

1. `wvlet.uni.mcp.MCPClient` — inside `uni`, same package as the server, so it can reuse `JsonRpc`
   (`private[mcp]`) and existing message types without widening visibility.
2. **Streamable HTTP transport only** — mirrors the server's HTTP transport; runs on all three
   platforms via `Http.client`.
3. **Tools capability only** — same scope as the server: `initialize`,
   `notifications/initialized`, `tools/list`, `tools/call`, `ping`. No resources/prompts until
   the server grows them.
4. **Schema-driven, dynamic API** — no compile-time knowledge of the remote server.

### API sketch

```scala
import wvlet.uni.mcp.*
import wvlet.uni.json.JSON.*

// Connect + handshake (initialize, then notifications/initialized)
val client: MCPClient = MCPClient.connect("http://localhost:8080/mcp").await

val tools: Seq[MCPToolInfo] = client.listTools().await
// tools: {name, description?, inputSchema} — inputSchema is the JSON Schema JSONValue

val result: MCPToolResult = client
  .callTool("forecast", JSONObject(Seq("city" -> JSONString("Tokyo"))))
  .await
result.isError shouldBe false
result.content // Seq[MCPContent] — text / image content blocks

client.close()
```

Async `Rx[...]` returns are primary (like `HttpAsyncClient`); `MCPClient.connect` hides the
handshake so users never see raw JSON-RPC.

### Key points

- **Message layer**: extend `JsonRpc` with a client-side response parser (extract `result` or
  `error`, match `id`) next to the existing `parseRequest`, keeping one code path for message
  shape and standard error codes. Same `private[mcp]` visibility suffices — no public API change.
- **HTTP**: one POST per JSON-RPC message with `Content-Type: application/json`,
  `Accept: application/json, text/event-stream`, `MCP-Protocol-Version: 2025-06-18`.
  `initialize` may return `Mcp-Session-Id`; if so, echo it on subsequent requests (Uni's own
  server is stateless and does not, but third-party servers do). 202 responses (notifications)
  have no body and are ignored.
- **tools/list**: parse into `MCPToolInfo(name, description: Option[String], inputSchema:
  JSONValue)` — `inputSchema` is exactly what `JsonSchema.scala` already produces for the server,
  so a client round-trips an existing schema without reinterpretation.
- **tools/call**: arguments are a pass-through `JSONObject`. Content blocks become
  `MCPTextContent` / `MCPImageContent` (spec `text`/`image`); `isError: true` is returned as data,
  not thrown — mirroring the server's decision that tool execution failures are results and
  protocol errors are exceptions.
- **Errors**: JSON-RPC `{code, message}` → `MCPClientException(code, message)`; HTTP failures
  surface as existing HTTP client exceptions.
- **Lifecycle**: `close()` closes the underlying HTTP client. `connect` is shorthand for
  `initialize` + `notifications/initialized`; expose `MCPClient.withHttpClient(...)` (or accept an
  `HttpClientConfig`) for custom base URI, retry, and filters.

### Testing and docs

- Shared test (`uni/src/test/scala/wvlet/uni/mcp/MCPClientTest`): start
  `MCPServer.withTools[...].httpHandler` on a loopback port via the platform server (JVM /
  NodeServer / NativeServer) and drive it with `MCPClient` — `initialize`, `listTools`,
  `callTool` round-trips, `isError` result, invalid-args `-32602` error.
- JVM end-to-end: `uni-netty` test against `NettyServer` (mirrors `MCPNettyServerTest`).
- Docs: "Call an MCP server from Scala" section in `docs/mcp/index.md`; the sidebar entries for
  `/mcp` already exist. ADR: extend `2026-07-20-mcp-stdio-server.md` (or add a short follow-up
  ADR) recording: client inside `uni`, HTTP first, no generated typed code in v1.

## Alternatives and Why Not?

### stdio client transport (spawn local server processes)
Deferred: needs a cross-platform process-spawn primitive that `uni` does not have (JVM
`ProcessBuilder`, JS `child_process`, Native subprocess) — three new transports and CI surface for
a use case that HTTP already covers for remote servers. Keep the client core transport-agnostic so
a stdio transport slots in later on demand.

### Typed client (codegen from a trait, like `RPCClient`)
Deferred: `tools/list` schemas arrive at runtime; there is no compile-time schema unless the
server is a Uni trait. A later `sbt-uni` increment could generate typed clients from a known
server trait. v1's dynamic API covers both Uni and third-party servers.

### New module (`uni-mcp-client`)
Rejected per ADR decision 1: module splits exist only for dependency isolation (`uni-netty` ⇒
Netty). The client adds zero dependencies and wants package-private access to `JsonRpc`/`MCPTool`.

### Legacy HTTP+SSE transport
Skipped: deprecated by the spec, and Uni's server offers no GET event stream. `sendSSE` already
exists if a real server demands it later.

## Decisions to confirm

1. Scope: HTTP-only, tools-only, dynamic schema-driven `MCPClient` inside `wvlet.uni.mcp` (no new
   module)?
2. API shape: `MCPClient.connect(url)`, `listTools()` / `callTool(name, args)` returning
   `Rx[...]`, plus `close()` — with `initialize` hidden?
3. Tests: shared loopback test on all platforms plus a JVM `uni-netty` end-to-end test?

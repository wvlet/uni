# MCP client (`wvlet.uni.mcp.MCPClient`): consume MCP servers over Streamable HTTP

Date: 2026-09-11

## Context

Uni can *build* MCP servers (`MCPServer`, stdio + Streamable HTTP) but nothing in the repo can
*consume* one: the only way to call MCP tools from Scala was to shell out to an external client
(Claude Code, etc.). The pieces for a client already existed — `HttpSyncClient`/`HttpAsyncClient`
and `JSON`/`Weaver` (all three platforms), the MCP package's `JsonRpc` JSON-RPC layer
(`private[mcp]`), `MCPTool`/`JsonSchema` shapes — so the client needed no new dependencies.

## Decisions

1. **Inside `uni`, same package as the server.** Module splits exist only for dependency
   isolation (`uni-netty` ⇒ Netty); like the server, the client adds zero dependencies. Living in
   `wvlet.uni.mcp` lets it reuse `JsonRpc` (`private[mcp]`) and the `MCPTool`/`inputSchema` types
   without widening visibility. A stdio client transport remains out of scope: there is no
   cross-platform process-spawn primitive in `uni`, and HTTP already covers remote servers.

2. **Dynamic, schema-driven API; no codegen.** `tools/list` schemas arrive at runtime, so there
   is no compile-time contract unless the server is a Uni trait. `MCPClient.connect(url)` returns
   `Rx[MCPClient]` after the handshake (`initialize` + `notifications/initialized`);
   `listTools(): Rx[Seq[MCPToolInfo]]` and `callTool(name, JSONObject): Rx[MCPToolResult]` are
   the public surface. A typed `sbt-uni` generated client for known Uni-server traits is a later
   increment, not part of this change.

3. **Sync HTTP channel behind an Rx API.** The JVM `HttpAsyncClient` is not runnable today: its
   `JavaHttpAsyncChannel` returns `RxDeferred.get`, an anonymous `Rx` that `RxRunner.run` matches
   on no case (`MatchError`), and `RxDeferred`'s "handled specially by RxRunner" comment is
   stale — uni-core cannot reference uni's `RxDeferred` (dependency direction), so the special
   case was never implemented. Until that is fixed (e.g. move `RxDeferred` to uni-core and give
   RxRunner a callback-based source), the client uses `HttpSyncClient` and wraps each call in
   `Rx.single`, so each call blocks when the returned Rx is run. This matches the blocking stdio
   server and works on all platforms (browser JS has no sync HTTP anyway and throws, as
   everywhere else in uni). Retries are disabled by default because JSON-RPC calls
   (`tools/call`) are not idempotent.

4. **Message layer shares one code path.** `JsonRpc` gained the client counterparts of its
   existing server helpers: `request(...)` renders a request/notification compact JSON, and
   `parseResponse(...)` extracts `result`/`error` and validates the shape, so message framing and
   standard error codes stay in one place. Parse failures are **thrown** as
   `JsonRpcParseException(id, code, message)` (the pre-existing `parseRequest` was refactored from
   `Either` to match): uni keeps `throw`/`try` inside internal code and reserves `Result` for API
   boundaries that represent failure as a value — `Either[(...), ...]` is not used for exception
   propagation.

5. **Protocol** (matching the server): one POST per message with `Content-Type: application/json`,
   `Accept: application/json, text/event-stream`, `MCP-Protocol-Version: 2025-06-18`; `202`
   responses are notifications with no body; a `Mcp-Session-Id` returned by `initialize` is
   echoed on subsequent requests (session-less servers such as `MCPServer` do not issue one).

6. **Error taxonomy mirrors the server**: JSON-RPC `{code, message}` and HTTP failures are thrown
   as `MCPClientException(code, message)`; `tools/call` results with `isError: true` are returned
   as data (the spec's distinction between protocol errors and tool execution failures).

7. **Resource-managed sessions.** `MCPClient` is an `AutoCloseable`, and the idiomatic session
   shape is bracket-based: `RxResource.fromAutoCloseable(MCPClient.connect(url)).use { client =>
   ... }`, so `close()` runs on both the success and error paths (an exception before `close`
   must not leak the HTTP client). To support this on every platform, `RxResource.use` was
   rewritten to compose its release/finalizers as an `Rx` chain instead of blocking with
   `Rx.await`, which is unsupported on Scala.js.

## Consequences

- Each call blocks the thread that runs the Rx; a true async path (a runnable `RxDeferred` or
  equivalent) is the upgrade path, after which `MCPClient` can take an `HttpAsyncClient`.
- The client is contractually tied to the server's protocol shape (both directions use the same
  `JsonRpc` and `JSON` types); server changes to tool result encoding propagate to the client.
- Tests: `MCPClientTest` (shared, all three platforms) drives `MCPServer` through an in-memory
  `HttpChannel` that runs `MCPHttpHandler` synchronously via `RxRunner.runOnce`, exercising the
  whole `DefaultHttpSyncClient` → channel → `MCPHttpHandler` → `MCPServer` path without a
  socket; `MCPClientNettyServerTest` (uni-netty, JVM) covers a real `NettyServer` end-to-end.

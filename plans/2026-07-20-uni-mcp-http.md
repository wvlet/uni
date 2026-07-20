# MCP Streamable HTTP transport for wvlet.uni.mcp

## Context

Follow-up to `plans/2026-07-20-uni-mcp.md` (PR #659, merged): add MCP's HTTP transport. Since spec
revision 2025-03-26, HTTP MCP is "Streamable HTTP": a single endpoint receiving POSTed JSON-RPC
messages; per-request `application/json` responses are fully compliant — SSE streaming, sessions,
and the GET event stream are optional and only needed for server-initiated messages, which the
tools-only server does not have. `MCPServer.handleMessage` is already transport-independent, and
all three platforms have HTTP servers mounting `RxHttpHandler` (`NettyServer` in uni-netty,
`NodeServer` on JS, `NativeServer` on Native), so this is a thin shared adapter.

## Design

`MCPServer.httpHandler: RxHttpHandler` in shared code (`uni/src/main/scala/wvlet/uni/mcp/`):

- **POST**: run the body through `handleMessage`.
  - `Some(json)` → 200 with `application/json` body.
  - `None` (notification) → 202 Accepted, empty body.
- **Any other method** (GET/DELETE/...) → 405 Method Not Allowed (no SSE stream, no sessions —
  stateless server, no `Mcp-Session-Id`).
- **Origin validation** (spec MUST, DNS-rebinding protection): when an `Origin` header is present,
  allow only localhost origins (`localhost`, `127.0.0.1`, `[::1]`, any port/scheme) plus anything
  in a new `MCPServer.allowedOrigins` config (`withAllowedOrigins(...)`); otherwise 403. Absent
  Origin (non-browser clients) → allowed.
- **`MCP-Protocol-Version` header**: if present and not in `SupportedProtocolVersions` → 400 (spec
  SHOULD); absent → accepted (spec says assume an older supported revision).

Mounting is the user's three lines per platform (no `serveHttp` convenience: the JVM server lives
in uni-netty, which `uni` cannot depend on, so a uniform shared method is impossible — keep the
API symmetric instead):

```scala
NettyServer.withPort(8080).withRxHandler(mcp.httpHandler).start()   // JVM (uni-netty)
NodeServer.withPort(8080).withRxHandler(mcp.httpHandler).start()    // Scala.js / Node
NativeServer.withPort(8080).withRxHandler(mcp.httpHandler).start()  // Scala Native
```

## Deliverables

- `MCPServer.httpHandler` + `allowedOrigins`/`withAllowedOrigins` (shared).
- Shared tests (all 3 platforms) driving `httpHandler` with synthesized `Request`s: initialize →
  200 JSON, notification → 202, GET/DELETE → 405, non-local Origin → 403, allowed origin → 200,
  localhost origins → 200, unsupported `MCP-Protocol-Version` → 400, tools/call round trip.
- JVM integration test in uni-netty: real `NettyServer` + HTTP client POSTing a session.
- Docs: "HTTP transport" section in `docs/mcp/index.md` (mount snippets, `.mcp.json` `"url"` form).

## Learnings from the PR cycle (#660)

- **Origin parsing must be strict, not permissive**: the first `hostOf` cut a bracketed IPv6 host
  at `]`, so `http://[::1].evil.com` validated as `[::1]` (flagged security-critical by Gemini).
  Fixed by requiring that a bracketed host be followed only by a port separator or the end of the
  authority; anything malformed is returned whole so it can never match an allowed host.
  Regression tests pin `[::1].evil.com`, `[::1]evil.com:80`, and `localhost.evil.com`.

## Deferred

SSE streaming + sessions (needed only when server-initiated notifications exist — resources/prompts
with `listChanged`), `Mcp-Session-Id`, resumability, exposing RPC routes alongside MCP on one port.

# Harden uni HTTP/RPC servers

## Goals

- Close the concrete, exploitable gaps in uni's HTTP/RPC server stack (Netty, Scala Native, Node.js) so a service built with uni is safe by default, not only after the app adds its own guards.
- Keep the change to one reviewable PR. It covers the high- and medium-severity items that share a common shape: limits, timeouts, origin checks and error hygiene. Larger features such as TLS and an RPC exposure policy come in follow-up PRs.
- Settle which defaults to change, because some fixes alter behavior that existing users may rely on (see **Decisions**).

## Background

A read-only audit of `uni/src/main/scala/wvlet/uni/http/**`, `uni/.js`, `uni/.native` and `uni-netty` found the following.

What is already sound:
- Netty bounds the request line, headers and body (`HttpServerCodec(4096, 8192)`, `HttpObjectAggregator(64KiB)`) and WebSocket frames (1MiB).
- The Native server bounds requests at 1MiB, has 30s idle, read and handler timeouts, and rejects chunked or conflicting `Content-Length` (anti-smuggling).
- The shared WebSocket frame decoder validates RSV bits, masking and control-frame sizes.
- The JSON parser is iterative, so deep nesting cannot overflow the stack.
- RPC is POST-only with exact-path lookup, and error JSON carries no stack traces.

Gaps:

| # | Gap | Where | Impact |
|---|-----|-------|--------|
| H1 | Node server buffers the request body with no cap | `NodeHttpServer.scala:135-139` | One large POST exhausts the heap and kills the process |
| H2 | No WebSocket `Origin` check on any backend | `NettyRequestHandler`, `WebSocketHandshake.validate` | Cross-site WebSocket hijacking when the route filter authenticates by cookie, including `localhost` dev servers |
| M1 | `e.getMessage` of arbitrary exceptions returned to clients | ~15 sites: `RPCDispatcher:104`, `RouterHandler:105`, `NettyRequestHandler`, `NativeHttpServer`, `NodeHttpServer`, `ElectronRPCServer:79` | Paths, SQL, hostnames and tokens can leak in 500 and RPC error bodies |
| M2 | Netty HTTP pipeline has no idle or read timeout | `NettyHttpServer.scala:116-145` | Slowloris and idle keep-alive connections exhaust file descriptors |
| M3 | Every backend binds `0.0.0.0` by default | `NettyServer:55`, `NativeServer:49`, `NodeServer:46` | Unauthenticated dev and sidecar servers are reachable from the LAN |
| M6 | Response header values are not checked for CR/LF | Native writer `NativeHttpProtocol:167,224`; shared `Response`/`HttpMultiMap` | Response splitting when an app echoes input into `Location` or `Set-Cookie` |
| L2 | Bad path or query parameters (`"abc".toInt`) produce a 500 with the exception message | `HttpRequestMapper.scala:96-117` | Noisy 500s and the same message leak as M1; should be a 400 |

The limits live on the backend configs (`NettyServerConfig`, `NativeServerConfig`, `NodeServerConfig`). The shared `HttpServerConfig` trait has no security settings.

## Design

1. **Node body limit (H1).** Add `maxContentLength` (default 1MiB, the same as Native's `maxRequestSize`) to `NodeServerConfig`. Reject an oversized `Content-Length` up front. While reading, count bytes in `onData`, and once over the limit, respond 413 and destroy the socket.
2. **WebSocket origin policy (H2).** Add a small shared `OriginPolicy` in `uni/http`:
   - A request with no `Origin` header is allowed. Non-browser clients such as the JVM `JavaWebSocketClient` and CLI tools send none.
   - The default allows a same-origin request, where the `Origin` host:port equals the `Host` header.
   - `withAllowedOrigins(Seq("https://app.example.com", ...))` allows the listed origins; `"*"` restores today's behavior.
   - The policy is enforced before the route filter on all three backends. A rejected request gets a 403.
3. **Generic internal errors (M1, L2).**
   - A non-`HttpException`/non-`RPCException` failure becomes `500 Internal Server Error` (RPC: `INTERNAL_ERROR_I0` with the message "Internal error"). The full exception is logged at WARN on the server.
   - `HttpException` and `RPCException` keep their messages, because app code throws these on purpose to talk to the client.
   - An opt-in `withDetailedErrors(true)` on each server config (or a shared flag) restores today's messages for development.
   - The ~15 call sites go through one helper, `Response.internalError(e, detailed)`.
   - Parameter conversion failures in `HttpRequestMapper` are wrapped as 400 Bad Request.
4. **Netty timeouts (M2).** Add `withIdleTimeoutMillis` (default 60s) and `withReadTimeoutMillis` (default 30s) to `NettyServerConfig`. Install an `IdleStateHandler` in the HTTP pipeline that closes the channel on timeout. SSE and WebSocket channels are exempt; WebSocket keeps its own ping handling.
5. **Header CR/LF validation (M6).** Reject CR, LF and NUL in header names and values when they are added to `Response` and `Request` (`HttpMultiMap` add/set path), by throwing `IllegalArgumentException`. Response splitting is then impossible on every backend, not only on Netty and Node, whose libraries already validate.
6. **Default bind address (M3).** Change the default `host` of `NettyServerConfig`, `NativeServerConfig` and `NodeServerConfig` to `127.0.0.1`. Deployments that need external access (Docker, Kubernetes) call `withHost("0.0.0.0")`. This is a breaking change, which is acceptable for uni.
7. **Tests.** One UniTest per item: oversized Node body gives 413; a cross-origin WebSocket upgrade gives 403 and a same-origin one succeeds; a thrown `RuntimeException("secret")` produces a body without `secret`; an idle Netty connection is closed; a header containing CRLF throws; `?n=abc` gives 400.
8. **Docs.** Add a short "Security defaults" section to `docs/http`, plus an ADR for the error-detail and origin defaults.

### Out of scope (follow-up PRs)

- SSE on an unbounded cached thread pool, and Native's 16-worker pool starved by long-lived streams (M4).
- Electron IPC sender-frame validation (M5).
- RPC exposing inherited public trait methods (L1).
- TLS support, opt-in security response headers, configurable compression, and the client-side SSE line-length cap (L3–L6).

## Decisions

- **A. Default bind address:** `127.0.0.1`. Breaking changes are acceptable for uni; external exposure must be explicit.
- **B. Origin default:** same-origin plus no-Origin. A browser page on a different port (for example Vite on :5173) must add `withAllowedOrigins`.
- **C. Error detail default:** generic messages by default; `withDetailedErrors(true)` opts in to the old behavior.

## Alternatives and Why Not?

- **Put the settings on the shared `HttpServerConfig` trait.** The trait is a minimal abstraction, and each backend already owns its limits. Duplicating the few new fields per backend keeps the trait stable. Shared logic (`OriginPolicy`, header validation, the error helper) still lives in `uni/http`.
- **Sanitize only RPC errors.** REST handlers leak the same way through `RouterHandler`, so one helper should cover both.
- **One PR per finding.** That is about seven small PRs touching the same handlers and conflicting with each other. The items in scope share files and test fixtures.

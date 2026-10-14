# HTTP/RPC server hardening, round 2

## Goals

- Close the gaps that #709 left open: DNS rebinding against loopback servers, untrusted Electron IPC senders, unintended RPC endpoints, and unbounded threads for long-lived SSE/WebSocket connections.
- Keep the #709 principle: secure by default, explicit opt-out. Breaking changes are acceptable for uni.
- Settle which items ship in this PR and which wait.

## Background

- #709 (see `adr/2026-10-14-http-server-secure-defaults.md`) made servers bind to `127.0.0.1` by default. Loopback alone does not stop a browser: a page on `evil.example` can rebind its hostname to `127.0.0.1` and then send same-origin requests to the local server. The browser sends `Host: evil.example:PORT`. The ADR records this as a known gap. `HttpServerSecurity.isSameOrigin` compares `Origin` with this client-supplied `Host`, so the WebSocket origin check also passes for a rebinding page.
- Plain HTTP/RPC has no Origin check at all, so after rebinding any route is readable (an MCP server, an Electron helper, or a dev tool with no auth).
- `ElectronRPCServer.serve` registers `ipcMain.handle(channel, (_event, payload) => ...)` and ignores the sender. Any frame in any renderer can call every RPC method, including an embedded third-party iframe or a page the app navigated to by mistake. Electron's security checklist asks apps to validate `event.senderFrame`.
- `RPCRouter.of[T]` uses `Surface.methodsOf[T]`. `localMethodsOf` collects every public, non-Java method of `T` and its base traits. Only `Object`/`Any` are excluded. A service trait that mixes in a helper trait (e.g. a uni trait with public helpers, or a shared base with concrete utility methods) exposes those methods as RPC endpoints. `RPCClient` applies the same filter, so any change must stay symmetric.
- Netty runs each SSE stream on `Executors.newCachedThreadPool` (unbounded). Each open stream holds a thread, so many clients can exhaust threads or memory. Native serves every connection, including SSE and WebSocket, on a fixed pool of 16 `workerThreads`. 16 idle SSE/WS clients starve all ordinary requests.

## Design

Priority, all in this PR:

### P1. Host header allowlist (DNS rebinding)

- Add `allowedHosts: Seq[String]` to `HttpServerConfig` (a member with a default, like `allowedOrigins`), with `withAllowedHosts(...)` / `noHostCheck()` on the Netty, Native and Node configs.
- Default (empty) is derived from the bind address:
  - Loopback bind (the default): allow `localhost`, `127.0.0.1`, `[::1]` (and `*.localhost`).
  - Non-loopback bind (`0.0.0.0` or a real address): allow all, because the server cannot know its public names. The user can narrow it with `withAllowedHosts`.
- Matching: hostname only (port ignored), case-insensitive. An entry can be an exact name or `*.suffix`. `"*"` allows all.
- Add `HttpServerSecurity.checkHost(request, allowedHosts): Option[Response]`. Each backend calls it before routing, WebSocket upgrade, SSE and RPC, so one check covers everything, MCP included. A rejected request gets `403 Forbidden` with the body "Host not allowed". A request without `Host` (HTTP/1.0) is allowed, because browsers always send it.
- The WebSocket same-origin rule can then trust `Host`, so the gap described in the ADR is closed.

### P2. Electron IPC sender validation

- Add `ElectronRPCServer.serve(ipcMain, config: ElectronRPCServerConfig, routers*)`. The existing overloads use the default config.
- By default, reject a call when `event.senderFrame` is null (destroyed frame) or is not a top-level frame (`senderFrame.parent != null`). Embedded iframes cannot call RPC.
- `withAllowedOrigins(...)`: allow only sender URLs whose origin is in the list, e.g. `file://` or `app://myapp`. `withSenderFilter(frame => Boolean)` handles custom rules. When no allowlist is set, the default accepts any top-level frame.
- A rejected call resolves with an RPC error response (`PERMISSION_DENIED`-style status) and logs a warning. It does not throw, so the renderer always receives a well-formed payload.

### P3. RPC exposes only intended methods

- Add a `@rpcIgnore` method annotation (name TBD). `RPCRouter` and `RPCClient` share one filter, `RPCMethodFilter`, that drops annotated methods. `MethodSurface.annotations` already exists.
- Also drop methods that `T` inherits from uni's own mixin traits (`LogSupport` and similar), which are never RPC APIs. This needs the declaring class. Add `MethodSurface.declaringType: Option[String]`, filled by `CompileTimeSurfaceFactory` from `m.owner.fullName`, so the filter can exclude declaring types under `wvlet.uni.*` and `scala.*`.
- Keep inherited methods from the user's own base traits. `trait UserApi extends CrudApi[User]` is a legitimate composition pattern, which rules out a "declared on T only" restriction (see Alternatives).

### P4. Bounded long-lived connections

- Netty: replace the cached pool with a `ThreadPoolExecutor(0, maxStreams, 60s, SynchronousQueue)`. It is configured by `withMaxStreamingConnections(n)` (default 256) and covers SSE streams. Over the limit, the request gets `503 Service Unavailable` before any headers are written. WebSockets run on the event loop and need no executor change.
- Native: count active SSE/WebSocket connections and reject new ones with 503 above `maxStreamingConnections`. The default is `workerThreads - 4`, so at least 4 workers stay free for ordinary requests. Validate that `workerThreads` exceeds the reserve.
- Node.js is event-driven and unaffected.

### P5 (small, included): SSE parser line-length cap

- `ServerSentEventParser` holds the incomplete line in `buffer = buffer + chunk` and re-splits the whole buffer for every chunk. A malicious server can make memory grow without bound and CPU time grow quadratically. Switch to a `StringBuilder` that scans only new data, and fail the stream when a line exceeds `maxLineLength` (default 1MiB).

### Deferred (not in this PR)

- Opt-in security response headers filter (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`): useful, but opt-in and independent.
- Configurable `HttpContentCompressor` (BREACH): only matters for TLS with secrets reflected in compressed bodies. Add a `withCompression(false)` later.
- TLS / `withSsl`: a feature rather than hardening. Docs first.
- Separate bug PR: on Scala.js, `Rx.future` + `RxRunner.runOnce` emits `OnCompletion` while the future is pending (`FilterOp` completes on rejection). Async Node tests that return `startAndAwait` therefore never assert. `NodeServerTest.firstValue` is the current workaround. Fix it in its own PR so that a test-semantics change does not mix with security behavior.

### Docs and ADR

- Update `adr/2026-10-14-http-server-secure-defaults.md` (resolve the "DNS rebinding" known gap) or add a new ADR for host allowlist, IPC sender, and RPC method exposure. Update the HTTP/RPC/Electron docs pages.

## Alternatives and Why Not?

- **Origin check on every HTTP request instead of a Host allowlist**: non-browser clients send no Origin, and a rebinding page *is* same-origin, so an Origin check cannot stop rebinding. Only `Host` reveals it.
- **Allowlist enforced for non-loopback binds too**: secure, but every server on `0.0.0.0` would break until the user configured its public names. Loopback is where unauthenticated dev and sidecar servers live, so the default focuses there.
- **RPC: only methods declared directly on T**: simplest, but breaks API trait composition (`extends CrudApi[A]`), which existing users rely on.
- **RPC: only abstract methods**: drops concrete helpers automatically, but also drops trait-provided default endpoints, which is a surprising rule to explain.
- **Electron: require an explicit origin allowlist (no default)**: strictest, but every app would break on upgrade, and top-level-only already blocks the main threat (embedded third-party frames).

## Decisions

- Ship P1–P5 in one PR, like #709.
- A rejected `Host` gets `403 Forbidden`.
- RPC filtering uses the `@rpcIgnore` annotation plus `MethodSurface.declaringType`.
- Defaults: Netty caps streams at 256, and Native reserves 4 workers.

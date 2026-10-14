# HTTP server secure defaults

## Decision

uni's HTTP servers (Netty, Node.js, Scala Native) are secure by default, and an app opts out explicitly:

- **Bind to `127.0.0.1`.** `withHost("0.0.0.0")` is required to accept connections from other hosts. This is a breaking change, accepted because a dev or sidecar server with no auth (MCP, Electron helpers, local tools) should never be reachable from the LAN by accident.
- **Generic 500 bodies.** An unexpected exception becomes `Internal Server Error` (RPC: `INTERNAL_ERROR_I0` with the message `Internal error`). The full exception is logged at WARN. `withDetailedErrors(true)` restores the message.
  - Messages of `HttpException` are hidden too. A handler may surface one from an upstream call, and its message (URL, host) or status (an upstream 401) does not belong to this server's client. Handlers that want a specific status return a `Response`.
  - `RPCException` keeps its message: application code throws it on purpose to talk to the client.
  - The MCP server keeps detailed errors, because MCP returns tool failures to the model as `isError` content so the model can correct its call.
- **WebSocket origin check.** A browser `Origin` must be same-origin (its host:port equals the `Host` header, ignoring default ports) or listed in `allowedOrigins`. `"*"` allows all. A request without `Origin` (non-browser clients) is allowed. The check runs before the route filter, so a cookie-authenticating filter is never consulted for a cross-site page.
- **Header CR/LF/NUL rejection.** It is enforced in `Request`/`Response.addHeader`/`setHeader` for early failure, and again in every backend's writer, because a `Response` can be built with a raw `HttpMultiMap`. The writer substitutes a 500.
- **Request read timeout on Netty.** `RequestReadTimeoutHandler` (30s) bounds the time to receive a complete request, measured from connection open or the end of the previous response. It does not limit handler time; it counts pipelined requests so a slow handler is never cut off, and it is removed on WebSocket upgrade. The count is signed (requests read minus responses written): the aggregator can answer 413 before the oversized body finishes, and a clamped counter then left the timer disarmed forever. An `IdleStateHandler` was rejected because all-idle would close slow handlers and quiet SSE streams.
- **Node.js body limit.** `maxContentLength` defaults to 1MiB, matching Native's `maxRequestSize`. The server checks `Content-Length` up front and counts chunked bytes while reading, then answers 413 and closes the socket.

## Why the settings live on each backend config

`HttpServerConfig` exposes `allowedOrigins` and `detailedErrors` as members with defaults, so shared code can read them. Each backend config declares the fields and `withXXX` builders, as with `webSocketRoutes`. The logic itself lives once, in `HttpServerSecurity`.

`RouterHandler` and `RPCHandler`/`RPCDispatcher` are built independently of the server config, so they take their own `detailedErrors` flag.

## Host allowlist (DNS rebinding)

Same-origin compares `Origin` with the client-supplied `Host`, so a rebinding page (its host name resolves to 127.0.0.1) passes it. Every request, not only WebSocket upgrades, is therefore checked against `allowedHosts` before routing. Empty means the default derived from the bind address: a loopback bind accepts `localhost`, `*.localhost`, `127.0.0.1` and `[::1]`, and any other bind accepts every host, because a server on `0.0.0.0` cannot know the names clients use to reach it. A rejected request gets 403. A request without `Host` is allowed, because browsers always send one.

An `Origin` check on every request was rejected: non-browser clients send no `Origin`, and a rebinding page is same-origin, so only `Host` reveals it.

## Bounded long-lived connections

SSE streams (and WebSockets on Native) hold resources for their whole lifetime. Netty caps open SSE streams with a counter released on channel close (default 256), and the SSE executor has as many threads as stream slots. A thread-pool bound alone is not enough, because a non-blocking `Rx` source releases its thread while the stream stays open. Native counts SSE and WebSocket connections, because each parks a worker. Its default keeps `min(4, workerThreads / 2)` workers for ordinary requests. Over the limit, the request gets 503.

## RPC endpoint selection and Electron senders

`RPCMethodFilter` (shared by `RPCRouter` and `RPCClient`) drops `@rpcIgnore` methods and methods declared in `scala.*`, `java.*` or `wvlet.uni.log.*`, using the new `MethodSurface.declaringType`. Methods inherited from the application's own base traits stay exposed, so API trait composition keeps working; "declared on T only" was rejected for that reason. `ElectronRPCServer` accepts calls only from top-level frames by default (`ElectronRPCServerConfig`), with optional origin and custom sender checks.

## Known gap: `startAndAwait` tests on Scala.js

`Rx.future(f)` is `filter(_.isDefined)` over an `RxVar`. While `f` is pending, the filter emits `OnCompletion`, and `RxRunner.runOnce` treats that as terminal. A Node.js test returning `startAndAwait { ... }` therefore finishes before its assertions run. The new Node server tests use `RxRunner.run` (`firstValue` in `NodeServerTest`) so they assert for real. Fixing `runOnce`/`Rx.future` is a separate change.

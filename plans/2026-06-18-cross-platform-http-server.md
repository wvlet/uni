# Cross-Platform HTTP Server Foundation (toward WebSocket support)

## Context

Airframe recently added WebSocket support — but only **server-side, JVM/Netty-only**. We want WebSocket
in Uni too, and ideally cross-platform. A WebSocket server is an HTTP server with an `Upgrade` handshake,
so a cross-platform WS server is impossible without a cross-platform HTTP server first.

Today Uni's HTTP **server** lives entirely in the JVM-only `uni-netty` module; the core server contract
(`RxHttpHandler`/`RxHttpFilter`, `Request => Rx[Response]`) is trapped inside that module. JS and Native
only have HTTP **clients**. Airframe never made its server cross-platform either — but it *did* make the
server *abstraction* (lifecycle trait + handler/filter contract) reusable across backends.

**This PR delivers the foundation, not WebSocket itself:**
1. Lift the server abstraction (`HttpServer`, `RxHttpHandler`, `RxHttpFilter`, `HttpServerConfig`) into the
   shared `uni` cross-project.
2. Refactor `uni-netty` to implement the shared abstraction (JVM backend, behavior unchanged).
3. Add a **real Node.js (Scala.js) HTTP server backend** via `http.createServer`, proving the seam is
   genuinely cross-platform.

Long-term goal is **JVM + Node + eventual Native** (posix sockets). WebSocket and the Native server are
explicit follow-up PRs; the abstraction here is designed to accommodate both.

## Design

### 1. Shared abstraction — `uni/src/main/scala/wvlet/uni/http/`

**Move** (from `uni-netty`, package `wvlet.uni.http.netty` → shared, package `wvlet.uni.http`):
- `RxHttpHandler.scala` (`RxHttpHandler` + `RxHttpFilter`, currently `uni-netty/.../netty/RxHttpHandler.scala`).
  These are pure (`Request`/`Response`/`Rx` only) and already compile everywhere. Update all `uni-netty`
  imports accordingly.

**Add** `HttpServer.scala` — platform-neutral lifecycle trait:
```scala
trait HttpServer extends AutoCloseable:
  def localAddress: String   // "host:port"
  def localPort: Int
  def isRunning: Boolean
  def stop(): Unit
  def awaitTermination(): Unit
  override def close(): Unit = stop()
```

**Add** `HttpServerConfig.scala` — shared trait factoring the common config surface + the filter-chaining
logic currently duplicated as `NettyHttpServer.effectiveHandler`:
```scala
trait HttpServerConfig:
  def name: String
  def host: String
  def port: Int
  def handler: RxHttpHandler
  def filters: Seq[RxHttpFilter]

  // lifted from NettyHttpServer.effectiveHandler — reused by every backend
  def effectiveHandler: RxHttpHandler =
    if filters.isEmpty then handler
    else
      val chained = RxHttpFilter.chain(filters)
      RxHttpHandler(request => chained.apply(request, handler))

  def start(): HttpServer                    // backend-specific (covariant return)
  def start[A](block: HttpServer => A): A =  // shared convenience
    val s = start(); try block(s) finally s.stop()
```

### 2. JVM backend — refactor `uni-netty` (behavior unchanged)
- `NettyServerConfig extends HttpServerConfig` (it already has `name/host/port/handler/filters`); drop its
  bespoke handler-chaining and reuse the shared `effectiveHandler`. Keep all Netty-specific knobs
  (`maxContentLength`, `useNativeTransport`, graceful-shutdown, `handlerExecutorThreads`, …) and
  `start()` overriding with covariant return `NettyHttpServer`.
- `NettyHttpServer extends HttpServer`: implement `localAddress: String` as `s"${host}:${port}"` (keep the
  existing `InetSocketAddress`-typed accessor as a private/separate method); `localPort`, `isRunning`,
  `stop`, `awaitTermination` already exist.
- Files: `uni-netty/.../netty/{NettyServer,NettyHttpServer}.scala`, import fixes in
  `NettyRequestHandler.scala`, `RouterHandler.scala`, `RPCHandler.scala`.

### 3. JS (Node) backend — NEW, `uni/.js/src/main/scala/wvlet/uni/http/`
- `NodeServer.scala`: `object NodeServer` entry + `case class NodeServerConfig extends HttpServerConfig`
  mirroring NettyServer's common builders (`withName/withHost/withPort/withHandler/withRxHandler/withFilter/
  withFilters`, `start()/start(block)`).
- `NodeHttpServer.scala`: `class NodeHttpServer extends HttpServer`, backed by Node's built-in `http`:
  - Load `http` lazily via `getBuiltinModule("http")` with `require("http")` fallback — reuse the exact
    pattern in `NodeSyncHttpChannel.workerThreads` (`uni/.js/.../NodeSyncHttpChannel.scala:203`). Factor it
    into a tiny shared `NodeModules.builtin(name)` helper to avoid duplication.
  - `http.createServer((req, res) => …)`: collect the body via `req.on("data")`/`req.on("end")` into a
    `Buffer` → `Array[Byte]`, build a uni `Request` (method, url→uri, headers).
  - Run `config.effectiveHandler.handle(request): Rx[Response]`, consume with `RxRunner.runOnce`
    (`wvlet.uni.rx.RxRunner`, cross-platform). On `OnNext(resp)`:
    - if `resp.isEventStream`: `res.writeHead(200, text/event-stream + chunked)`, subscribe
      `resp.events` via `RxRunner.run`, `res.write` each formatted SSE event, `res.end` on completion —
      mirrors `NettyRequestHandler`'s SSE branch (`NettyRequestHandler.scala:79-160`).
    - else: `res.writeHead(status, headers)` + `res.end(bodyBytes)`.
  - `server.listen(port, host)`; `localPort` reads `server.address().port` (so `withPort(0)` ephemeral
    binding works); `stop()` → `server.close()`; `awaitTermination()` is a no-op (Node's event loop keeps
    the process alive while listening — documented); `isRunning` via a plain `var` (JS is single-threaded).

### 4. Designed-for-future (no code this PR, captured as comments/notes)
- **WebSocket** (next PR): both backends already expose an upgrade seam — Netty pipeline mutation in
  `NettyRequestHandler` (the SSE branch is the precedent), and Node `server.on("upgrade", …)`. WS will add
  shared `WebSocketHandler`/`WebSocketContext` traits + a `webSocketRoutes` list on `HttpServerConfig`,
  registered separately from the RPC router (matching airframe's design).
- **Native server** (later): implement `HttpServer` over posix sockets. The abstraction deliberately
  assumes neither Netty nor Node specifics so a `NativeHttpServer` slots in.

## Files

| Action | Path |
|---|---|
| Move + repackage | `uni-netty/.../netty/RxHttpHandler.scala` → `uni/src/main/scala/wvlet/uni/http/RxHttpHandler.scala` |
| Add | `uni/src/main/scala/wvlet/uni/http/HttpServer.scala` |
| Add | `uni/src/main/scala/wvlet/uni/http/HttpServerConfig.scala` |
| Refactor | `uni-netty/.../netty/{NettyServer,NettyHttpServer,NettyRequestHandler,RouterHandler,RPCHandler}.scala` (imports + trait impl) |
| Add | `uni/.js/src/main/scala/wvlet/uni/http/{NodeServer,NodeHttpServer}.scala` |
| Add | `uni/.js/src/main/scala/wvlet/uni/http/NodeModules.scala` (builtin-module loader helper) |
| Add | `uni/.js/src/test/scala/wvlet/uni/http/NodeServerTest.scala` |

No new sbt module; no new dependencies (Node `http` is built-in). `NodeServer` lives in the existing `uni`
cross-project `.js` tree alongside `FetchChannel`/`NodeSyncHttpChannel`.

## Verification

- `./sbt scalafmtAll` then `./sbt compile` (compiles JVM/JS/Native).
- `./sbt nettyJVM/test` (or the netty module's test task) — **existing `NettyServerTest` must pass
  unchanged**, proving the JVM refactor is behavior-preserving.
- New `uni/.js` test `NodeServerTest`: start `NodeServer.withPort(0).withRxHandler(...).start { server => … }`,
  then hit it with the **async** `FetchChannel` client
  (`Http.client.withBaseUri(s"http://localhost:${server.localPort}").newAsyncClient`) — async, not sync,
  because `NodeSyncHttpChannel` blocks the event loop and would deadlock against an in-process server.
  Assert: GET returns body+status, POST echoes the body, request/response headers round-trip, unmatched
  path → 404 (default handler). Run via `./sbt uniJS/test` (the JS test env is NodeJS, which can bind ports —
  `NodeSyncHttpChannelTest` already spins up a Node server in tests).
- `./sbt test` for the full cross-platform suite before pushing.

## Implementation notes / learnings

- **Scala 3 does not auto-import enclosing-package members.** Moving `RxHttpHandler`/`RxHttpFilter`
  from `wvlet.uni.http.netty` up to `wvlet.uni.http` required adding explicit imports in every netty
  file that names them (`NettyServer`, `NettyRequestHandler`, `RouterHandler`, `RPCHandler`, and the
  test) — a flat `package a.b.c` clause does not bring `a.b` members into scope.
- **Node's socket bind is asynchronous**, so `server.address()` returns null until the `listening`
  event fires. The shared `HttpServerConfig.start()` is synchronous and returns immediately; reading
  `localPort` or connecting before readiness fails. Resolved with `NodeHttpServer.whenReady: Rx[...]`
  (backed by a `Promise` completed in the listen callback) and the `NodeServerConfig.startAndAwait`
  convenience. This is the key cross-platform impedance mismatch — the abstraction is sync, one
  backend binds async.
- **Node rejects `Int8Array` response bodies.** `Array[Byte].toTypedArray` yields an `Int8Array`, but
  `res.end(...)` requires `string`/`Buffer`/`Uint8Array`. Fixed by viewing the bytes as a `Uint8Array`
  over the same buffer (`toUint8Array`).
- `start[A](block)` lives on the shared trait; backends override only `start(): HttpServer` with a
  covariant return type (`NettyHttpServer` / `NodeHttpServer`). Netty's previous bespoke
  `start[A](block)` and `effectiveHandler` were removed in favor of the shared ones.

## Out of scope (follow-up PRs)
- WebSocket handlers/routes (server + cross-platform client).
- Scala Native HTTP server.
- A unified `Http.server` auto-resolving factory (JVM's Netty backend lives in a separate module from
  `uni/.jvm`, so a compile-time default would be circular; explicit `NettyServer`/`NodeServer` entry points
  are used instead, which also matches how `uni-netty` is already consumed).

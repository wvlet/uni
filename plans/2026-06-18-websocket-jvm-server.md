# WebSocket Support — JVM (Netty) server + shared traits

## Context

The cross-platform HTTP server foundation just merged (#572): shared `HttpServer`/`HttpServerConfig`/
`RxHttpHandler` in `uni`, with a JVM Netty backend and a Node.js backend. This PR adds the first slice of
WebSocket support on top of it, mirroring airframe's recently-added design.

WebSocket is intentionally **not** forced through the `Request => Rx[Response]` router: WS connections are
stateful, long-lived, and bidirectional. Following airframe, WS routes are registered by path on the server
config (separate from RPC/router), with an optional per-route `RxHttpFilter` so auth/logging filters still
gate the HTTP upgrade handshake.

**Scope of this PR (decided):** shared callback-style WS traits + a JVM Netty WS server, tested end-to-end
with the JDK `java.net.http.WebSocket` client. **Out of scope (follow-ups):** Node.js WS server (needs
hand-written RFC 6455 framing — no built-in), and a cross-platform WS *client* (JVM `java.net.http` / JS
`scalajs-dom` / Native libcurl). The traits/config seam is designed so those slot in later.

Difficulty confirmed by research: JVM WS server is **easy** — `netty-codec-http` (already a dep) provides
`io.netty.handler.codec.http.websocketx.*` (handshake + framing). Node WS server is the hard part, hence
deferred.

## Design

### 1. Shared traits — `uni/src/main/scala/wvlet/uni/http/WebSocket.scala` (NEW)
Pure callback API (direct port of airframe; no frame ADT — text is `String`, binary is `Array[Byte]`):
```scala
trait WebSocketContext:
  def request: Request
  def send(text: String): Unit
  def send(data: Array[Byte]): Unit
  def close(): Unit
  def close(statusCode: Int, reason: String): Unit

trait WebSocketHandler:          // all no-op defaults; one instance per connection
  def onOpen(ctx: WebSocketContext): Unit = {}
  def onTextMessage(ctx: WebSocketContext, message: String): Unit = {}
  def onBinaryMessage(ctx: WebSocketContext, message: Array[Byte]): Unit = {}
  def onClose(ctx: WebSocketContext): Unit = {}
  def onError(ctx: WebSocketContext, e: Throwable): Unit = {}

case class WebSocketRoute(                 // shared so HttpServerConfig can hold a list of these
  path: String,
  handlerFactory: Request => WebSocketHandler,
  filter: RxHttpFilter = RxHttpFilter.identity
)
```
`WebSocketRoute` lives in shared `uni` (it only needs `Request`/`RxHttpFilter`/`WebSocketHandler`, all
shared) because `HttpServerConfig` references it.

### 2. Shared config seam — `uni/src/main/scala/wvlet/uni/http/HttpServerConfig.scala`
Add `def webSocketRoutes: Seq[WebSocketRoute] = Nil` (default keeps `NodeServerConfig` unchanged; the Netty
backend reads it generically). `withWebSocketRoute`/`withWebSocketMaxFrameSize` builders live on the
concrete `NettyServerConfig` (a Node builder is a follow-up).

### 3. JVM server — port airframe's Netty WS into `uni-netty/.../netty/`
**`NettyWebSocketHandler.scala` (NEW)** — direct port:
- `NettyWebSocketContext(channel, request, handshaker)`: `send(text)` → `channel.writeAndFlush(TextWebSocketFrame)`;
  `send(bytes)` → `BinaryWebSocketFrame(Unpooled.wrappedBuffer(...))`; `close(...)` →
  `handshaker.close(channel, CloseWebSocketFrame)` (thread-safe via Netty's `writeAndFlush`).
- `NettyWebSocketHandler extends SimpleChannelInboundHandler[WebSocketFrame]`: frame dispatch
  (Text→onTextMessage, Binary→`ByteBufUtil.getBytes`→onBinaryMessage, Close→notifyClose + echo,
  Ping→Pong, Pong→ignore); `closeNotified: AtomicBoolean` so `onClose` fires exactly once (from a Close
  frame or `channelInactive`); `notifyOpen`; `safeInvoke`/`safeOnError` route callback exceptions to
  `onError`; `exceptionCaught` uses the existing `NettyRequestHandler.isBenignIOException`
  (`uni-netty/.../NettyRequestHandler.scala`) to log benign disconnects at debug.

**`NettyRequestHandler.scala`** — add the upgrade path (the SSE branch is the precedent for mid-connection
pipeline mutation):
- Widen the constructor to also receive `webSocketRoutes: Seq[WebSocketRoute]`, `webSocketMaxFrameSize: Int`,
  and `wsHandlerExecutor: Option[EventExecutorGroup]` (the existing handler-executor group).
- At the top of `channelRead0`: if `webSocketRoutes` non-empty AND `isWebSocketUpgrade(nettyRequest)`
  (`Connection: Upgrade` + `Upgrade: websocket`, via `headers.containsValue(..., ignoreCase=true)`) AND a
  route matches `request.path` → `handleWebSocketUpgrade`; else the existing `handler.handle` path.
- `handleWebSocketUpgrade`: build the gate `RxHttpFilter.chain(serverFilters :+ route.filter)` applied to a
  terminal `RxHttpHandler(_ => Rx.single(Response.ok))`; `nettyRequest.retain()` to survive the async
  filter; `RxRunner.run` the result with an `AtomicBoolean handled` guard — 2xx → `doWebSocketHandshake`;
  non-2xx → write the response + release; `OnError` → 500 + release; `OnCompletion` (e.g. filter returns
  `Rx.empty`) → release + close. Buffer released exactly once on every path.
- `doWebSocketHandshake` (on the channel event loop): `WebSocketServerHandshakerFactory(webSocketLocation,
  null, true, webSocketMaxFrameSize).newHandshaker(msg)`; if null →
  `sendUnsupportedVersionResponse`; else build `NettyWebSocketContext` + `NettyWebSocketHandler`,
  `handshaker.handshake(channel, msg)`, then `pipeline.addLast("wsAggregator",
  WebSocketFrameAggregator(maxFrameSize))`, add the WS handler (on `wsHandlerExecutor` if set),
  `pipeline.remove(this)`, enqueue `notifyOpen()` on the WS handler's executor (so onOpen precedes any
  frame), close on failed-handshake future; `msg.release()` in `finally`.
- `webSocketLocation(ctx, req)`: `ws://` (or `wss://` if an `SslHandler` is present) + Host + uri.

**`NettyHttpServer.scala`** — pass `config.webSocketRoutes`, `config.webSocketMaxFrameSize`, and
`handlerExecutorGroup` into `NettyRequestHandler(...)`. Pipeline is otherwise unchanged (the
`HttpObjectAggregator` already produces the `FullHttpRequest` the upgrade needs).

**`NettyServer.scala` (`NettyServerConfig`)** — add fields `webSocketRoutes: Seq[WebSocketRoute] = Nil`
(override) and `webSocketMaxFrameSize: Int = 1024 * 1024`, plus builders:
`withWebSocketRoute(path)(factory)`, `withWebSocketRoute(path, filter)(factory)`,
`withWebSocketMaxFrameSize(n)`.

### 4. Why not `WebSocketServerProtocolHandler`
Netty's built-in protocol handler does its own handshake for a fixed path and can't be gated on an
`RxHttpFilter` — so (like airframe) we drive `WebSocketServerHandshakerFactory`/`WebSocketServerHandshaker`
manually and handle control frames ourselves, to keep auth/logging filters in front of the upgrade.

## Files

| Action | Path |
|---|---|
| Add | `uni/src/main/scala/wvlet/uni/http/WebSocket.scala` (`WebSocketContext`/`WebSocketHandler`/`WebSocketRoute`) |
| Edit | `uni/src/main/scala/wvlet/uni/http/HttpServerConfig.scala` (`webSocketRoutes` default Nil) |
| Add | `uni-netty/.../netty/NettyWebSocketHandler.scala` (context + frame bridge) |
| Edit | `uni-netty/.../netty/NettyRequestHandler.scala` (upgrade detect + handshake) |
| Edit | `uni-netty/.../netty/NettyHttpServer.scala` (thread WS config into the request handler) |
| Edit | `uni-netty/.../netty/NettyServer.scala` (`NettyServerConfig` WS fields + builders) |
| Add | `uni-netty/src/test/scala/wvlet/uni/http/netty/WebSocketTest.scala` |

No new dependencies (`netty-codec-http` already provides `websocketx.*`).

## Verification
- `./sbt scalafmtAll` then `./sbt netty/compile`.
- `WebSocketTest` (port airframe's, JDK `java.net.http.WebSocket` client driving a real `NettyServer` on an
  ephemeral port): echo text, echo binary, server push on `onOpen`, `onOpen`/`onClose` lifecycle, filter
  rejects upgrade (403 → connect fails), filter returning `Rx.empty` rejects (connection closed), fragmented
  message aggregation, works with `withHandlerExecutorThreads(n)`, and a regression test that normal HTTP
  handlers still work alongside WS routes.
- `./sbt netty/test` must stay green (existing 23 + new WS tests); `./sbt uniJVM/test uniJS/test
  uniNative/compile` to confirm the shared trait additions don't break other platforms.

## Out of scope (follow-up PRs)
- Node.js WS server via hand-written RFC 6455 framing (handshake via `crypto`, masking, opcodes) on the raw
  `server.on("upgrade")` socket — at which point a JS WS client becomes testable.
- Cross-platform WS client (`java.net.http.WebSocket` / `scalajs-dom` `WebSocket` / libcurl `curl_ws_*`).
- `Sec-WebSocket-*` `HttpHeader` constants (Netty handles handshake headers internally; the client/Node work
  will need them).

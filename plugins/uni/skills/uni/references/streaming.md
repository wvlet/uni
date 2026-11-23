# Server-sent events and WebSocket

| API | JVM | Scala.js | Native |
|-----|-----|----------|--------|
| SSE server (`Response.eventStream`) | `Http.server` (built-in or Netty) | `Http.server` (Node) | `Http.server` |
| SSE client (`sendSSE`) | streams | streams (Fetch) | buffered until the response ends |
| WebSocket server (`withWebSocketRoute`) | `Http.server` (built-in or Netty) | `Http.server` (Node) | `Http.server` |
| WebSocket client (`Http.webSocketClient`) | yes | browser, Node.js >= 22 | `ws://` only, IP or `localhost` |

Full reference: https://wvlet.org/uni/http/sse.md, https://wvlet.org/uni/http/websocket.md,
and the WebSocket section of https://wvlet.org/uni/http/server.md

## SSE server

Return `Response.eventStream(rx)` from a handler or an `@Endpoint` method. Each
`ServerSentEvent` is written as it is emitted; the stream ends when the `Rx`
completes.

```scala
import wvlet.uni.http.{Response, ServerSentEvent}
import wvlet.uni.http.Http
import wvlet.uni.rx.Rx

@main def sseServer(): Unit =
  Http
    .server
    .withPort(8080)
    .withRxHandler { request =>
      val events = Rx.fromSeq(1 to 3).map { i =>
        ServerSentEvent.data(s"tick ${i}").withId(i.toString).withEvent("tick")
      }
      Rx.single(Response.eventStream(events))
    }
    .start { server => server.awaitTermination() }
```

Also on `ServerSentEvent`: `withRetry(millis)`, `withData`, `noId`, `noEvent`,
`noRetry`, `toContent` (wire format). The JVM and Native servers cap concurrent
streams; over the cap clients get 503 (`Http.server.withMaxStreamingConnections(n)`,
default 256 on the JVM).

## SSE client

`sendSSE` is on the async client. It sets `Accept: text/event-stream`, sends
once (no retry), and fails with `HttpException` on a non-2xx status.

```scala
import wvlet.uni.http.{Http, Request, ServerSentEvent, ServerSentEventHandler}
import wvlet.uni.rx.Rx

val client = Http.client.withBaseUri("http://localhost:8080").newAsyncClient

// As an Rx of events
val events: Rx[ServerSentEvent] = client.sendSSE(Request.get("/events"))
events.run { e => println(s"${e.event.getOrElse("message")}: ${e.data}") }

// Or with a handler for the whole lifecycle
val handler = new ServerSentEventHandler:
  override def onEvent(e: ServerSentEvent): Unit = println(e.data)
  override def onCompletion(): Unit              = println("done")
client.sendSSE(Request.get("/events").withEventHandler(handler)).run()
```

`ServerSentEventHandler { e => ... }` builds a handler from `onEvent` alone; it
also has `onConnect(response)` and `onError(e)`. With the sync client,
`send(Request.get(...).withEventHandler(handler))` delivers a 2xx event stream
to the handler; on JVM it returns before the stream ends, so wait for
`onCompletion`. `ServerSentEventParser.parse(text)` parses SSE text without a
connection.

## WebSocket server

A route creates a fresh `WebSocketHandler` per connection; every callback
defaults to a no-op. The same code runs on every platform.

```scala
import wvlet.uni.http.{WebSocketContext, WebSocketHandler}
import wvlet.uni.http.Http

@main def wsServer(): Unit =
  Http
    .server
    .withPort(8080)
    .withAllowedOrigins("http://localhost:5173") // browser pages from another origin
    .withWebSocketRoute("/ws/echo") { request =>
      new WebSocketHandler:
        override def onOpen(ctx: WebSocketContext): Unit = ctx.send("welcome")
        override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
          ctx.send(s"echo: ${message}")
        override def onClose(ctx: WebSocketContext): Unit = println("closed")
    }
    .start { server => server.awaitTermination() }
```

- Cross-origin browser upgrades get 403 unless listed in `withAllowedOrigins`
  (`"*"` allows all). Clients that send no `Origin` are accepted.
- `withWebSocketRoute(path, filter) { ... }` gates the upgrade with an
  `RxHttpFilter`: a non-2xx response rejects it. Node.js and browser clients see
  a rejected upgrade only as close 1006; see readable rejections below.
- `onError` fires when a callback throws; a lost connection calls `onClose`
  (exactly once). Override `onClose(ctx, statusCode, reason)` for the close
  status: the first close frame sent or received, 1006 without one, and 1013 when
  a peer that stopped reading was dropped.
- `ctx.close(code, reason)` cuts a reason over 123 bytes and sends an invalid code
  as 1011.
- `withWebSocketMaxFrameSize(bytes)` (default 1 MB) bounds aggregated messages.

## Readable rejections and relays

`withCloseOnRejection()` makes a filter rejection accept the upgrade and close at
once with 4000 + the HTTP status (401 → 4401, an empty `Rx` → 4403), the body as
the reason. Origin and host checks still answer HTTP 403. `WebSocketRelay` pipes a
session to an upstream WebSocket with its own headers, and reports usage per session:

```scala
import wvlet.uni.http.{Request, Response, RxHttpFilter, WebSocketRelay, WebSocketRoute}
import wvlet.uni.rx.Rx

val auth = RxHttpFilter { (request, next) =>
  if request.header("Authorization").contains("Bearer app-token") then next.handle(request)
  else Rx.single(Response.unauthorized.withTextContent("sign in first"))
}

val realtime = WebSocketRoute(
  "/v1/realtime",
  _ =>
    WebSocketRelay(Request.get("wss://api.example.com/v1/realtime").withBearerToken("upstream-key"))
      .withStatsReporter(stats => println(s"${stats.durationMillis} ms, ${stats.closedBy}"))
      .newHandler(),
  auth
).withCloseOnRejection()

@main def relayServer(): Unit =
  Http.server.withPort(8080).withWebSocketRoute(realtime).start { server => server.awaitTermination() }
```

- Client frames sent before the upstream opens are queued up to
  `withMaxPendingBytes` (1 MiB); past it the client gets 1013.
- An unreachable or rejecting upstream closes the client with 4502
  (`upstream status 401` on JVM and Native). An upstream that doesn't finish its
  connect within the client's connect timeout (30 s) closes it with 4504; pass
  `withWebSocketClient(Http.webSocketClient.withConnectTimeoutMillis(10000))`
  for a shorter one.
- Closes are mirrored. Upstream codes other than 1000, 1001, and 3000–4999 reach
  the client as 4900 + (code − 1000), e.g. 1011 → 4911; `ctx.close` never remaps.
- `WebSocketRelayStats` has `durationMillis`, frames and bytes each way,
  `statusCode`, `reason`, and `closedBy` (`Client`, `Upstream`, `Relay`).

## WebSocket client

```scala reset
import wvlet.uni.http.{Http, WebSocketContext, WebSocketHandler}

val handler = new WebSocketHandler:
  override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
    println(s"received: ${message}")
    if message.startsWith("echo:") then ctx.close()

Http.webSocketClient
  .connect("ws://localhost:8080/ws/echo", handler)  // Rx[WebSocketContext], emitted once open
  .run { ctx => ctx.send("hello") }
```

`connect(url, handler, pingIntervalMillis = 30000)` adds a ping heartbeat on JVM
and Native (ignored on Scala.js). A handshake failure fails the `connect` stream.
A connect that takes over 30 s fails with `HttpErrorCode.ConnectionTimeout`; set
the limit with `Http.webSocketClient.withConnectTimeoutMillis(ms)` or remove it
with `noConnectTimeout()`.

To send upgrade headers such as `Authorization`, pass a `Request` instead of a URL
(JVM, Native, and Node.js; a browser can't send them, so there it fails):

```scala
import wvlet.uni.http.Request

Http.webSocketClient
  .connect(
    Request.get("ws://localhost:8080/ws/echo").addHeader("Authorization", "Bearer token"),
    handler
  )
  .run { ctx => ctx.send("hello") }
```

`ctx.send(bytes)` sends binary; `ctx.close(code, reason)` closes with a status.

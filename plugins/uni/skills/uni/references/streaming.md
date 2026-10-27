# Server-sent events and WebSocket

| API | JVM | Scala.js | Native |
|-----|-----|----------|--------|
| SSE server (`Response.eventStream`) | `NettyServer` | `NodeServer` | `NativeServer` |
| SSE client (`sendSSE`) | streams | streams (Fetch) | buffered until the response ends |
| WebSocket server (`withWebSocketRoute`) | `NettyServer` | `NodeServer` | `NativeServer` |
| WebSocket client (`Http.webSocketClient`) | yes | browser, Node.js >= 22 | `ws://` only, IP or `localhost` |

Full reference: https://wvlet.org/uni/http/sse.md, https://wvlet.org/uni/http/websocket.md,
and the WebSocket section of https://wvlet.org/uni/http/server.md

## SSE server

Return `Response.eventStream(rx)` from a handler or an `@Endpoint` method. Each
`ServerSentEvent` is written as it is emitted; the stream ends when the `Rx`
completes.

```scala
import wvlet.uni.http.{Response, ServerSentEvent}
import wvlet.uni.http.netty.NettyServer
import wvlet.uni.rx.Rx

@main def sseServer(): Unit =
  NettyServer
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
`noRetry`, `toContent` (wire format). Netty and Native cap concurrent streams;
over the cap clients get 503 (`.withMaxStreamingConnections(n)`, Netty default
256).

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
defaults to a no-op. `NodeServer` and `NativeServer` take the same builder calls.

```scala
import wvlet.uni.http.{WebSocketContext, WebSocketHandler}
import wvlet.uni.http.netty.NettyServer

@main def wsServer(): Unit =
  NettyServer
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
  `RxHttpFilter`: a non-2xx response rejects it.
- `onError` fires when a callback throws; a lost connection calls `onClose`
  (exactly once).
- `withWebSocketMaxFrameSize(bytes)` (default 1 MB) bounds aggregated messages.

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
`ctx.send(bytes)` sends binary; `ctx.close(code, reason)` closes with a status.

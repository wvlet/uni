# WebSocket Client

Uni includes a cross-platform WebSocket **client** that runs on the JVM, Scala.js,
and Scala Native. It connects to `ws://` / `wss://` endpoints and uses the same
`WebSocketHandler` / `WebSocketContext` abstraction as the
[server-side WebSocket](./server#websocket) support, so handler code is symmetric
on both ends.

## Connecting

`Http.webSocketClient` returns the platform's `WebSocketClient`. Call `connect`
with the URL and a handler; it returns an `Rx[WebSocketContext]` that emits the
open context once the handshake completes (or fails with the handshake error):

```scala
import wvlet.uni.http.{Http, WebSocketContext, WebSocketHandler}

val handler = new WebSocketHandler:
  override def onTextMessage(ctx: WebSocketContext, message: String): Unit =
    println(s"received: ${message}")

Http.webSocketClient
  .connect("ws://localhost:8080/ws/echo", handler)
  .subscribe { ctx =>
    ctx.send("hello")     // connection is open here
  }
```

The handler's `onOpen(ctx)` fires for the same event, so you can also do the
initial send there instead of in `subscribe`.

## Upgrade Headers

To authenticate, pass a `Request` instead of a URL. Its headers go on the HTTP
upgrade, and `ctx.request` returns it:

```scala
import wvlet.uni.http.{Http, Request}

val request = Request
  .get("wss://api.example.com/v1/realtime")
  .addHeader("Authorization", s"Bearer ${token}")

Http.webSocketClient.connect(request, handler)
```

Headers that the handshake sets itself (`Host`, `Upgrade`, `Connection`, and
`Sec-WebSocket-*`) fail the connect with an `IllegalArgumentException`.

::: warning Browsers can't send upgrade headers
The browser `WebSocket` API has no way to set headers, so in a browser a request
with headers fails the connect instead of dropping them. Pass a short-lived token
in the URL query instead. Node.js (≥ 22), the JVM, and Scala Native send the
headers.
:::

## Handler Callbacks

The client implements the same `WebSocketHandler` as the server. Every callback
has a no-op default, so override only what you need:

| Callback | When it fires |
|----------|---------------|
| `onOpen(ctx)` | the handshake completed |
| `onTextMessage(ctx, message)` | a text message arrived |
| `onBinaryMessage(ctx, message)` | a binary message arrived (`Array[Byte]`) |
| `onClose(ctx)` | the connection closed; delivered exactly once |
| `onClose(ctx, statusCode, reason)` | the same event, with the close status (see [Close Status](#close-status)); its default calls `onClose(ctx)` |
| `onError(ctx, e)` | a **handler callback** threw |

::: tip Errors vs. close
Transport/protocol failures map to `onClose`, not `onError` — `onError` is
reserved for exceptions thrown inside your own callbacks. A failure *before* the
connection opens surfaces through the `connect` stream (as an `Rx` error).
:::

## Sending and Closing

The `WebSocketContext` controls the connection; its `send`/`close` methods are
safe to call from any thread:

```scala
ctx.request                       // the original HTTP upgrade Request
ctx.send("hello")                 // text frame
ctx.send(Array[Byte](1, 2, 3))    // binary frame
ctx.close()                       // normal closure (1000)
ctx.close(1001, "going away")     // close with a status code and reason
```

A reason longer than 123 UTF-8 bytes, the limit for a close frame, is cut on a
character boundary. A code that may not be sent in a close frame (1004–1006,
1015, or outside 1000–4999) is sent as 1011. The JS client can only send 1000
and 3000–4999, as the `WebSocket` API requires, so it sends other codes as 1000.

## Close Status

Override `onClose(ctx, statusCode, reason)` to see why a connection closed:

```scala
val handler = new WebSocketHandler:
  override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
    if statusCode == 4401 then println("not signed in")
```

The status is that of the first close frame sent or received: the peer's when it
closed first, this side's when it did. It is 1006 when the connection ended
without a close frame, and 1013 ("try again later") when this side dropped a
peer that stopped reading.

Codes a Uni server sends on its own:

| Code | Meaning |
|------|---------|
| 4000 + HTTP status (4401, 4403, …) | The route filter rejected the upgrade, on a route with [readable rejections](./server#readable-rejections) |
| 4502 | A [relay](#relaying-a-session) couldn't reach its upstream |
| 4900–4999 | A relay's upstream closed with a code the client can't be given, such as 1011 (4911) |
| 1013 | A peer stopped reading, or a relay's pre-open queue overflowed |

A browser reports a close during the handshake as 1006. A client that closes on
its own while connecting should treat that 1006 as a normal close.

## Relaying a Session

`WebSocketRelay` is a server handler that relays one client session to an
upstream WebSocket server, text and binary frames both ways. Register it with
`newHandler()` as a route's handler; see
[Relaying to an Upstream](./server#relaying-to-an-upstream) for a full server.

```scala
import wvlet.uni.http.{Request, WebSocketRelay}

val relay = WebSocketRelay(
  Request.get("wss://api.example.com/v1/realtime").withBearerToken(apiKey)
)
  .withMaxPendingBytes(1024 * 1024)  // queue for client frames until the upstream opens
  .withStatsReporter(stats => println(stats))
```

| Event | What the relay does |
|-------|---------------------|
| Client frames before the upstream opens | Queued in order, up to `maxPendingBytes` (1 MiB by default); past it the client is closed with 1013 |
| Upstream connect or handshake fails | The client is closed with 4502, reason `upstream status 401` when the upstream answered with a status (JVM and Native), else `upstream connect failed` |
| Client closes | The upstream is closed with the same code and reason; a dropped client (1006) closes it with 1001 |
| Upstream closes with 1000, 1001, or 3000–4999 | The client is closed with the same code |
| Upstream closes with another code | 1002–1099 map to 4902–4999 (1006 → 4906, 1011 → 4911), anything else to 4900 |
| An error on either side | That side is closed with 1011, which closes the other |

`ctx.close` itself never remaps codes; only the relay maps an upstream code it
copies to the client. Remapping in `ctx.close` would rewrite an app's
legitimate `close(1011, ...)`.

When the client connection closes, the stats reporter gets one
`WebSocketRelayStats` for the session:

| Field | Meaning |
|-------|---------|
| `durationMillis` | Wall-clock time from the client's open to its close, e.g. for per-minute audio billing |
| `framesToUpstream`, `bytesToUpstream` | Client frames sent to the upstream, and their payload bytes |
| `framesToClient`, `bytesToClient` | Upstream frames sent to the client |
| `statusCode`, `reason` | The close status of the side that closed first, as it reported it (the upstream's own code, before mapping) |
| `closedBy` | `WebSocketRelaySide.Client`, `Upstream`, or `Relay` (connect failure or queue overflow) |

The relay decides nothing about auth or billing: authenticate the client in the
route filter, and meter from the stats.

## Slow Peers

A peer that stops reading can't make a connection hold memory or a thread
forever:

| Backend | Limit | Then |
|---------|-------|------|
| JVM client (`java.net.http`), Netty server, Node server | 16 MiB of queued sends | the connection is dropped; `onClose` reports 1013 |
| Native client, built-in JVM server, Native server | a send that makes no progress for 30 s | the connection is dropped; `onClose` reports 1013 |

Audio and other real-time streams can't slow down at the source, so dropping the
connection beats queueing more data than the peer will ever catch up with.

## Heartbeat (ping/pong)

A client-side ping/pong heartbeat detects a dead-but-idle connection. It is
**off by default** and enabled by passing a ping interval (in milliseconds) as
the third argument to `connect`:

```scala
// Ping an idle connection every 30s; close it if the peer stops responding.
Http.webSocketClient.connect("wss://example.com/ws", handler, pingIntervalMillis = 30000)
```

When enabled, an idle interval sends a `Ping`; if the next interval is still idle
with the ping unanswered, the client closes the connection (status `1011`, "ping
timeout"). Any inbound frame proves liveness and clears a pending ping.

::: warning Not available on Scala.js
The browser/Node global `WebSocket` API cannot send protocol pings, so
`pingIntervalMillis` is **ignored on Scala.js**. Use it on JVM/Native, or
implement application-level keepalive messages if you need liveness detection in
the browser.
:::

## Platform Support

| Platform | Backend | Transport | Notes |
|----------|---------|-----------|-------|
| **JVM** | `JavaWebSocketClient` | `java.net.http.WebSocket` (Java 11+) | `ws://` and `wss://`; heartbeat supported |
| **Scala.js** | `JSWebSocketClient` | global `WebSocket` | browsers or **Node.js ≥ 22**; `pingIntervalMillis` ignored |
| **Scala Native** | `NativeWebSocketClient` | raw POSIX socket | `ws://` only (no TLS); host must be an IP or `localhost` (no DNS); max frame 1 MB |

`Http.webSocketClient` resolves to the right backend automatically. On a platform
without WebSocket support the base client throws
`NotImplementedError`, but all three shipped platforms provide a backend.

## See Also

- [Server-side WebSocket](./server#websocket) — register `WebSocketHandler`
  routes, readable rejections, and relays on any Uni server.
- [Server-Sent Events](./sse) — one-way server→client streaming when you don't
  need a bidirectional channel.

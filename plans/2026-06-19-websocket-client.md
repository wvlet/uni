# Cross-platform WebSocket client (PR 1: shared API + JVM)

## Context
The server side has WebSocket on all three backends, but there was no WebSocket *client*. This is the
first of three PRs (JVM, then JS, then Native), delivered JVM-first so the public API can be reviewed
before the other backends are built.

## Design
Reuse the existing shared `WebSocketHandler`/`WebSocketContext` so client and server handler code is
symmetric (a client connection IS-A WebSocketContext whose `request` is the connect request).

- **`WebSocketClient` (shared)** — `connect(uri, handler): Rx[WebSocketContext]`. The Rx emits the
  open connection (for send/close) once the handshake completes, or fails with the handshake error;
  `handler.onOpen` fires for the same event.
- **`HttpChannelFactory.newWebSocketClient: WebSocketClient`** — new factory method with a default
  that throws `NotImplementedError` (JS/Native inherit it until implemented; no per-platform change
  needed yet). Surfaced as `Http.webSocketClient`.
- **JVM (`JavaWebSocketClient`)** — backed by `java.net.http.WebSocket` (handshake/masking/framing
  handled by the JDK). A `WebSocket.Listener` reassembles text/binary fragments (on `last`),
  delivers to the handler, and guarantees `onClose` exactly once; `onOpen` calls `request(MaxValue)`.
  The open connection is bridged to `Rx` via a `Promise[WebSocketContext]` + `Rx.future` (RxRunner
  runs `Rx.future`; it does *not* run an `RxDeferred.get` node — that was the initial mistake).
  Registered in `JVMHttpChannelFactory.newWebSocketClient`.

## Verification
`uni-netty` test starts a `NettyServer` with a `withWebSocketRoute` echo/push route and drives the new
client over `ws://localhost:port`: text echo, server push on open, and onClose on disconnect. JVM
suite 1635 passed; JS/Native compile (inherit the throwing default).

## Follow-ups (this feature)
PR 2 — JS backend (global `WebSocket`). PR 3 — Native backend (raw socket + the shared codec extended
to client mode: mask outbound frames, decode unmasked server frames). Also: WS ping/pong heartbeat,
permessage-deflate.

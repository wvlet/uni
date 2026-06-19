# Native HTTP server — WebSocket (PR 2 of 2)

## Context

Completes WebSocket support across all server backends (Netty #573, and now Native). Builds on the
Native HTTP server (#574) and its SSE streaming (#575), reusing the shared `WebSocketContext`/
`WebSocketHandler`/`WebSocketRoute` traits from #573.

`java.security.MessageDigest` is **absent** on Scala Native, so the handshake uses a small pure-Scala
SHA-1 + `java.util.Base64` (present on Native). Framing is hand-written RFC 6455 over the raw POSIX
socket (no dependency).

## Design (all in `uni/.native/.../http/`)

- **`Sha1`** — dependency-free SHA-1 (RFC 3174) for the accept key.
- **`NativeWebSocket`** — `acceptKey(key)` = base64(SHA1(key + GUID)); `encodeFrame(opcode, payload)`
  (unmasked server frames); `serve(fd, request, handler, maxFrameSize)`: delivers `onOpen`, then a
  read loop that parses masked client frames (FIN/opcode, 7/16/64-bit length, mask key, unmask),
  reassembles fragments, dispatches text/binary, auto-replies to ping, ignores pong, echoes/handles
  close, and guarantees `onClose` exactly once. `NativeWebSocketContext` writes frames under a lock
  (so `send`/`close` are thread-safe alongside the loop's pong/close writes) and drops sends after
  close. Oversized frames → close 1009.
- **`NativeServerConfig`** — `webSocketRoutes` (override, default Nil) + `webSocketMaxFrameSize` +
  `withWebSocketRoute(path[, filter])(factory)`; the `NativeServer` object exposes the same entry
  points.
- **`NativeHttpServer`** — `handleConnection` detects an `Upgrade: websocket` request matching a
  route; `handleWebSocketUpgrade` gates it through the route filter (2xx allows, else the response
  rejects — reusing an extracted `awaitRx`), writes the `101 Switching Protocols` handshake, then
  hands the connection to `NativeWebSocket.serve` (which owns the worker for the connection's life).
- **`HttpHeader`** (shared) — added `Sec-WebSocket-Key/Accept/Version/Protocol` constants.

## Files
| Action | Path |
|---|---|
| Add | `uni/.native/.../http/Sha1.scala` |
| Add | `uni/.native/.../http/NativeWebSocket.scala` (framing + handshake + context + serve loop) |
| Edit | `uni/.native/.../http/NativeServer.scala` (WS config fields + builders + object entry points) |
| Edit | `uni/.native/.../http/NativeHttpServer.scala` (upgrade detect + filter-gate + handshake; `awaitRx`) |
| Edit | `uni/src/main/scala/wvlet/uni/http/HttpHeader.scala` (Sec-WebSocket-* constants) |
| Edit | `uni/.native/src/test/.../NativeServerTest.scala` (raw-socket `WsClient` + WS tests) |

## Verification
- `./sbt scalafmtAll`, `uniNative/test`; `uniJVM/compile uniJS/compile netty/compile` for the shared
  `HttpHeader` change.
- WS tests via a raw-socket `WsClient` (handshake, masked text frame, server-frame read): accept-key
  matches the RFC 6455 example vector, text echo, server push on `onOpen`, `onOpen`/`onClose`
  lifecycle (latches), and filter-rejected upgrade (403). Native suite: 1378 tests, 0 failed.

## Limitations / follow-ups (inherited from the blocking model)
- A WebSocket connection pins a worker thread for its lifetime; an idle client disconnect is detected
  only on the next read/write (a portable idle/read timeout is the standing follow-up — posixlib's
  `timeval` is mislaid out on macOS, see #574/#575 notes).
- No per-message compression (permessage-deflate) or client-side WebSocket.

# WebSocket client — Native backend (PR 3 of 3)

## Context
Final WebSocket-client PR (after JVM #581, JS #582). Native has no built-in WebSocket, so the client
is hand-rolled over a raw POSIX socket, reusing the shared codec — which is generalized here to
client mode.

## Changes
- **Shared codec client-mode** (used by the Native client; server unchanged):
  - `WebSocketFrameDecoder(maxFrameSize, expectMasked = true)` — `expectMasked=false` accepts the
    unmasked server->client frames a client receives and rejects a masked one (1002); the unmask
    step is skipped when unmasked. Server callers keep the masked-required default.
  - `WebSocketFrame`: `encodeMaskedFrame(opcode, payload, mask)` for client sends, `newMaskingKey()`
    (4 bytes), `newClientKey()` (base64 of 16 random bytes for `Sec-WebSocket-Key`), and a shared
    `frameHeader` helper. Cross-platform decoder tests cover client-mode decode + masked-frame reject.
- **`NativeSocket.connect(host, port)`** — open a client TCP connection (IPv4/"localhost").
- **`NativeWebSocketClient`** — connect, send the upgrade GET (random key), validate the 101 +
  `Sec-WebSocket-Accept`, then drive `WebSocketFrameDecoder(expectMasked=false)` on a daemon reader
  thread bridging to the handler (via the shared `WebSocketDispatcher`); `onOpen` before the reader
  starts, `onClose` exactly once. `NativeWebSocketClientContext` masks every outbound frame and
  serializes writes. `ws://` only (no TLS on the raw socket). Registered in
  `NativeHttpChannelFactory.newWebSocketClient`.

## Verification
`NativeWebSocketClientTest` drives the Native client against the in-process `NativeServer` WS
echo/push route (text echo, push-on-open, onClose). All platforms green: JVM 1637 / JS 1389 /
Native 1398 / Netty 37. The server suites confirm the codec generalization didn't regress the
masked (server) path.

## Result
WebSocket client now works on all three backends (JVM/Netty java.net.http, JS global WebSocket,
Native raw socket + shared codec), on the same `WebSocketClient`/`WebSocketHandler`/`WebSocketContext`.

## Remaining follow-ups
WS ping/pong heartbeat (server + client); permessage-deflate.

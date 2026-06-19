# WebSocket hardening (Sec-WebSocket-Version + Node backpressure)

## Context
Follow-up hardening for the WebSocket servers (Native #576, Node #577). Two gaps noted at merge:
1. Neither manual backend validated `Sec-WebSocket-Version` (RFC 6455 says reply `426` advertising the
   supported version on a mismatch).
2. Node ignored `socket.write()` backpressure (could buffer unbounded for a slow consumer).

## Changes
- **`WebSocketHandshake` (shared)** — `validate(request): Either[Response, String]`: missing/empty
  `Sec-WebSocket-Key` → 400; `Sec-WebSocket-Version` absent → 400, present-but-not-13 → 426 (with
  `Sec-WebSocket-Version: 13`); otherwise `Right(key)`. Dedups the key check and adds the version
  check across both manual backends. Native (`NativeHttpServer.handleWebSocketUpgrade`) and Node
  (`NodeWebSocket.handleUpgrade`) now call it.
- **Node backpressure** — `NodeWebSocketContext.writeFrame` pauses the socket when `write()` returns
  false; `accept` wires `'drain'` → `socket.resume()`. Bounds memory under a slow consumer.
- **Node `writeHttpClose`** now copies the response's own headers (so the 426's `Sec-WebSocket-Version`
  reaches the client; Native's `serialize` already did).

## Verification
`scalafmt`; full JVM/JS/Native suites + Netty compile. New tests: Native + Node "unsupported version
→ 426 (advertising 13)". JVM 1635 / Native 1388 / JS 1387 pass.

## Follow-ups (remaining)
Portable Native idle/read timeout; Native libcurl client bug; cross-platform WebSocket client;
permessage-deflate.

# WebSocket close status, upgrade headers, and slow peers

## Context

Realtime voice transcription is the use case for WebSocket in Uni: a relay server sits between clients and a model's realtime WebSocket API, and a push-to-talk client streams audio to it. LLM chat stays on server-streaming RPC over SSE (`2026-11-06-rpc-server-streaming.md`). Such apps hit four gaps in the shared `WebSocketHandler`/`WebSocketClient` API:

- `WebSocketClient.connect` took only a URL, so a client couldn't send `Authorization` on the upgrade.
- `onClose(ctx)` carried no status, and the JS client dropped the `CloseEvent`, so an app couldn't tell "not allowed" from "network down".
- Close reasons weren't capped. `WebSocketFrame.closePayload` let the built-in, Native, and Node servers and the Native client send a close frame over the 125-byte control-frame limit, and the JS `ws.close` throws over 123 bytes. Codes weren't checked either: Netty and the JDK client throw on invalid ones.
- A peer that stopped reading behaved differently on each backend. Netty's `writeAndFlush`, the JDK client's send chain, and the Node server (pausing reads doesn't stop the app's own sends) queued without limit. The socket servers' write watchdog (`2026-11-18-socket-server-netty-parity.md`) already bounded a stalled write, but the Native client's writes blocked with no timeout.

## Decision

- **Connect with a `Request`.** `WebSocketClient.connect(request, handler, pingIntervalMillis)` is the abstract method, and the URL forms delegate to it. The request's headers go on the upgrade on the JVM, Native, and Node.js (undici's `headers` option), and `ctx.request` returns the request. Headers the handshake sets itself (`Host`, `Upgrade`, `Connection`, `Sec-WebSocket-*`) fail the connect with `IllegalArgumentException` on every platform, rather than being dropped on one and rejected by the JDK on another. A browser can't send upgrade headers, so there a request with headers fails the connect, and the docs point to a token in the URL query.
- **`onClose(ctx, statusCode, reason)`.** The new overload's default calls `onClose(ctx)`, so existing handlers compile and behave as before. Every backend delivers it through one `WebSocketCloseState`. The first recorded status wins: the peer's close frame, or this side's `close`. Without either, the code is 1006. This matches what a browser reports in the common cases, and it makes a close during the handshake report the 1000 the app asked for instead of the runtime's 1006. A close frame without a status reads as 1000, as the frame decoder already treated it.
- **Close hygiene in one place.** `WebSocketClose.truncateReason` cuts reasons to 123 UTF-8 bytes on a character boundary, and `sendableCode` sends a code that may not be sent (1004–1006, 1015, outside 1000–4999; `WebSocketFrameDecoder.isValidCloseCode`) as 1011. `closePayload` applies both, which covers the socket and Node backends. Netty and the JDK client apply them before calling their libraries. Valid codes such as 1011 or 1013 pass through unchanged: remapping every reserved code into 49xx would rewrite an app's legitimate `close(1011, ...)`, so that mapping belongs in a relay that copies an upstream code to a client. Two runtime limits stay: the JDK client can't send 1012–1014, so it sends 1011, and the JS `WebSocket` API accepts only 1000 and 3000–4999, so the JS client sends other codes as 1000.
- **Drop a peer that stops reading, and report 1013.** Backends that queue sends drop the connection past 16 MiB queued (`WebSocketClose.MaxPendingWriteBytes`). Netty counts the channel's pending outbound bytes, which include writes still queued from other threads. The JDK client counts the bytes of sends that haven't completed, and the Node server reads the socket's `writableLength`. Blocking backends use the 30 s stalled-write watchdog. The socket servers' existing one now marks the connection, and the Native client gets its own daemon watchdog over the shared `WriteTrackingConnection`. `onClose` then reports 1013 ("try again later") on this side. The peer, which isn't reading, sees the connection end. Real-time audio can't slow down at its source, so dropping beats queueing data the peer will never catch up with. The limits are constants rather than options: no app has needed to tune them yet, and they can become options without changing behaviour.

## Alternatives

- **Remap reserved codes in `ctx.close`.** Rejected: it changes codes the app is allowed to send. See the decision above.
- **One byte limit on every backend.** The socket backends write synchronously, so their queue is always near empty. Their failure is a blocked thread, which a stalled-write timeout bounds.
- **Pass upgrade headers as a separate `Seq` argument.** It would add one more overload. A `Request` already carries validated headers and is what `ctx.request` returns, and an upgrade request is an HTTP request.

## Consequences

- `WebSocketRelay` and readable rejections (a filter rejection answered as close 4000 + status) follow in a second PR and build on these primitives.
- On the JVM, the JDK's `abort()` doesn't call the listener, so the client delivers `onClose` itself after it drops a server for not reading.

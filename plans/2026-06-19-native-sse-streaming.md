# Native HTTP server — SSE / chunked streaming (PR 1 of 2)

## Context

The Scala Native HTTP server (#574) handles request/response with `Content-Length` bodies but does
**not** stream: a `Response.eventStream(...)` (Server-Sent Events) would be serialized as an empty
body. The JVM (Netty) and Node.js backends both stream SSE. This PR adds chunked transfer encoding +
SSE streaming to the Native backend, closing that cross-platform gap. (WebSocket on Native is the
separate follow-up PR 2.)

## Design

All changes in `uni/.native/src/main/scala/wvlet/uni/http/`, reusing the shared `Response`
(`events: Rx[ServerSentEvent]`, `isEventStream`), `ServerSentEvent.toContent`, `Rx`/`RxRunner`, and
`Cancelable` — all cross-platform and already used on Native.

### 1. `HttpResponseWriter` (NativeHttpProtocol.scala) — chunked helpers
- `serializeSseHeaders(response): Array[Byte]` — status line + the response's custom headers (minus
  managed ones) + `Content-Type: text/event-stream`, `Cache-Control: no-cache`,
  `Transfer-Encoding: chunked`, the keep-alive/close `Connection` header, and the blank line. No
  `Content-Length` (chunked). Mirrors `NodeHttpServer.writeSseResponse`/`NettyRequestHandler.sendSseResponse`.
- `chunk(data: Array[Byte]): Array[Byte]` — one chunk: `"<hex-len>\r\n"` + data + `"\r\n"`.
- `finalChunk: Array[Byte]` — `"0\r\n\r\n"` (end of chunked body).

### 2. `NativeHttpServer` — stream when `response.isEventStream`
In `handleConnection`, branch the `ReadResult.Req` case: if `response.isEventStream`, call
`streamSse(clientFd, response)` instead of the buffered `serialize` path.

`streamSse(clientFd, response): Boolean`:
- Write `serializeSseHeaders(response)`.
- Subscribe to `response.events` with `RxRunner.run` (not `runOnce` — many events), blocking the
  worker on a `CountDownLatch` until terminal:
  - `OnNext(event)` → `sendAll(clientFd, chunk(event.toContent bytes))`; if the write fails (client
    gone) cancel the subscription and finish.
  - `OnError` → log and finish; `OnCompletion` → finish.
  - Use the **delegate-`Cancelable` + `cancelled`-flag** pattern (as in `NodeHttpServer.writeSseResponse`)
    so a write failure during a *synchronous* emission (`Rx.fromSeq`) cancels correctly before the
    subscription handle is assigned.
- After the stream terminates, write `finalChunk` (best-effort) and return whether all writes
  succeeded.
- The connection then follows the normal keep-alive decision (chunked framing delimits the message,
  so keep-alive is valid); a dead client (`sent == false`) closes it.

Note: an SSE connection occupies its worker thread for the stream's lifetime (no handler-await
timeout is applied to the streaming phase — SSE is long-lived by design); a client disconnect is
detected via a failed chunk write and cancels the stream.

### 3. SHA-1/Base64, sockets — unchanged
No new dependency, no config change. (The WebSocket PR will add a pure-Scala SHA-1 + `java.util.Base64`
handshake — `java.security.MessageDigest` is **not** available on Native; that's PR 2.)

## Files
| Action | Path |
|---|---|
| Edit | `uni/.native/.../http/NativeHttpProtocol.scala` (`HttpResponseWriter`: `serializeSseHeaders` + `chunk`/`finalChunk`) |
| Edit | `uni/.native/.../http/NativeHttpServer.scala` (`handleConnection` SSE branch + `streamSse`) |
| Add | `uni/.native/src/test/.../NativeServerSseTest.scala` (or extend `NativeServerTest`) |

## Verification
- `./sbt scalafmtAll` then `./sbt uniNative/compile`.
- New test: `NativeServer.withRxHandler(_ => Rx.single(Response.eventStream(Rx.fromSeq(events)))).start { ... }`,
  then a raw POSIX-socket client (the existing `NativeServerTest` pattern, reusing `NativeSocket`)
  reads the full response and **de-chunks** it (parse `<hex>\r\n<data>\r\n` … `0\r\n\r\n`), asserting
  the SSE wire form (`data: hello`, `data: world`, etc.) and `Transfer-Encoding: chunked`. (The
  in-module libcurl client can't be used — it has the `CURLE_URL_MALFORMAT` bug found in #574.)
- `./sbt uniNative/test` (full Native suite stays green); the existing non-streaming tests are
  unaffected (the SSE branch only triggers for `isEventStream`).

## Follow-up: PR 2 — Native WebSocket (sketch)
- `withWebSocketRoute` on `NativeServerConfig` consuming the shared `webSocketRoutes`
  (`HttpServerConfig`, default Nil); detect the `Upgrade: websocket` request in `handleConnection`.
- Handshake: `Sec-WebSocket-Accept = Base64(SHA1(key + GUID))` — **pure-Scala SHA-1** (~60 lines,
  dependency-free; `MessageDigest` is absent on Native) + `java.util.Base64` (present on Native);
  write `101 Switching Protocols`.
- RFC 6455 framing over the raw socket: parse masked client frames (FIN/opcode, mask key, payload),
  unmask; handle text/binary/close/ping(→pong)/pong; write unmasked server frames; bridge to the
  shared `WebSocketContext`/`WebSocketHandler` (reuse from #573, mirroring `NettyWebSocketHandler`).

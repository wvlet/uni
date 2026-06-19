# Node.js WebSocket server (shared RFC 6455 codec)

## Context

The cross-platform HTTP server now supports WebSocket on JVM (Netty, #573) and Scala Native (#576),
but **not Node.js** — Node's `http.Server` has no built-in WebSocket, so it was deferred. This PR
closes that gap: it adds a WebSocket server to the Node backend by hand-rolling the handshake +
framing on the raw `'upgrade'` socket, exactly as the Native backend does.

Rather than duplicate the intricate, security-sensitive RFC 6455 parser (the Native one accrued 7
review findings), the plan **extracts a shared, tested frame codec** that both Native and Node drive
— so there is one parser to maintain. Research confirmed `Sha1` (pure Scala) and `java.util.Base64`
both compile/run on Scala.js 1.21, so the handshake accept-key is fully shareable.

Outcome: all three server backends (JVM/Node/Native) support request/response, SSE streaming, and
WebSocket on the one shared abstraction.

## Design

### 1. Shared codec — new files in `uni/src/main/scala/wvlet/uni/http/` (cross-compiled JVM/JS/Native)

- **`Sha1.scala`** — **move** verbatim from `uni/.native/.../http/Sha1.scala` (pure `ArrayBuffer` +
  `Integer.rotateLeft`). **Delete the Native copy in the same change** (two `wvlet.uni.http.Sha1`
  on the Native classpath = duplicate-definition compile error).
- **`WebSocketFrame.scala`** (`private[http] object`) — moved from `NativeWebSocket`: opcode
  constants (`OpContinuation/OpText/OpBinary/OpClose/OpPing/OpPong`), `MagicGuid`,
  `acceptKey(key)` = `Base64(Sha1(key+GUID))`, `encodeFrame(opcode, payload)` (unmasked server
  frame), and `closePayload(code, reason)` (2-byte BE code + UTF-8 reason, extracted from
  `NativeWebSocketContext.close`).
- **`WebSocketFrameDecoder.scala`** (`private[http] class`, stateful) — the parser extracted from
  the Native `serve` loop. Holds `buf`/`fragments`/`fragmentOpcode`/`terminated`.
  ```scala
  enum WsEvent:
    case Message(opcode: Int, data: Array[Byte])  // OpText|OpBinary, reassembled
    case Ping(data: Array[Byte]); case Pong(data: Array[Byte])
    case PeerClose(code: Int, reason: String)     // close code pre-resolved (>=2 bytes ? BE16 : 1000)
    case Fail(code: Int, reason: String)          // terminal protocol/size violation
  def feed(bytes: Array[Byte])(emit: WsEvent => Unit): Boolean  // false once terminal
  ```
  **Behavior must match the current Native loop exactly:**
  - **Truncated frame ⇒ buffer and return `true` (need more bytes), never `Fail`.** This is the key
    regression trap — frames span multiple `recvChunk`/`data` chunks. Only emit `Fail`/`PeerClose`
    on a fully-parsed violation/close.
  - Internally loop over all complete frames in `buf` per `feed` (multiple frames per chunk).
  - Masking is **hardcoded required** (server decodes only client→server). `!masked ⇒ Fail(1002)`.
  - **Validation order (pin it):** `len<0 || len>maxFrameSize ⇒ Fail(1009)`; then `rsv!=0 ||
    !masked ⇒ Fail(1002)`; then `isControl && (!fin || len>125) ⇒ Fail(1002)`. (Oversized+unmasked
    ⇒ 1009, not 1002.)
  - `maxFrameSize` is **both** per-frame (1009) **and** per-message reassembly cap (1009).
  - Fragmentation: lone continuation ⇒ Fail(1002); data frame mid-fragmentation ⇒ Fail(1002).

- **`HttpServerConfig.scala`** — update the now-stale `webSocketRoutes` doc comment (Native already
  consumes it; Node will too). No code change.

### 2. Native refactor — `uni/.native/.../http/NativeWebSocket.scala`

- Drop local `Op*`/`MagicGuid`/`acceptKey`/`encodeFrame` → delegate to `WebSocketFrame`.
- `serve(...)` becomes a thin driver: `onOpen`; `while open { chunk=recvChunk; if empty break; open
  = decoder.feed(chunk){ ev => dispatch(ev) } }`; `finally notifyClose()` (keep the `AtomicBoolean`
  exactly-once). Dispatch mapping (preserve order + reason strings):
  | `WsEvent` | action |
  |---|---|
  | `Message(OpText,d)` / `Message(OpBinary,d)` | `deliver(...)` → `onTextMessage`/`onBinaryMessage` |
  | `Ping(d)` | `ctx.sendPong(d)` |  `Pong` | ignore |
  | `PeerClose(code,_)` | `notifyClose()` **then** `ctx.close(code,"")` (onClose before echo) |
  | `Fail(code,reason)` | `ctx.close(code,reason)` (onClose via `finally`) |
- `NativeWebSocketContext` keeps `writeLock`/`AtomicBoolean` (**Native is genuinely multi-threaded**);
  swap `encodeFrame`/`Op*` → `WebSocketFrame.*`; `close` uses `WebSocketFrame.closePayload`.

### 3. Node backend — `uni/.js/src/main/scala/wvlet/uni/http/`

- **`NodeBytes.scala`** (new `private[http]`) — extract `toBytes(chunk): Array[Byte]` /
  `toUint8Array(bytes): Uint8Array` out of `NodeHttpServer` so both it and `NodeWebSocket` reuse them.
- **`NodeWebSocket.scala`** (new) — the `'upgrade'` handler + `NodeWebSocketContext`. Lifecycle:
  1. Build `Request` from `req.method/url/rawHeaders` (reuse the `rawHeaders` loop; bodyless GET).
  2. No matching route ⇒ `socket.destroy()`. Missing/empty `Sec-WebSocket-Key` ⇒ write `400` +
     `socket.end()`.
  3. Filter-gate via `RxRunner.runOnce(route.filter.apply(request, gate))` (Node is async — NOT the
     blocking `awaitRx`). `gate` captures the filter-threaded request (plain `var`). `OnNext`
     success ⇒ `accept()`; non-2xx ⇒ write that response + `end()`; `OnError` ⇒ 500 + `end()`;
     `OnCompletion` (no verdict) ⇒ `socket.destroy()` (Netty parity).
  4. `accept()`: write `101` (`WebSocketFrame.acceptKey(key)`); build `NodeWebSocketContext(socket,
     req)`; `handler.onOpen(ctx)` **before** wiring data; if `head.length>0` feed it first; then
     `socket.on("data", c => drive(toBytes(c)))`, `socket.on("close"/"error", _ => notifyClose())`.
  5. `drive(bytes)`: `if !closed { if !decoder.feed(bytes)(dispatchNode) then socket.end() }`.
  - `NodeWebSocketContext`: **plain `var closed`, no AtomicBoolean/lock** (single-threaded event
    loop). `send`/`close`/`sendPong` write `socket.write(toUint8Array(WebSocketFrame.encodeFrame
    (...)))` — **every write in try/catch** (an uncaught throw on the event loop crashes the
    process — same discipline as `writeSseResponse`). Exactly-once `onClose` via one `var closed`.
    On close, `socket.end()` (flush the close frame) — **not `destroy()`**.
- **`NodeHttpServer.scala`** — in `start()`, after `createServer`, register `server.on("upgrade",
  jsFn3)` (`js.Function3[js.Dynamic,js.Dynamic,js.Dynamic,Unit]`) → `NodeWebSocket`. Use
  `NodeBytes.*`.
- **`NodeServer.scala`** — `NodeServerConfig` gains `override val webSocketRoutes = Nil`,
  `webSocketMaxFrameSize = 1024*1024`, and `withWebSocketRoute(path)(factory)` /
  `withWebSocketRoute(path, filter)(factory)` / `withWebSocketMaxFrameSize(n)` — mirror
  `NativeServerConfig` (NativeServer.scala:107-129).

## Files
| Action | Path |
|---|---|
| Move | `uni/.native/.../http/Sha1.scala` → `uni/src/main/scala/wvlet/uni/http/Sha1.scala` (delete Native copy) |
| Add | `uni/src/main/scala/wvlet/uni/http/WebSocketFrame.scala` |
| Add | `uni/src/main/scala/wvlet/uni/http/WebSocketFrameDecoder.scala` |
| Edit | `uni/.native/.../http/NativeWebSocket.scala` (serve → driver; delegate to WebSocketFrame) |
| Add | `uni/.js/.../http/NodeBytes.scala`, `uni/.js/.../http/NodeWebSocket.scala` |
| Edit | `uni/.js/.../http/NodeHttpServer.scala` (on("upgrade"); use NodeBytes) |
| Edit | `uni/.js/.../http/NodeServer.scala` (WS config + builders) |
| Add | `uni/src/test/scala/wvlet/uni/http/WebSocketFrameDecoderTest.scala` |
| Add | `uni/.js/src/test/scala/wvlet/uni/http/NodeWebSocketTest.scala` |

## Verification
- `./sbt scalafmtAll`; `uniJVM/compile uniJS/compile uniNative/compile netty/compile` (shared move).
- **Decoder unit tests** (`uni/src/test`, cross-platform JVM/JS/Native) — the real regression guard:
  feed crafted masked byte sequences, assert the emitted `WsEvent` list. Cover single text/binary,
  fragmented (start+continuation+fin), interleaved-data ⇒ Fail(1002), lone-continuation ⇒ Fail(1002),
  ping ⇒ Ping, pong ignored, close ⇒ PeerClose(code), unmasked ⇒ Fail(1002), rsv ⇒ Fail(1002),
  oversized frame and oversized reassembly ⇒ Fail(1009), oversized/non-final control ⇒ Fail(1002),
  and **split-buffer** (same stream one byte per `feed` ⇒ identical events).
- **Node integration test** (`uni/.js/src/test`) — a manual `NodeWsClient` over
  `NodeModules.builtin("net").createConnection(...)` (no `ws` npm dep), mirroring the Native
  `WsClient` but async (return `Rx`/`Promise` like the existing Node SSE test): echo, binary echo,
  push-on-open, onOpen/onClose latches, missing-key 400, filter-reject 403.
- `uniNative/test` (the 18 cases, incl. 6 WS, stay green — exact-behavior preserved) and `uniJS/test`.

## Known limitations / follow-ups
- **Backpressure (Node):** `socket.write` returning `false` buffers in memory unbounded; acceptable
  for correctness-first parity (Native's `sendAll` is naturally backpressured). Optional follow-up:
  pause reading on `false`, resume on `drain`.
- `Sec-WebSocket-Version` not validated (matches Native; Netty replies 426) — optional hardening.
- No permessage-deflate; no client-side WebSocket (the standing cross-platform-client follow-up).

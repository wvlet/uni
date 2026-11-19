# Realtime WebSocket sessions (voice transcription)

## Goals

- Let a Uni app build a realtime voice transcription service and its client with Uni alone: a server that relays audio sessions to a realtime model API (such as OpenAI's Realtime API), and a push-to-talk client.
- Cover what such apps otherwise work around themselves: authenticating the upgrade, reporting rejections, close-code hygiene, metering, and stalled peers.
- Keep LLM chat on SSE. `/v1/messages` and `/v1/responses` are a request plus a streamed response, which server-streaming RPC already covers (ADR 2026-11-06).

## Background

- **A realtime relay server** sits between clients and a model's realtime WebSocket API, relaying frames verbatim in both directions. Built on a generic WebSocket server and client, it has to handle these itself:
  - Auth, feature gating, and model checks often run *after* the upgrade is accepted, with rejections sent as app close codes (4401/4403/4404/4500/4502). Clients can't read the HTTP status of a rejected upgrade: Node's undici and browsers report it as close 1006 with no status.
  - The upstream may close with codes a browser-facing client can't be given, so the relay maps them into the app range, for example 4902–4915.
  - Close reasons must fit in 123 UTF-8 bytes, cut on a character boundary.
  - The upstream connects asynchronously, so client frames can arrive before it is open, and the connect can fail.
  - Audio is typically billed by session wall-clock time, with frame and byte counters in each direction for metrics.
  - A stalled peer on either side must not hold memory or a thread forever.
- **A push-to-talk client**, for example in a desktop app, sends audio from an AudioWorklet to a process that holds the WebSocket.
  - The browser WebSocket API can't set an `Authorization` header, so the socket lives in Node, using undici's non-standard `headers` option.
  - With OpenAI's Realtime API, audio is PCM16, mono, 24 kHz, sent base64-encoded in JSON `input_audio_buffer.append` events, for example one frame per 250 ms (12 KB).
  - The client sends `session.update` on open, queues audio until then, sends `input_audio_buffer.commit` when the user releases push-to-talk, then closes with 1000.
  - When the user closes during the handshake, the browser reports 1006, which the client has to treat as a normal close.
  - Realtime sessions show the first word after a few hundred milliseconds, against seconds for chunked HTTP uploads.
- **Uni today:**
  - `WebSocketHandler`/`WebSocketContext` handle text and binary messages on the Netty, built-in JVM, Native, and Node servers.
  - An `RxHttpFilter` can gate the upgrade, and the attributes it adds are visible through `ctx.request`. The origin and host checks run before the filter (ADR 2026-10-14).
  - Servers cap frame size and send pings. `maxStreamingConnections` already counts WebSockets on the built-in and Native servers.
  - `WebSocketClient.connect(uri, handler, pingIntervalMillis)` takes no request headers on any platform, so a Uni client can't authenticate with an `Authorization` header.
  - `WebSocketFrame.closePayload` doesn't cap the reason. The built-in, Native, and Node servers and the Native client can send a close frame over the 125-byte control-frame limit, a protocol error. The JS client passes the reason to `ws.close`, which throws over 123 bytes.
  - `onClose(ctx)` carries no code or reason. The JS client drops the browser's `CloseEvent`.
  - What happens when a peer stops reading depends on the backend:
    - The built-in, Native-server, and Native-client sockets write synchronously, so the sending thread blocks with no timeout.
    - The Node server already pauses reading under backpressure.
    - Netty's `writeAndFlush` and the JDK client's send chain queue without a limit.

## Design

Lane: Standard. This adds public API and changes the WebSocket upgrade's auth boundary.

Delivery: two PRs. The first ships the primitives: client upgrade headers, close hygiene, the close code in callbacks, and stalled-peer limits. The second adds `WebSocketRelay` and readable rejections.

User flow for an app developer building a transcription relay:

- Register a WebSocket route with an auth filter, as today.
- An upgrade the filter rejects is answered with a WebSocket close code the client can read, so the client tells "not allowed" apart from "network down".
- The handler opens an upstream session with a Uni `WebSocketClient` that sends the upstream's `Authorization` header. Client frames that arrive before the upstream opens are queued, up to a limit. If the upstream can't be reached, the client is closed with a defined code.
- Frames flow both ways. Either side closing or failing closes the other with a valid code and reason, and the app receives one usage record for the session.
- A peer that stops reading is closed once its limit is reached, instead of holding memory or a thread.

Decisions:

- **Upgrade headers on the client.** `WebSocketClient.connect` accepts request headers:
  - It sends them on the JVM (`java.net.http`), on Native, and on Node (through undici's `headers` option).
  - Browsers can't send headers on a WebSocket upgrade. There, connecting with headers fails with a clear error instead of dropping them. The docs point to a short-lived token in the query string or in `Sec-WebSocket-Protocol`.
- **Readable rejections.** A `withXXX` route option (named during implementation) makes a *filter* rejection accept the upgrade and close at once.
  - The close code is 4000 plus the filter's HTTP status (401 becomes 4401, 403 becomes 4403). The reason is the response body, cut to fit.
  - An empty `Rx` from the filter maps to 4403.
  - Origin and host rejections stay plain HTTP 403, so a cross-site page never completes a handshake.
  - Plain HTTP rejection stays the default.
- **Close hygiene, fixed once in the shared code.**
  - `WebSocketFrame.closePayload` and the JS client cut reasons to 123 bytes on a UTF-8 boundary.
  - `ctx.close` replaces only codes that are invalid to send (1004–1006, 1015, and out-of-range codes) with 1011. Valid codes such as 1011 or 1013 pass through unchanged.
- **Close code in callbacks.** A new `onClose(ctx, code: Int, reason: String)` has a default that calls `onClose(ctx)`, so existing handlers keep working.
  - Every backend fills in the code, with 1006 when no close frame arrived. The JS client reads `event.code` and `event.reason`.
  - A local close during the handshake reports 1000, not 1006.
- **Stalled peers, per backend.**
  - Netty uses write-buffer water marks. The JDK client gets a byte limit on its send queue.
  - The blocking socket backends (the built-in server and the Native server and client) get a write timeout.
  - Over the limit or past the timeout, the connection closes with 1013 ("try again later"). Audio is produced in real time, so closing beats adding latency.
  - The Node server keeps its existing backpressure.
- **A relay helper.** `WebSocketRelay` takes an upstream URI and headers and pipes frames both ways.
  - Client frames that arrive before the upstream opens are queued up to a byte limit.
  - If the upstream connect or handshake fails, the client is closed with 4502, with the upstream status in the reason.
  - It mirrors closes and errors across, and maps upstream codes the client can't be sent into 4900–4999.
  - It reports per-session statistics (duration, frames and bytes each way, close code and side) to a callback for metering.
  - The app still decides auth and billing.
- **No new connection limits.** WebSocket sessions keep counting toward `maxStreamingConnections`, and the existing frame-size and ping settings apply.
- **Tests.**
  - A relay in front of a fake upstream, on the built-in, Netty, Native, and Node servers. It covers readable rejections, origin rejections staying 403, close mapping both ways, the pre-open queue, upstream connect failure, a stalled peer closing with 1013, and the statistics.
  - Upgrade-header tests for the JVM, Native, and Node clients, and a browser-path test that headers fail clearly.
  - Close-reason truncation and the `onClose` code on every backend.
- **Docs.**
  - Update `docs/http/websocket.md`, the WebSocket section of `docs/http/server.md`, `docs/guide/for-ai-agents.md`, and `plugins/uni/skills/uni/`, with a plugin version bump.
  - Add an ADR for the readable-rejection auth boundary and the close-code rules.
  - The docs tell handlers to read the user and filter attributes from `ctx.request`.

Out of scope:

- Audio capture and codecs (AudioWorklet, resampling).
- OpenAI event types, which stay app-level JSON.
- A typed session layer over RPC (a config message, binary audio up, typed events down), as a follow-up once the primitives exist.
- General bidirectional `RxStream` RPC, HTTP/2, and WebSocket over Electron IPC.
- Per-route idle timeouts and connection limits.

## Alternatives and Why Not?

- **General bidirectional `RxStream` RPC first.** It is bigger, needs flow control across every transport, and voice doesn't need it. The primitives here are the transport it would sit on anyway.
- **Rejecting the upgrade only with an HTTP status.** That is correct by the spec, but Node and browser clients see 1006 without the status. A client needs app codes to show a message such as "not enabled for this account".
- **Remapping every 1002–1015 code in `ctx.close`.** It would silently turn an app's valid `close(1011, ...)` into an app code. The mapping into 49xx belongs in the relay, where an upstream code is copied to a client.
- **Binary audio frames end to end instead of base64 JSON.** They save about 33%, but a relay to OpenAI must keep OpenAI's wire format. The typed-session follow-up can use binary frames where Uni owns both ends.
- **One send-buffer byte limit on every backend.** The socket backends write synchronously, so their buffered bytes are always near zero. Their failure mode is a blocked thread, which a write timeout bounds.

## Design gate

- 2026-11-18: the user approved this note on the Standard direction card ("Approve"), which covered the whole note and stated that it changes the WebSocket upgrade's auth boundary, and chose "Two PRs".
- PR 1 (#967) shipped the primitives. PR 2 ships `WebSocketRelay` and readable rejections under the same approval, without deviating from the decisions above. Implementation choices: the route option is `WebSocketRoute.withCloseOnRejection()`; the Native client now connects on its own thread so a relay's pre-open queue applies there; Native sockets no longer raise SIGPIPE. See `adr/2026-11-18-websocket-readable-rejections-and-relay.md`.

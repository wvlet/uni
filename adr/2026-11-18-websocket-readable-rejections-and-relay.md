# WebSocket readable rejections and relay

## Context

A realtime voice relay sits between clients and a model's realtime WebSocket API (`plans/2026-11-18-realtime-websocket-sessions.md`). The first PR shipped the primitives (`2026-11-18-websocket-close-status-and-upgrade-headers.md`). Two gaps remained:

- A route filter that rejects the upgrade answers with an HTTP status, which is correct by RFC 6455. Node's undici and browsers report that rejection as close 1006 with no status, the same as a network failure, so a client can't show "not enabled for this account".
- Every app relaying to an upstream had to write the same session logic itself: queueing frames until the upstream opens, closing the client when the upstream can't be reached, mirroring closes with codes the client may be sent, and counting usage for billing.

## Decision

- **Readable rejections are a per-route opt-in.** `WebSocketRoute(...).withCloseOnRejection()`, registered with `withWebSocketRoute(route)`, makes a *filter* rejection accept the upgrade and close at once with 4000 + the HTTP status (401 → 4401). The reason is the response body cut to 123 bytes, or the status phrase when the body is empty. A filter error is answered as its error response (usually 4500), and an empty `Rx` as 4403. The route's handler is never created. Every server backend serves the rejection through its normal handshake with a handler that closes in `onOpen`, so the close handshake, timeouts, and streaming-slot accounting are the existing ones.
- **The auth boundary stays where it was for cross-site pages.** Host and origin checks run before the filter (`2026-10-14-http-server-secure-defaults.md`) and keep answering plain HTTP 403, so a cross-site page never completes a handshake, even on a readable route. Malformed upgrades (400, 426) stay HTTP errors. Without the option, a rejection is plain HTTP as before. A readable rejection does complete a handshake for a same-site or non-browser client that the filter turned away; it receives nothing but the close frame.
- **`WebSocketRelay` is a handler, not a server feature.** `WebSocketRelay(upstreamRequest).newHandler()` is used as a route's handler factory, so the app still decides auth (the route filter) and billing (the stats reporter). It connects the upstream with the shared `WebSocketClient`, so upgrade headers work wherever the client sends them.
- **Relay rules.**
  - Client frames that arrive before the upstream opens are queued in order up to `maxPendingBytes` (1 MiB, about 20 s of base64 PCM16 at 24 kHz). Past it the client is closed with 1013, matching slow-peer drops. No send runs under the session lock, so a stalled peer can't block the other direction.
  - An upstream connect or handshake failure closes the client with 4502. The reason carries the upstream's HTTP status (`upstream status 401`) when the client exposes it: the JDK and Native clients now fail with an `HttpException` carrying the status. Node's `WebSocket` doesn't expose it, so there the reason is `upstream connect failed`. Error messages are not forwarded, to avoid leaking upstream hosts or internals.
  - Upstream closes with 1000, 1001, or 3000–4999 pass through. Other codes map to 4900 + (code − 1000) for 1002–1099 (1006 → 4906, 1011 → 4911) and to 4900 otherwise, so the client can tell an upstream failure from the relay's own 1011. A client close is sent to the upstream as is, except a dropped client (1006), which becomes 1001.
  - One `WebSocketRelayStats` per session, reported when the client connection closes: duration, frames and bytes each way, the first closer's code and reason before mapping, and `closedBy` (`Client`, `Upstream`, or `Relay`).
- **The Native client connects asynchronously.** `NativeWebSocketClient.connect` ran the TCP connect and handshake on the caller's thread. A relay calls `connect` from the client's `onOpen`, so a slow upstream blocked the server worker and the pre-open queue could never apply. The handshake now runs on the connection's own daemon thread, which then becomes its reader, as on the JVM and JS clients.
- **Native sockets don't raise SIGPIPE.** A send to a peer that closed raised SIGPIPE and killed the process, which a relay closing its client mid-send hits routinely. The socket shim sets `SO_NOSIGPIPE` on each socket (macOS) and sends with `MSG_NOSIGNAL` (Linux), so the send fails with EPIPE. A process-wide `signal(SIGPIPE, SIG_IGN)` was rejected because it would change how the app's own pipes (stdout into `head`) behave.

## Alternatives

- **Readable rejections by default.** It would change what existing clients see, and complete handshakes that today stop at an HTTP status. An opt-in keeps the default spec-correct.
- **Mapping origin rejections too.** It would let a cross-site page complete a handshake, which the origin check exists to prevent.
- **A relay built into the server config (`withWebSocketRelay(path, upstream)`).** The app needs the upgrade request to pick the upstream, its credentials, and the meter, which a handler factory already receives.
- **Stats on the upstream's close.** The billed session is the client's; reporting on the client's close gives one record even when the upstream never opened.

## Consequences

- `netty` depends on `uni`'s test classes (`test->test`) to run the shared `WebSocketRelaySpec` (`uni/.jvm-native/src/test`) on Netty, as the built-in JVM and Native servers do. Node runs an async copy of the same cases (`NodeWebSocketRelayTest`).
- On the JS client, a rejected upstream handshake reports no status, so the 4502 reason is generic there.

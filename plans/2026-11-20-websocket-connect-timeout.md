# Bound the WebSocket client's connect and handshake

## Goals

- An upstream that accepts TCP but never answers the upgrade must not hold a `WebSocketRelay` client open indefinitely. On JVM and JS today the client gets no close until it overflows the pre-open queue (1013) or gives up itself.
- Any `WebSocketClient.connect` should fail within a known time when the peer never completes the handshake, on JVM, Native, and JS alike.

## Background

- **JVM**: `JavaWebSocketClient` builds on a shared `HttpClient.newHttpClient()` with no connect timeout, and the WebSocket builder sets none either. A silent upstream waits forever.
- **Native**: the TCP connect is blocking with no timeout (the OS default, often about 75 s, for a blackholed host). The handshake read waits 30 s per `poll`, then fails with `connectionFailed`, so a silent upstream already ends in 4502 after 30 s. The wait restarts on every chunk, so a server that drips bytes can stretch it far past 30 s.
- **JS**: the global `WebSocket` has no timeout option; a silent server waits forever.
- `HttpClientConfig.connectTimeoutMillis` already defaults to 30 s for HTTP requests, and the Native client's handshake already uses 30 s. `HttpErrorCode.ConnectionTimeout` exists for exactly this failure.
- The relay closes the client with 4502 on an upstream failure; the reason is `upstream status N` or `upstream connect failed` (`adr/2026-11-18-websocket-readable-rejections-and-relay.md`). 4502 follows the readable-rejection rule of 4000 + HTTP status (502 Bad Gateway).

## Design

Lane: Standard — new configurable behaviour on a public client and relay, and a new close code clients will see.

- **The client owns the timeout.** A client-side deadline also frees the socket, which a relay-only timer would leave connecting in the background. The relay inherits it from the client it uses.
- **One deadline covers the whole connect:** TCP connect (DNS on JVM) plus the upgrade request and its 101 response. It is a total deadline, not a per-read wait, so a dripping server can't extend it.
- **Default 30 s**, matching `HttpClientConfig.connectTimeoutMillis` and the Native handshake today.
- **Config on the client, platform-neutral:** `Http.webSocketClient.withConnectTimeoutMillis(10000)` and `noConnectTimeout()` on the `WebSocketClient` trait. They are concrete trait methods (a custom client that doesn't override them keeps its own behaviour), so existing implementers still compile. Each built-in backend takes the timeout as a constructor parameter. The `connect` signatures stay as they are.
- **On timeout, `connect` fails with `HttpException` code `ConnectionTimeout`**, and the half-open connection is closed:
  - JVM: the builder's own `connectTimeout` (it covers the 101 response); its `HttpTimeoutException` maps to `ConnectionTimeout`.
  - Native: a non-blocking TCP connect in the socket shim (poll for writability, then `SO_ERROR`; Windows path included), then one total handshake deadline.
  - JS: a timer that marks the connect timed out before closing the `WebSocket`, so the close-before-open the relay sees is reported as a timeout, not a refusal. The timer is cleared on open or close.
- **The relay closes its client with 4504, reason `upstream connect timed out`**, on all three platforms, in place of today's indefinite wait (JVM, JS) or 4502 after 30 s (Native). 4504 follows the 4000 + HTTP-status rule (504 Gateway Timeout), so a client can tell "upstream too slow, retry" from "upstream refused" (4502). Stats record `closedBy = Relay` with that code.
- 4504 can also come from a readable rejection whose filter answers 504, or from an upstream's own 4504 passing through, as 4502 already can. All mean "gateway timed out"; the docs say so, and stats `closedBy` tells them apart.
- **User flow (relay operator):** a voice app relays to a realtime API. The API hangs on the upgrade. After 30 s, or whatever the app set with `WebSocketRelay(...).withWebSocketClient(Http.webSocketClient.withConnectTimeoutMillis(10000))`, the client sees close 4504 and can retry or show "service is slow". The stats reporter sees the session with `closedBy = Relay` and code 4504.
- Tests use a real listener that accepts and never answers: client connect fails with `ConnectionTimeout` within the configured time (JVM, Native, Node), and the relay closes the client with 4504 in the shared `WebSocketRelaySpec` (JVM, Native, Netty) and in `NodeWebSocketRelayTest`.
- Docs: `docs/http/websocket.md` (client timeout; 4504 in the close-code and relay-event tables), the relay and Native client Scaladoc, and a new ADR (or an update of the relay ADR) for the timeout and 4504.

## Alternatives and Why Not?

- **A timer in `WebSocketRelay` only.** It closes the client but leaves the upstream socket connecting; a later open is closed, but one that never opens leaks until the OS gives up.
- **4502 with reason `upstream connect timed out`.** No new code for clients, but a client can only tell a timeout from a refusal by parsing the reason string.
- **A `connectTimeoutMillis` parameter on `connect`.** Changes the abstract method every backend and custom client implements, and adds another overload pair; a client-level setting with concrete trait methods keeps them compiling and matches `HttpClientConfig`.
- **A shorter default (e.g. 10 s) for the relay.** A relay-specific knob duplicates the client's; an app that wants a tighter bound sets it on the client it hands the relay.

# WebSocket client connect timeout

## Context

`WebSocketRelay` connects its upstream with the shared `WebSocketClient` (`2026-11-18-websocket-readable-rejections-and-relay.md`). An upstream that accepts TCP but never answers the upgrade left the relay's session pre-open indefinitely. The client got no close until it overflowed the pre-open queue (1013) or gave up itself.

- The JVM client set no connect timeout, and neither did its shared `HttpClient`.
- The JS client can't: the browser and Node `WebSocket` have no timeout option.
- The Native client's TCP connect blocked for as long as the OS allowed. Its handshake read waited 30 s per `poll`, and every chunk restarted the wait, so a server dripping bytes could stretch it far longer.

## Decision

- **The client owns the timeout.** `WebSocketClient.connectTimeoutMillis` bounds the TCP connect and the upgrade response together, as one total deadline. It defaults to 30 s (`WebSocketClient.DefaultConnectTimeoutMillis`), as `HttpClientConfig.connectTimeoutMillis` does. Set it with `withConnectTimeoutMillis(ms)`, or remove it with `noConnectTimeout()`. Past the deadline `connect` fails with `HttpErrorCode.ConnectionTimeout`, and the half-open connection is closed.
  - JVM: `WebSocket.Builder.connectTimeout`, whose `HttpTimeoutException` maps to `ConnectionTimeout`.
  - Native: a non-blocking connect in `uni_socket_shim.c` (`poll` on POSIX; `select` on Windows, because `WSAPoll` before Windows 10 2004 never reports a refused connect), then the handshake waits only for the time left.
  - JS: a timer. It fails the connect before closing the `WebSocket`, so the close it causes isn't mistaken for a refusal.
- **The config methods are concrete on the trait.** A custom `WebSocketClient` still compiles; one that doesn't override them returns itself and reports 0.
- **The relay closes its client with 4504, reason `upstream connect timed out`**, on every platform; stats record `closedBy = Relay`. 4504 follows the readable-rejection rule of 4000 + HTTP status (504 Gateway Timeout), so a client can tell "retry, the upstream was slow" from "the upstream refused" (4502). A filter answering 504 on a readable route, or an upstream's own 4504 passing through, also sends 4504; all three mean a gateway timed out, and `closedBy` tells them apart in stats.
- **The relay has no timeout setting of its own.** An app that wants a shorter bound sets it on the client it hands the relay: `withWebSocketClient(Http.webSocketClient.withConnectTimeoutMillis(10000))`.
- **A pre-open close always fails the JS client's connect `Rx`.** The relay now learns of every pre-open failure from that `Rx`, whose error carries the timeout or status, and no longer infers a failure from a close before open.

## Alternatives

- **A timer in the relay only.** It would close the client, but leave the upstream socket connecting until the OS gave up.
- **4502 with a timeout reason.** No new code, but a client could tell a timeout from a refusal only by parsing the reason.
- **A timeout parameter on `connect`.** It would change the abstract method every implementer overrides and add another overload pair.

## Consequences

- On Native, a silent upstream now closes the relay's client with 4504 instead of 4502 after 30 s, and a dripping one can no longer extend the wait.
- `noConnectTimeout()` on Native also removes the handshake's former 30 s per-read bound.

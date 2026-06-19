# WebSocket client — JS backend (PR 2 of 3)

## Context
Second of three WebSocket-client PRs (after JVM #581). Originally scoped for the global `WebSocket`,
which is only a stable Node global from v22; CI ran Node 20. Rather than implement a raw-socket
fallback, **we dropped Node 20 support** (bumped CI to Node 22), so the JS client is a thin adapter
over the global `WebSocket` (browsers + Node >= 22), which handles the handshake/masking/framing.

## Changes
- **`JSWebSocketClient`** (uni/.js) — facade over the global `WebSocket` (`@JSGlobal("WebSocket")`):
  `binaryType = "arraybuffer"`; `onopen`→`onOpen` + resolve the connect `Rx`; `onmessage`→
  `onTextMessage` (string) / `onBinaryMessage` (ArrayBuffer→bytes); `onclose`→`onClose` (once);
  `onerror`→`onClose` (transport errors, matching the other backends) + fail the connect `Rx`. Bridged
  to `Rx` via `Promise` + `Rx.future`. `JSWebSocketContext` sends text/`Uint8Array` and closes.
  Registered in `JSHttpChannelFactory.newWebSocketClient`.
- **CI**: bump `node-version` 20 → 22 in `.github/workflows/{test,doc,release-js}.yml`.

No shared-code change (the codec stays server-only; the client-mode codec is PR 3's Native work).

## Verification
`uniJS/test` (1400 tests): new `JSWebSocketClientTest` drives the JS client against an in-process
`NodeServer` WS echo/push route over `ws://127.0.0.1` — text echo, push-on-open, onClose.

## Follow-up
PR 3 — Native client (raw socket + the shared codec extended to client mode: mask outbound, decode
unmasked server frames).

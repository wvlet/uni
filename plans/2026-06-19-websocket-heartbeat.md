# WebSocket ping/pong heartbeat (PR 1: shared core + Native server & client)

## Context
Incoming Ping→Pong auto-reply already works everywhere; this adds *initiating* pings to detect a
half-open peer (one that neither sends data nor closes), closing it if pings go unanswered. Full
scope is server (Native/Node/Netty) + client (JVM/Native); the JS client can't (the browser/Node
global WebSocket exposes no send-ping to JS). Delivered as ~4 PRs; this is PR 1 (shared core + the
Native backends, which share the poll-based read loop).

## Changes
- **`WebSocketHeartbeat` (shared)** — a small state machine: `onActivity()` (any inbound frame proves
  liveness) and `onTick(): Decision` (`SendPing` on an idle interval; `Close` if a prior ping is
  still unanswered after another idle interval; `Idle` when there was recent activity). Gives a dead
  peer ~2 intervals. Deterministic unit test.
- **`WebSocketDispatcher.dispatch`** gains an `onActivity: () => Unit = () => ()` hook, invoked once
  per decoded frame, so every decoder-driven backend resets the heartbeat uniformly (default no-op
  keeps Node/Netty unchanged for now).
- **`NativeWebSocket.runReadLoop`** — extracted poll/read/dispatch loop shared by the Native server
  (`serve`) and client (reader thread). With `pingIntervalMillis > 0` it uses `poll` as the heartbeat
  clock (send ping on idle tick; close on unanswered); with 0 it blocks (poll timeout -1) exactly like
  the old `recv`. `sendPing` added to both contexts (server unmasked, client masked).
- **Config**: `NativeServerConfig.webSocketPingIntervalMillis` (0 = off) + `withWebSocketPingIntervalMillis`,
  threaded into `serve`. **`WebSocketClient.connect` gains a `pingIntervalMillis` param** (a final
  2-arg overload delegates with 0); the Native client uses it, JVM/JS impls accept-and-ignore it for
  now (JVM wires it in PR 4; JS can't).

## Verification
`WebSocketHeartbeatTest` (state machine) + a Native integration test (server pings every 150ms, the
client auto-pongs, the live-but-idle connection is NOT reaped over ~5 intervals). All suites green:
JVM 1640 / JS 1395 / Native 1402 / Netty 37.

## Follow-ups
PR 2 — Node server (`setInterval`). PR 3 — Netty server (`IdleStateHandler`). PR 4 — JVM client
(`ScheduledExecutorService` + `sendPing`/`onPong`). JS client: not possible (documented).

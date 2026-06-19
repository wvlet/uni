# Native HTTP server — portable idle/read timeout

## Context
The Native server (#574) used blocking `recv` with no timeout — `SO_RCVTIMEO` was dropped because
macOS's `timeval`/`suseconds_t` layout differs from posixlib's, making `recv` return immediately. So
an idle or silently-dropped client pinned a worker thread indefinitely (the recurring SSE/keep-alive
limitation noted across #574/#575/#576).

Fix: use `poll()` (posixlib 0.5.12), whose timeout is a plain millisecond `CInt` — no `timeval` trap,
portable across macOS and Linux.

## Changes (all Native)
- **`NativeSocket`** — `waitReadable(fd, timeoutMillis): Int` (1 readable / 0 timeout / -1 hangup,
  checking `POLLHUP|POLLERR|POLLNVAL`); `enableKeepAlive(fd)` (`SO_KEEPALIVE`, best-effort OS reaping
  of dead peers).
- **`NativeServerConfig`** — `idleTimeoutMillis` (default 30000) + `readTimeoutMillis` (30000) +
  `withIdleTimeoutMillis`/`withReadTimeoutMillis`.
- **`HttpConnectionReader`** — the `readChunk` thunk now takes an `idle` flag (true when the buffer is
  empty = waiting for a new request), so the caller applies the idle vs read timeout.
- **`NativeHttpServer.handleConnection`** — `enableKeepAlive`, then a polling read thunk: idle
  keep-alive waits `idleTimeoutMillis`, mid-request reads wait `readTimeoutMillis`; a timeout/hangup
  yields an empty chunk → the reader ends the connection (idle → Closed, mid-request → 400).
- **`streamSse`** — replaces the bare `done.await()` with `done.await(idleTimeoutMillis)` intervals,
  probing the socket (non-blocking `waitReadable`) between waits; a disconnected peer cancels the
  subscription so the worker is freed instead of parked until the next failed write.

WebSocket is unchanged: a clean disconnect already unblocks its blocking `recv` (returns 0). Half-open
WS detection (ping/pong) remains a separate follow-up; `SO_KEEPALIVE` helps at the OS level.

## Verification
`uniNative/test` (1399 tests, 0 failed). New tests: an idle keep-alive connection is closed after
`idleTimeoutMillis`; an incomplete request is closed (400) after `readTimeoutMillis`.

## Follow-ups (remaining)
Native libcurl client bug; cross-platform WebSocket client; WS ping/pong heartbeat; permessage-deflate.

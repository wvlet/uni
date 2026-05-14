# Synchronous HTTP on Node.js

PR: https://github.com/wvlet/uni/pull/546
Branch: `feature/js-sync-http-20260514_114113`

## Problem

`HttpChannel.send` is synchronous, but `JSHttpChannelFactory.newChannel` threw
`NotImplementedError` because JavaScript has no native sync HTTP. `wvlet/wvlet` needs a
sync HTTP client on JVM, Node, and Native to give its Trino client the same shape as
`DuckDB.execute`.

## Solution

`NodeSyncHttpChannel` — `worker_threads` + `Atomics.wait` on a `SharedArrayBuffer`.
Main thread blocks in `Atomics.wait`; a worker runs `await fetch(...)`, writes the
response into the shared buffer, and `Atomics.notify`s the parent. Node.js only;
browsers continue to throw with a message pointing at the async client.

See `adr/2026-05-14-nodejs-sync-http.md` for the design rationale and the non-obvious
points (they are the bulk of the value here).

## Files

- `uni/.js/src/main/scala/wvlet/uni/http/NodeSyncHttpChannel.scala` — new channel +
  companion (`isNode`, lazy `worker_threads` loader, layout constants, `WorkerScript`).
- `uni/.js/src/main/scala/wvlet/uni/http/JSHttpChannelFactory.scala` — `newChannel`
  returns `NodeSyncHttpChannel()` on Node, throws on browsers.
- `uni/src/main/scala/wvlet/uni/http/HttpClientConfig.scala` — added `maxResponseBytes`
  (+ `withMaxResponseBytes`).
- `uni/.js/src/test/scala/wvlet/uni/http/NodeSyncHttpChannelTest.scala` — 5 tests
  against an in-process echo server (run in its own worker thread).

## Learnings from the PR cycle

These all came out of review (Gemini + Codex) and are captured in the ADR:

1. **A static `@JSImport("worker_threads")` breaks browser bundler builds** for the
   whole library, since `JSHttpChannelFactory` is the default factory. Switched to a
   runtime `process.getBuiltinModule` load (bundler-invisible, works in CJS + ESM).
2. **`Long` config values do not cross into JS as numbers** — the timeout values were
   `RuntimeLong` objects, so both the worker abort and the parent `Atomics.wait`
   timeout were silently no-ops. Fixed by computing one effective `Int` timeout.
3. **The test echo server must run in a separate worker thread** — `Atomics.wait`
   blocks the main event loop, so an in-process same-thread server would deadlock.
   This also replaced an initial httpbin.org dependency.
4. **`redirect: 'manual'`** so `DefaultHttpSyncClient` keeps owning redirect handling.
5. **Stream the worker response body** and abort past `maxResponseBytes` rather than
   buffering it all with `arrayBuffer()` (worker OOM risk).
6. The eval'd worker string runs as CommonJS regardless of the parent module kind, so
   `require(...)` inside `WorkerScript` is fine even though uni links as ESModule.

## Known follow-ups (not in this PR)

- Per-call worker spawn and a per-call 16 MB buffer allocation — acceptable for the
  CLI/scripted use case; worker reuse / lazy buffer growth if profiling demands it.
- `process.getBuiltinModule` needs Node 20.16+ (older Node falls back to global
  `require`, CJS only).

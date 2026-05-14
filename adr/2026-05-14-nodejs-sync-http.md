# 2026-05-14: Synchronous HTTP on Node.js via `worker_threads` + `Atomics.wait`

PR: https://github.com/wvlet/uni/pull/546 (commit 60e6d35 introduces the design;
refined through 505d70a)

## Context

`HttpChannel.send` is synchronous — it returns an `HttpResponse`, not an `Rx`/`Future`.
JVM and Native back it with genuinely blocking clients. On Scala.js the sync channel
(`JSHttpChannelFactory.newChannel`) used to just `throw NotImplementedError`, because
JavaScript has no native synchronous HTTP API: `fetch` is async, sync `XMLHttpRequest`
is deprecated and unavailable off the main thread, and Node has no blocking HTTP call.

`wvlet/wvlet` needs a sync HTTP client that works on JVM, Node, and Native so its Trino
client can match the shape of `DuckDB.execute`. That forced the question: can Node do
sync HTTP at all, without a `curl` shell-out or a `deasync`-style libuv hack?

## Decision

Implement `NodeSyncHttpChannel` using the `worker_threads` + `Atomics.wait` on
`SharedArrayBuffer` pattern (the same trick `sync-fetch` uses internally):

1. The main thread allocates a `SharedArrayBuffer` (`HeaderBytes` + `maxResponseBytes`),
   spawns `new Worker(code, { eval: true })` with the request in `workerData`, and calls
   `Atomics.wait` to block.
2. The worker runs `await fetch(...)`, encodes status/headers/body into the shared
   buffer, then `Atomics.store` + `Atomics.notify` wakes the parent.
3. The main thread decodes the buffer and returns `HttpResponse` synchronously.

Node.js only. Browser environments still throw `NotImplementedError`;
`JSHttpChannelFactory.newChannel` detects Node via `process.versions.node` first.

## Non-obvious points a future reader would otherwise reverse-engineer

### `worker_threads` is loaded at runtime, not via `@JSImport`

A static `@JSImport("worker_threads")` is emitted by Scala.js as a top-level
`import "worker_threads"` into **every** bundle that links `NodeSyncHttpChannel`.
Because `JSHttpChannelFactory` is the *default* channel factory and its `newChannel`
references `NodeSyncHttpChannel`, that import lands in any browser bundle using uni's
HTTP at all — and browser bundlers (Webpack/Vite/Rollup) fail to resolve the Node-only
`worker_threads` at build time, before `isNode` can ever return false.

Scala.js has no synchronous dynamic `require`. The fix is `process.getBuiltinModule`
(Node 20.16+ / 22.3+), which works in both CommonJS and ESM, with the global `require`
as a fallback for older Node / `NoModule` builds. Being a plain runtime call it is
invisible to bundlers — browser bundles build fine and hit the `isNode == false` path.

### The eval'd worker string runs as CommonJS regardless of the parent's module kind

uni links as `ModuleKind.ESModule`. It is tempting to assume a worker spawned from an
ESM parent is itself ESM and lacks `require`. It is not: `new Worker(code, {eval:true})`
evaluates `code` as CommonJS, so `require('worker_threads')` inside `WorkerScript` works
even though the surrounding artifact is an ES module. (Verified directly on Node 25.)
The worker's own module kind is independent of the parent's.

### The test server must run in its own worker thread

`Atomics.wait` blocks the **main thread's event loop**. A test HTTP server created
in-process with `http.createServer` would never accept the connection — the client
blocks the very loop the server needs. `NodeSyncHttpChannelTest` therefore runs its
echo server inside a separate `worker_threads.Worker` and hands the port back through
its own tiny `SharedArrayBuffer` + `Atomics` handshake. This is also why the tests do
not (and should not) hit an external service like httpbin.org.

### `redirect: 'manual'` in the worker fetch

`DefaultHttpSyncClient` owns redirect handling (`followRedirects`, `maxRedirects`,
method rewriting), so the worker fetch must not silently follow redirects. It sets
`redirect: 'manual'`; Node's `fetch` (unlike browsers) exposes the real 3xx status and
`Location` header on a manual redirect, which is what the client needs. This mirrors
`FetchChannel`'s `RequestRedirect.manual`.

### Timeouts: a single effective value, and the `Long` trap

`fetch`'s `AbortController` cannot separate connect from read timeouts, so the channel
computes one effective timeout — `readTimeoutMillis` if set, else `connectTimeoutMillis`
— mirroring `JavaHttpChannel`, which applies `readTimeoutMillis` as the request timeout.

Crucially: `HttpClientConfig.connect/readTimeoutMillis` are `Long`. Scala.js represents
`Long` as a `RuntimeLong` object, **not** a JS number, so passing them straight into
`js.Dynamic.literal` (or `Atomics.wait`'s timeout argument) silently produces a
non-numeric value — the worker abort and the parent's safety-net `Atomics.wait` timeout
both became no-ops. The effective timeout must be `.toInt` before crossing into JS.

### Worker streams the body to bound memory

The worker reads the response via `resp.body.getReader()` and aborts once the
cumulative size exceeds `maxResponseBytes`, instead of materialising the whole body
with `arrayBuffer()` first — otherwise a huge response OOMs the worker before the size
check, and a worker crash leaves the parent blocked until the safety timeout.

## Consequences

- `HttpSyncClient` now works on all three platforms; `wvlet/wvlet` can use one API.
- `HttpClientConfig.maxResponseBytes` (default 16 MB) caps the `SharedArrayBuffer`;
  it is cross-platform config but only `NodeSyncHttpChannel` enforces it — other
  channels ignore it.
- Per-call worker spawn and a per-call 16 MB buffer allocation are known costs,
  acceptable for the CLI/scripted use case that motivated this. Worker reuse / lazy
  buffer growth are possible follow-ups if profiling shows they matter.
- Requires Node 20.16+ for `process.getBuiltinModule` (older Node falls back to global
  `require`, which only exists in CommonJS / `NoModule` builds).
- The shared-buffer layout (`OffsetState/StatusOrErrLen/HeadersLen/BodyLen`,
  `HeaderBytes`, `StatePending/Success/Error`) is a contract between Scala and the JS
  worker string. The constants are defined once on the Scala side and interpolated into
  `WorkerScript`; keep them single-sourced when changing the layout.

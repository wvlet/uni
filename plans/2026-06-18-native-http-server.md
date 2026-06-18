# Scala Native HTTP Server (POSIX sockets)

## Context

The cross-platform HTTP server abstraction (`HttpServer`/`HttpServerConfig`/`RxHttpHandler`, shared in
`uni`) has JVM (Netty) and Node.js backends; Scala Native has only an HTTP *client* (libcurl). This is
the planned third backend so the server runs on JVM + Node + **Native**, completing the cross-platform
goal set when the foundation landed (#572).

**Feasibility (confirmed):**
- Scala Native **0.5.12** is multithreaded — `java.lang.Thread`, `Executors`, `Future`/`Promise`,
  `LinkedBlockingQueue`, `AtomicBoolean` are all already used on Native in this repo
  (`uni-core/.native/.../io/IOCompat.scala`, `.../rx/compat.scala`, `uni/.native/.../rx/schedulerCompat.scala`).
- **POSIX TCP sockets** (`socket`/`setsockopt`/`bind`/`listen`/`accept`/`recv`/`send`/`getsockname`/`close`)
  come from Scala Native's bundled `posixlib` (`scala.scalanative.posix.*`) — **no new dependency and no
  linker flag** (sockets are in libc). posixlib is already used on Native (`FileSystemImpl`, `compat`).
- A Native test can start the server and drive it with the **in-module libcurl sync client**
  (`Http.client...newSyncClient` → `CurlChannel`); the server uses its own threads, so the blocking
  client doesn't deadlock (unlike Node's single event loop).

**Approach (decided):** hand-written HTTP/1.1 over POSIX sockets — consistent with uni's minimal-deps
ethos and the libcurl precedent, and free of the licensing problems of C server libs (mongoose = GPL/
commercial, libmicrohttpd = LGPL; both incompatible with Apache-2.0).

**Scope (MVP):** blocking accept thread + worker thread pool; HTTP/1.1 request/response with
`Content-Length` bodies, headers, keep-alive, ephemeral port. SSE/chunked streaming and WebSocket are
follow-ups.

## Design

All new code in `uni/.native/src/main/scala/wvlet/uni/http/`, mirroring the `NodeServer`/`NettyServer`
shape. No shared-module changes (server backends are selected by using their config directly; there is
no factory registry).

### 1. `NativeServer.scala` — entry + config
- `object NativeServer` with `withPort/withHandler/withRxHandler` (mirrors `NodeServer`).
- `case class NativeServerConfig(name, host, port, handler, filters, backlog = 128,
  maxRequestSize = 1MB, workerThreads = ...) extends HttpServerConfig` with the common
  `withName/withHost/withPort/withHandler/withRxHandler/withFilter/withFilters` builders plus
  native knobs (`withBacklog`, `withMaxRequestSize`, `withWorkerThreads`), and
  `override def start(): NativeHttpServer`. Binding is synchronous, so the inherited
  `start[A](block)` is safe (no override needed, unlike Node).

### 2. POSIX socket usage (prefer posixlib; thin helpers only where needed)
Use `scala.scalanative.posix.sys.socket.*`, `netinet.in.*`, `netinet.inOps.*`, `arpa.inet.*`
(`htons`), `unistd.*`, and `scala.scalanative.posix.poll` if needed. Memory via `stackalloc`
(sockaddr structs, out-params) and `Zone`/`malloc` for buffers — the pattern established by
`CurlBindings.scala`/`CurlChannel.scala`. Add a small `@extern`/`@name` binding **only** for any
symbol posixlib lacks (decided by a short compile spike — see Risks). No `@link` needed (libc).

### 3. `NativeHttpServer.scala` — `extends HttpServer`
- `start()`:
  - `socket(AF_INET, SOCK_STREAM, 0)`; `setsockopt(SO_REUSEADDR)`; fill `sockaddr_in`
    (`htons(port)`, host); `bind`; `listen(backlog)`.
  - Resolve the bound port via `getsockname` (so `withPort(0)` ephemeral works) → store it.
  - Spawn a **daemon accept thread**; mark `running = true`.
- Accept loop (accept thread): `accept()` in a loop while `running`; hand each connection fd to a
  worker pool (`Executors.newFixedThreadPool(workerThreads)`). A failed `accept` while `!running`
  means shutdown (listen fd closed) → exit the loop; otherwise log and continue.
- Per-connection (worker thread):
  1. Read+parse the request (§4) into a uni `Request`.
  2. Run `config.effectiveHandler.handle(request): Rx[Response]` and **block for the single result**
     via `RxRunner.runOnce` + a `CountDownLatch`/`AtomicReference` (the cross-platform Rx-await
     pattern already in `uni-core/.native/.../rx/compat.scala`). `OnError` → 500; `OnCompletion`
     without a value → 404.
  3. Serialize the `Response` (§5) and `send` it.
  4. Keep-alive: loop for the next request unless `Connection: close` or a read error/EOF; then
     `close`.
- `localPort`/`localAddress` from the stored bound address; `isRunning` from the flag; `whenReady`
  returns `Rx.single(this)` (synchronous bind, like Netty); `stop()` sets `running = false`, closes
  the listen fd (unblocks `accept`), and shuts down the worker pool with a timeout;
  `awaitTermination()` joins the accept thread.

### 4. `HttpRequestParser` (hand-written HTTP/1.1)
- Read from the socket into a growing buffer until the header terminator `\r\n\r\n` (bounded by
  `maxRequestSize`), tolerating partial `recv`s.
- Parse the request line (`METHOD SP target SP HTTP/1.1`), then headers into `HttpMultiMap`
  (`HttpMultiMap.newBuilder`), then read exactly `Content-Length` body bytes. Build the body with
  the shared `HttpContent.fromBytes(bytes, headers)` (reused from the Netty/Node backends).
- Map the method via `HttpMethod.of(...)`; an unknown verb or malformed request → a 400 written
  directly (never throws past the worker). Chunked **request** bodies are out of MVP scope —
  respond `400`/`411` (documented).

### 5. `HttpResponseWriter`
- Serialize `Response` → `HTTP/1.1 <code> <reason>\r\n`, headers, `Content-Type` (from
  `content.contentType`), `Content-Length` (from `content.toContentBytes.length`), the keep-alive/
  close `Connection` header, `\r\n\r\n`, then the body bytes. Reuses `HttpStatus.code`/`.reason`.

### 6. No build.sbt / dependency changes
posixlib + threads are bundled; sockets are libc. Nothing to add.

## Files

| Action | Path |
|---|---|
| Add | `uni/.native/src/main/scala/wvlet/uni/http/NativeServer.scala` (object + `NativeServerConfig`) |
| Add | `uni/.native/src/main/scala/wvlet/uni/http/NativeHttpServer.scala` (`HttpServer` impl: sockets + accept loop + workers) |
| Add | `uni/.native/src/main/scala/wvlet/uni/http/NativeHttpProtocol.scala` (request parser + response writer; or split into two files) |
| Add | `uni/.native/src/test/scala/wvlet/uni/http/NativeServerTest.scala` (NEW native test tree) |

Reuse: shared `HttpServer`/`HttpServerConfig`/`RxHttpHandler`/`Request`/`Response`/`HttpContent.fromBytes`/
`HttpMultiMap`/`HttpStatus`/`RxRunner`; the `CurlBindings.scala` extern/memory pattern; the
`NodeServerConfig`/`NettyServerConfig` builder shape; the `rx/compat.scala` Rx-await pattern.

## Verification
- **Compile spike first** (de-risks the one unknown): a tiny `uniNative/compile` that calls
  `socket/bind/listen/getsockname` to pin the exact posixlib symbol names/signatures in SN 0.5.12,
  before building the full server.
- `./sbt scalafmtAll` then `./sbt uniNative/compile`.
- New `uni/.native` `NativeServerTest`: `NativeServer.withPort(0).withHandler(...).start { server => ... }`,
  then hit it with the in-module libcurl **sync** client
  (`Http.client.withBaseUri(s"http://localhost:${server.localPort}").newSyncClient`): assert GET body+
  status, POST echoes the body, request/response headers round-trip, unmatched path → 404, and the
  ephemeral port is reported. Run `./sbt uniNative/test`.
- Regression: `./sbt uniJVM/test uniJS/test netty/test` stay green (no shared changes expected).
- Native binaries build per-platform on CI ("Scala Native" job) — the new test exercises a real
  socket round-trip there.

## Risks / notes
- **posixlib API surface** is the one unknown (exact names for `getsockname`, `sockaddr_in` field
  ops, `htons`). Mitigated by the upfront compile spike; fall back to a minimal `@extern`/`@name`
  binding for any missing symbol (libc, no `@link`).
- **HTTP/1.1 correctness** is owned by us: scope to `Content-Length` bodies + basic keep-alive;
  reject malformed/chunked-request with 400/411; never let a parse error escape a worker thread.
- **Concurrency**: handlers may run on multiple worker threads concurrently (same contract as Netty);
  the accept loop must survive per-connection errors and shut down cleanly when the listen fd closes.
- **Blocking I/O**: MVP uses blocking `recv`/`send` on worker threads (simple, correct). A `poll`/
  event-loop model is a possible later optimization, not needed for MVP.

## Implementation notes / learnings

- **posixlib socket API (SN 0.5.12)**: all calls came from `scala.scalanative.posix.sys.socket`,
  `netinet.in`/`inOps`, `arpa.inet`, `unistd` — no extern bindings or linker flags needed. The one
  non-obvious bit: `in_addr` is `CStruct1[in_addr_t]`, so set the address via `ia._1 = inet_addr(...)`
  (the `inOps` `s_addr` setter is value-based, not Ptr-based). Use the `sockaddr_inOps` setters
  (`sin_family_=`/`sin_port_=`/`sin_addr_=`) so BSD/macOS `sin_len` is handled; `memset` the struct
  first since `stackalloc` is not zeroed.
- **Threading** worked exactly as expected on SN 0.5.12: a daemon accept `Thread` + an
  `Executors.newFixedThreadPool`; `RxRunner.runOnce` + a `CountDownLatch`/`AtomicReference` blocks a
  worker for the handler's single `Rx[Response]`.
- **The Native libcurl *client* is currently broken on a loopback request** — `CurlChannel.send`
  returns curl error 3 (CURLE_URL_MALFORMAT) for a perfectly valid `http://localhost:PORT/...` URL,
  i.e. the URL isn't reaching libcurl (likely the variadic `curl_easy_setopt` fixed-arity binding).
  There is no existing Native HTTP round-trip test, so this was never exercised. **The server test
  therefore uses a raw POSIX-socket client** (a stronger end-to-end check anyway). Fixing/auditing
  the Native curl client is a separate follow-up (see below).

- **Review hardening (/code-review + Gemini):** reject unparseable/oversized and conflicting
  duplicate `Content-Length` headers (request-smuggling defense); suppress the body for HEAD and
  204/304/1xx responses; bound the handler `Rx` await with a timeout (→503) so a never-completing
  handler can't wedge a worker; guard the accept-loop `execute` against `RejectedExecutionException`
  (close the fd, don't kill the accept thread); validate the port range and reject an unparseable
  bind host (`inet_addr` INADDR_NONE); switch the per-connection read buffer to an `ArrayBuffer`
  (amortized O(1)) to kill an O(n²) DoS amplifier. Added regression tests for the security fixes.
- **SO_RCVTIMEO read timeout attempted and dropped:** posixlib declares `suseconds_t = CLong`, but
  macOS's `timeval.tv_usec` is a 4-byte `__int32_t`, so the posixlib `timeval` is the wrong layout
  there and `setsockopt(SO_RCVTIMEO, ...)` made `recv` return immediately. Dropped for the MVP
  (worker threads are daemon threads, so shutdown can't hang the process; handler-await is bounded).
  A portable read/idle timeout is a follow-up.

## Out of scope (follow-ups)
- **Investigate the Native libcurl client `CURLE_URL_MALFORMAT` bug** (variadic `curl_easy_setopt`
  binding) discovered while testing — likely affects all Native client requests.
- A portable per-connection **read/idle timeout** (the posixlib `timeval` layout is wrong on macOS).
- SSE (`Response.events`) + chunked transfer encoding on Native (parallel to Netty/Node).
- WebSocket on Native (would build on this server, like the JVM WS server builds on Netty).
- A `poll`-based non-blocking event loop / TLS.

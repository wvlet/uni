# A JVM HTTP server without Netty

## Goals

- Answer whether Uni's JVM server needs all of Netty, and whether a Uni-owned HTTP server should replace or sit next to uni-netty.
- Outcome: a JVM app can call `Http.server(...).start()` with only the `uni` artifact. Today that call throws until uni-netty is added.
- The built-in server stays lean (little code, no dependencies), and benchmarks show its throughput and p99 latency matching or beating Netty.
- Keep uni-netty for apps that already use it or need its extras.

## Background

- The `uni` JVM artifact has no Netty dependency. Only `uni-netty` brings in Netty, and `Http.server` finds it through `ServiceLoader` (ADR 2026-10-31).
- uni-netty already uses only part of Netty. Netty 4.2 is split into small modules, and uni-netty resolves 12 jars, about 3.8 MB:
  - Core: common (900 KB), buffer, resolver, transport (together about 1.9 MB). Every Netty server needs these.
  - HTTP: codec-base, codec-compression, handler, codec-http (about 1.6 MB). codec-http itself requires handler and codec-compression.
  - Linux epoll: classes-epoll, unix-common, and two native jars (about 0.3 MB). This is the only part we could drop.
- So making Netty smaller saves at most about 0.3 MB (epoll, falling back to NIO). The real question is whether to have a server with no Netty at all.
- Uni already has its own server: `NativeHttpServer` on Scala Native. It parses HTTP/1.1 and WebSocket frames itself (`NativeHttpProtocol`, `NativeWebSocket`, about 1,000 lines). It runs one thread per connection over a small socket layer (`NativeSocket`). The parsing code uses only `java.nio.charset` and Scala collections, so it would also compile on the JVM.
- On the JVM, a thread per connection costs little with virtual threads. Uni's Rx scheduler already uses virtual threads on JDK 24+ (`schedulerCompat.scala`). JDK 24 is also the release that stops virtual threads from blocking their carrier thread in `synchronized` blocks.
- Neither backend supports TLS. Apps put a proxy in front of the server, so TLS doesn't separate the two options.

## Design

Lane: **Standard**. This adds a new server backend and changes what `Http.server` does when uni-netty is missing. It also amends ADR 2026-10-31.

User flow:
- A JVM user adds only `uni`, writes `Http.server.withPort(8080).withHandler(...).start()`, and gets a running server with no extra dependency or setup.
- The built-in server logs one startup line at info level. The line names the backend and says that uni-netty adds request decompression and higher throughput, so the user learns what the built-in server lacks without reading the docs.
- To switch to Netty, they add uni-netty and change no code. The ServiceLoader provider takes over.

Decisions:
- **Share the protocol code between JVM and Native.** `NativeHttpProtocol` (the request parser and response writer) moves to shared `uni/src` as it is. `WebSocketFrame` is already shared.
- **Put a small connection interface between the server loop and the socket.** It covers wait-readable, read a chunk, send everything, and close. Native implements it over `NativeSocket` file descriptors, and the JVM implements it over `java.net.Socket`. Then the server loop (`NativeHttpServer`, which holds the keep-alive, SSE, streaming, and limit logic) and the WebSocket read loop also move to shared code instead of being copied. Native's behavior stays the same.
- **JVM threading: virtual threads, JDK 24+ only (your call, 2026-11-18).** On older JDKs `start()` throws and names both ways out: a newer JDK, or uni-netty. Each connection's request is handled on that connection's own virtual thread. There is no hand-off to a separate handler pool, which is one thread switch fewer per request than Netty.
- **With virtual threads, the SSE and WebSocket caps no longer protect a worker pool.** A parked connection costs only memory, so the cap is 256 like Netty's, with no 503 reserve.
- **Ideas taken from Netty (your call, 2026-11-18):** in-place parsing from one buffer per connection, `TCP_NODELAY`, one write per response, and Netty's connection draining on `stop()` (ADR 2026-10-27), which the Native server also gets.
- **A Lean 4 model of request framing (your call, 2026-11-18).** `spec/lean/UniSpec/HttpReader.lean` proves the framing rules, and generated cases replay through the Scala reader at read sizes from 1 byte to 64 KB.
- **Lean hot path, guided by profiling.** The design reuses one read buffer per connection, parses the request line and headers from bytes without splitting strings, and writes the response head and body in one write. Each optimization stays only if the benchmark shows it helps.
- **Benchmark against Netty.** The existing `examples/rpc-native/bench` harness (oha, keep-alive, JDK 25 in `rpc-bench.yml`) gets a third target: the built-in JVM server next to Netty and Native. The goal is throughput and p99 within 10% of Netty, or better, at 16 and 256 connections. The PR reports the numbers, and the README table records them.
- **The read timeout and size limits that Netty enforces also apply here.** Native already has `readTimeoutMillis`, `idleTimeoutMillis`, and `maxRequestSize`, so the shared loop brings them along. Request decompression is the one secure default that only Netty has. The built-in server passes compressed request bodies to the handler as sent, the same as the Native server.
- **The built-in server is the fallback, and uni-netty wins when present.** `HttpCompat.startServer` uses a ServiceLoader provider when one exists and the built-in server otherwise. Apps that use uni-netty keep working exactly as they do today. The PR amends ADR 2026-10-31, which says `start()` throws without uni-netty.
- **No backend-specific options at first.** The built-in server takes only the shared `HttpServerConfig` fields and the Native defaults. A `ServerOptions` type can come later if users ask for one.
- **uni-netty is unchanged.**
- **Tests:** `NativeServerTest` becomes a shared suite that runs on both JVM and Native. `HttpServerJVMTest`, which expects the old exception, now checks that the fallback server starts. The RPC benchmark gets one new row comparing the built-in server with Netty.
- **Out of scope:** HTTP/2, TLS (neither backend has it today), request decompression, and trimming Netty's own dependencies.

- **CI:** test jobs move from JDK 23 to JDK 25. Releases still build on JDK 23.

## Alternatives and Why Not?

- **Trim uni-netty's Netty dependencies:** saves only about 0.3 MB, because codec-http requires the rest.
- **`com.sun.net.httpserver` (built into the JDK):** it has no WebSocket support and controls connections and limits poorly. We would end up reimplementing most of it anyway.
- **Replace uni-netty with the built-in server:** Netty is proven under load and has epoll and decompression. Removing it would break current users for no gain.
- **Write a new JVM server from scratch:** duplicates the Native parser we already test, so the two platforms would drift apart.

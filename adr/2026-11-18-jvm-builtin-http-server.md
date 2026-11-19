# A built-in JVM HTTP server that shares the Native server's code

## Context

`Http.server` needed `uni-netty` on the JVM: without it, `start()` threw (`2026-10-31-http-server-entry-point.md`). uni-netty already uses only the part of Netty it needs. Netty 4.2 is split into small modules, but the HTTP codec still requires common, buffer, resolver, transport, codec-base, codec-compression, and handler: 12 jars, about 3.8 MB with the Linux epoll jars. Trimming Netty would have saved about 0.3 MB.

Uni already had its own HTTP/1.1 and WebSocket server for Scala Native: a request parser, a server loop with keep-alive, SSE, file bodies, streaming limits, and timeouts, all over a small POSIX socket layer. Only that socket layer was platform-specific.

## Decision

- **The server code moves to `uni/.jvm-native`**, shared by the JVM and Scala Native: `SocketHttpProtocol.scala` (the reader and the response writer), `SocketHttpServer.scala` (the server loop), and `SocketWebSocket.scala` (the WebSocket read loop and context). They talk to a `SocketConnection` (`read` with a timeout, `write`, `interrupt`, `close`) and a `SocketListener`. Native implements them over file descriptors (`NativeSocketConnection`), and the JVM over `java.net.Socket` (`JvmSocketConnection`). `NativeHttpServer` is now a thin subclass that keeps its fixed worker pool and `NativeServerConfig`.
- **On the JVM, `Http.server` falls back to the built-in server (`JvmHttpServer`) when no `HttpServerProvider` is on the classpath.** uni-netty still takes over whenever it is present, so existing apps don't change. The built-in server logs at info level that it started, and that uni-netty adds request decompression.
- **One virtual thread per connection, and JDK 24+ only.** The handler runs on the connection's thread, with no hand-off to a handler pool. A blocking read parks the virtual thread, and the JDK's poller (epoll, kqueue) resumes it. JDK 21–23 pin a virtual thread's carrier inside `synchronized` (fixed by JEP 491 in JDK 24), so on older JDKs `start()` throws and names both ways out: a newer JDK or uni-netty. CI test jobs run JDK 25.
- **No backend options for the built-in server.** It takes the shared `HttpServerConfig` settings with the Native server's defaults. A parked virtual thread costs only memory, so long-lived connections (SSE, WebSocket) are capped at 256 like Netty, without the Native pool's 503 reserve.
- **Ideas taken from Netty, where they fit a blocking design:**
  - One read buffer per connection that grows as needed, parsed in place. The reader previously copied every read into a boxed `ArrayBuffer[Byte]` and split strings. The search for the header terminator resumes where the last read ended.
  - `TCP_NODELAY` on accepted sockets, and one write per response.
  - Connection draining on `stop()`, as `2026-10-27-netty-close-connections-on-stop.md` does for Netty. Idle connections close at once (a request whose bytes are still arriving counts as idle, as in Netty), a busy connection closes after its last response (sent with `Connection: close`), and whatever remains at the deadline is interrupted. `stop()` wakes a worker through `SocketConnection.interrupt` (`shutdown(2)` on Native), so a connection is never closed under the thread still using it. The Native server gets this too.
- **A Lean model states the request framing** (`spec/lean/UniSpec/HttpReader.lean`, following `2026-11-18-lean-specs.md`). It proves that a request consumes exactly its header block, the terminator, and its `Content-Length` bytes, and that conflicting `Content-Length` headers and chunked bodies are rejected. These are the rules that keep request smuggling out. `HttpReaderSpecTest` replays the generated cases, splitting each byte stream into reads of 1 to 64 KB bytes, so where a read ends can never change where a request ends.

## Alternatives

- **Trim uni-netty's Netty dependencies:** saves about 0.3 MB, because the HTTP codec requires the rest.
- **`com.sun.net.httpserver`:** no WebSocket support, and little control over connections and limits.
- **Replace uni-netty:** Netty adds request decompression, the annotation router integration, and many tuning options that users already set.
- **A fixed platform-thread pool on JDK 17–23:** each keep-alive connection would hold a thread, so a pool small enough to be cheap would queue new connections behind idle ones, as the Native server does with 16 workers.

## Consequences

- A JVM app on JDK 24+ can serve `Http.server` with `uni` alone. The `examples/rpc-native` benchmark adds a "JVM built-in" target (the same jars without Netty). In the first local sweep on an Apple M5 Pro (oha, keep-alive RPC calls), it served about 99k req/s at 16 connections and about 121k at 256, against about 94k and 108k for Netty, with lower p99 latency. Client and server shared the cores, so treat the numbers as a rough comparison.
- The built-in server does not decode compressed request bodies. They reach the handler as sent, the same as on Native.
- Changing the reader's framing means changing the Lean model and regenerating its cases.

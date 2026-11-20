# Keep the socket server healthy over long uptimes

## Context

The built-in JVM server and the Scala Native server share `SocketHttpServer` (`2026-11-18-jvm-builtin-http-server.md`). Next to uni-netty, a process that runs for weeks exposed five gaps:

- The step that shrinks a connection's read buffer between requests ran only before parking in the Native idle poller (`2026-11-19-native-server-idle-poller.md`). On the JVM there is no poller, so a keep-alive connection kept the largest buffer it ever grew, up to 2 MiB after a 1 MiB upload.
- The accept thread guarded only `pool.execute`, and only against `NonFatal`. Any other exception, or an `OutOfMemoryError`, ended the thread. The server then accepted nothing while `isRunning` was still true, and `awaitTermination()`, which joins that thread, returned. The write watchdog could die the same way, which silently turned off write timeouts. Failed accepts (`EMFILE`) were logged at debug only.
- An async handler (a pending `Rx`) got a 503 after 30 s and was never cancelled. uni-netty has no handler timeout, and synchronous handlers, including most RPC methods, never had one on either backend.
- `stop()` waited 5 s for busy connections, against Netty's 30 s default. SSE streams counted as busy, so `stop()` with an open stream always took the full 5 s.
- `stop()` from a handler waited for its own request to finish.

## Decision

- **Shrink the read buffer after every request**, on every platform, so an idle connection holds 8 KiB.
- **The accept loop and the write watchdog survive every throwable**, as Netty's event loops do. They log it at warn, at most once per 5 s with a count of the repeats held back. Consecutive failed accepts back off from 10 ms, doubling up to 1 s (Netty pauses accepting for 1 s), so fd exhaustion neither spins nor floods the log, and a single transient failure costs only 10 ms.
- **A handler timeout cancels the handler's `Rx`**, so the work stops. So does `stop()` interrupting a worker that waits on one.
- **The built-in JVM server has no handler timeout**, as on Netty. A waiting handler parks a virtual thread, which costs only memory. Native keeps its 30 s `handlerTimeoutMillis`, because a waiting handler holds one of its few workers.
- **`stop()` waits up to 30 s** for in-flight requests, Netty's default. Idle connections, SSE streams, and WebSockets close at once: a stream may never end, Netty also closes WebSockets at once, and `EventSource` clients reconnect. An SSE stream waits on its event source, not on the socket, so `stop()` also wakes that wait.
- **`stop()` from a handler** shuts down on a new thread and returns, as uni-netty does (`2026-10-27-netty-close-connections-on-stop.md`). That thread is not a daemon. Connection threads are virtual threads, which are daemons, and `awaitTermination()` returns once the listener closes, so without it the JVM could exit mid-drain.
- `SocketServerStabilityTest` checks each of these, plus that open connections and streaming slots return to zero after many clients come and go.

## Why not

- **Keep a 30 s handler timeout on the JVM, now with cancellation:** it guards against an `Rx` that never completes. But it applies only to async handlers, and it makes the same handler fail on one JVM backend and work on the other.
- **Keep SSE streams open until the shutdown deadline, as Netty does:** every `stop()` with a live feed would take 30 s.
- **A JVM shutdown hook, as `Netty.options.withShutdownHook`:** Netty's is off by default, the built-in server takes no options, and apps already call `stop()` from their own hook or a `Design` lifecycle.
- **A cap on total connections:** Netty has none. File descriptor limits and a reverse proxy already bound it, and a cap turns a load spike into refused connections.

## Consequences

- A client that disconnects during a long handler call is noticed only when the response write fails, as on Netty.
- `stop()` on a server with a request still running can take up to 30 s, not 5 s.

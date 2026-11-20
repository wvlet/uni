# Keep the built-in JVM HTTP server healthy over long uptimes

## Goals

- The built-in JVM server (`JvmHttpServer`, the shared `SocketHttpServer` loop) should hold up as well as uni-netty in a process that runs for weeks: no memory that only grows, no silent loss of the accept loop, no leaked handler work, and long-running handlers that behave as they do on Netty.
- Make each of these properties checked by a test, so a later change can't quietly undo it.

## Background

- The built-in server runs one virtual thread per connection (JDK 24+) and shares its HTTP code with Scala Native (ADR `2026-11-18-jvm-builtin-http-server.md`). It has fixed defaults and no backend options.
- Native gained an idle poller (`2026-11-19-native-server-idle-poller.md`). The step that shrinks a connection's read buffer between requests now runs only on the poller path, so on the JVM it never runs: a keep-alive connection that once received a 1 MiB body keeps a buffer of up to 2 MiB (it doubles) for its life. 10k such connections hold 10–20 GB.
- The accept loop runs on one platform thread and guards only `pool.execute` (with `NonFatal`); `accept()` maps only `IOException`. Any other exception, or a fatal error such as `OutOfMemoryError`, ends the thread. The server then stops accepting while `isRunning` still says true, and `awaitTermination()` (which joins the accept thread) returns, so a `main` blocked on it exits. Netty's event loop logs such errors and keeps going.
- The write watchdog thread has the same weakness: a fatal error there silently turns off write timeouts for every connection.
- Accept failures (e.g. `EMFILE`, too many open files) are logged at debug with a 10 ms retry, so fd exhaustion is invisible in production logs. Netty logs them as warnings and pauses accepting for 1 s.
- An async handler (one returning a pending `Rx` or `Future`) that takes over 30 s gets a 503, and its `Rx` is never cancelled: the subscription keeps running after the response is gone. Synchronous handlers, including most RPC methods, run inline and never time out. uni-netty has no handler timeout for either, so a 45 s async RPC call works on Netty but fails on the built-in server.
- `stop()` drains busy connections for a fixed 5 s (then 1 s forced). Netty drains for 30 s by default. Under a Kubernetes SIGTERM, an in-flight request longer than 5 s is cut off on the built-in server.

## Design

Lane: Standard — changes server behavior (handler timeout, logging) that apps can notice.

- **Shrink the read buffer after every request on all platforms**, not only before parking in the poller. An idle connection then holds an 8 KiB buffer, as on Native.
- **Keep the accept loop and the write watchdog alive.** Catch every throwable except a stop request, log it at warn (rate-limited, at most one per few seconds), back off, and keep going. Accept failures such as fd exhaustion back off 1 s, as Netty does, so they don't spin.
- **Cancel the handler's `Rx` when the handler timeout fires**, on both JVM and Native, so timed-out async work stops instead of leaking. (Cancelling can stop only async work; a synchronous handler has already returned.)
- **No handler timeout on the built-in JVM server**, as on Netty: synchronous handlers already have none, so this makes async ones match. Native keeps its 30 s `handlerTimeoutMillis`, since a waiting handler holds one of its few workers. A client that disconnects during a long handler call is not detected until the response write fails, also as on Netty.
- **Drain for up to 30 s on `stop()`**, Netty's default, so in-flight requests finish during a rolling restart. Idle connections still close at once, so an idle server stops as fast as today.
- **Leak checks as tests.** After many keep-alive connections (with large bodies, timeouts, SSE, WebSocket) open and close, the server's connection set, streaming slots, and live server threads return to their baseline, and idle connections hold small buffers.
- Update `docs/http/server.md` (handler timeout row, built-in server notes) and record the handler-timeout decision in an ADR.

## Alternatives and Why Not?

- **Keep the 30 s handler timeout on the JVM, now with cancellation.** Safer against a never-completing `Rx`, but a parked virtual thread costs only memory, and the difference from Netty surprises apps that switch backends.
- **Keep the 5 s drain.** Faster `stop()` for busy servers, but cuts off requests that Netty would let finish.
- **A JVM shutdown hook, like Netty's `withShutdownHook`.** Netty's is off by default; the built-in server has no options, and apps already call `stop()` from their own hook or `Design` lifecycle.
- **Add a total-connection cap.** Netty has none; fd limits and a reverse proxy already bound it, and a cap would turn load spikes into refused connections.
- **Run handlers on a separate pool, like Netty's `handlerExecutorThreads`.** A virtual thread per connection already keeps blocking handlers off other connections; a pool would add a hand-off per request.

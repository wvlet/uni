# Scale the Scala Native HTTP server past one worker per connection

## Goals

- A Native server should serve hundreds of concurrent keep-alive connections, as the JVM built-in server (virtual threads) and Netty do. Today the 17th open connection gets no response until another one closes.
- Raise throughput per request on Native by cutting avoidable CPU work in the socket layer.
- Keep the shared server code (`uni/.jvm-native`) behaving the same on the JVM.

## Background

- `SocketHttpServer` (shared by JVM and Native) runs one task per connection for the connection's whole life. On the JVM that task is a virtual thread, which parks cheaply while the connection is idle.
- `NativeHttpServer` runs those tasks on a fixed pool of `workerThreads` (16 by default). An idle keep-alive connection pins a worker while it waits up to `idleTimeoutMillis` (30 s) for its next request, so new connections queue behind idle ones. The bench README documents this as "no response" in the `--sweep` mode at 64 and 256 connections.
- Scala Native 0.5 has no virtual threads, so the fix has to release the worker between requests rather than make threads cheap.
- The socket layer copies every received and sent byte one at a time through a `stackalloc` buffer, in a bounds-checked loop. Scala Native's GCs (Immix, Commix, Boehm) don't move objects, so `recv`/`send` can read and write the Scala array in place, as Scala Native's own javalib does.
- ADR 2026-07-08: OS differences stay in `uni_socket_shim.c`; anything that parks a thread must be `@blocking`. ADR 2026-10-30: NativeServer apps build with Boehm; that doesn't change here.

## Design

Lane: Standard — it changes how the Native server schedules connections (a visible capacity change and new native code), so the direction needs approval.

- **Idle connections park in a poller, not a worker.** After a response, if the reader holds no buffered bytes and the socket has nothing to read yet, the worker registers the connection with one poller thread and returns to the pool. When the socket turns readable (or the peer hangs up), the poller hands the connection back to the pool, and a worker resumes with the same reader state.
- **Workers now serve requests, not connections.** `workerThreads` bounds concurrent handler executions; open connections are bounded only by the fd limit, as with Netty. The default stays 16; scaladoc and docs change from "one connection per worker" to "one request per worker".
- **One owner at a time.** A connection belongs to its worker or to the poller, never both, and only the owner closes it. `stop()` and other threads only wake the owner, as the shared server already does with `interrupt()`.
- **Resumable connection state.** The reader, request deadline, and `ConnectionState` move from `handleConnection`'s locals into one per-connection object. Its cleanup (`connections.remove` + `close`) runs only when the connection really ends, not when a worker hands it to the poller. The JVM runs the same object without a poller, so it loops on one virtual thread as today.
- **Poller in C: epoll on Linux, kqueue on macOS/BSD**, with one-shot registration. Readiness costs O(ready), not O(open connections). Events carry a per-connection token, not the raw fd, so a reused fd number can't wake the wrong connection. The code sits in the shim's POSIX branch behind OS guards. Its wait call is `@blocking`, and only fixed-arity ints cross into Scala (ADR 2026-07-08). A pipe wakes the poller on `stop()`.
- **Windows and other unix keep today's model.** The shim reports no poller there, and the server falls back to a worker per connection. Windows Native stays correct without a third backend; it can get one (WSAPoll) later.
- **Idle timeout by a periodic sweep.** The poller wakes every quarter of `idleTimeoutMillis` (at most 1 s) and closes connections parked past their deadline. The sweep runs a few times a second, not on every wake, so its cost doesn't grow with request rate. (Changed from a FIFO queue during implementation: every park would add an entry, millions under load.)
- **Shutdown order.** `stop()` sets `running = false` and closes the poller first: it wakes and joins the poller thread, which closes every parked connection. A worker that tries to park after that keeps the connection, sees the server stopping, and closes it. The poller closes any connection it can't hand back to a rejected pool. Then busy connections drain and the pool shuts down as today (Netty drain ADR).
- **SSE and WebSocket still hold a worker** for their lifetime, under the existing `maxStreamingConnections` cap.
- **The JVM built-in server is unchanged**: it supplies no poller, so its code path stays the virtual-thread-per-connection loop.
- **Socket I/O reads and writes Scala arrays in place**, dropping the per-byte copy loops and the 8 KB per-call cap. A zero-length send returns before taking a pointer.
- **Known limit: a request still being received holds its worker**, for up to `readTimeoutMillis` (30 s). Sixteen slow or stalled clients mid-request can still fill the pool. Parking mid-request needs a resumable parser, which is out of scope here.
- **Docs and ADR.** A new ADR records the poller design. ADR 2026-11-18 ("keeps its fixed worker pool") and ADR 2026-07-08's shim table get pointers to it. The bench README sweep note and the `NativeHttpServer` and `withMaxStreamingConnections` scaladocs change from per-connection to per-request workers.
- Tests cover more open idle connections than workers (each still answered), idle-timeout close and `stop()` while parked, pipelined requests (buffered bytes skip parking), and a peer hanging up while parked. The bench `--sweep` (1/16/64/256) before and after goes in the PR.

## Alternatives and Why Not?

- **Raise the default `workerThreads` to hundreds.** Each Native thread costs a full stack and GC root scanning; it moves the cliff instead of removing it.
- **Fully event-driven (non-blocking parsing and handlers).** Rewrites the shared protocol code that JVM and Native share; parking only the idle phase gets the connection scalability with a small diff.
- **`poll()` over all parked fds.** Portable (including Windows), but every wake scans every open connection, which costs more than the request itself at a few hundred connections.

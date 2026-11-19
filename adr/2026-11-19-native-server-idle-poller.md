# Native server: idle keep-alive connections wait in an epoll/kqueue poller

- Status: Accepted
- Date: 2026-11-19
- Plan: [`plans/2026-11-19-native-server-idle-poller.md`](../plans/2026-11-19-native-server-idle-poller.md)

## Context

`SocketHttpServer` (shared by the JVM built-in server and Scala Native) runs one task per connection for its whole life. The JVM runs that task on a virtual thread, which parks cheaply while the connection waits for its next request. `NativeHttpServer` runs it on a fixed pool of `workerThreads` (16), so an idle keep-alive connection held a worker for up to `idleTimeoutMillis` (30 s). The 17th open connection got no response until one closed: the bench sweep showed "no response in 7s" for a fresh connection at 16, 64, and 256 connections. Scala Native 0.5 has no virtual threads.

## Decision

- **Between requests a connection leaves its worker.** When the reader holds no buffered bytes (CR/LF left before the next request line are dropped) and the socket is not yet readable, the worker hands the connection to an `IdlePoller` and returns to the pool. When the socket turns readable or the peer hangs up, the poller hands it back to the pool. The connection's state (reader buffer, request deadline, `ConnectionState`) lives in a per-connection `ConnectionSession`, so whichever worker resumes it continues where the last one stopped. Bytes already buffered (a pipelined request) are served at once, without a round trip through the poller.
- **One owner at a time, and only the owner closes.** Ownership moves through the poller's `parked` map: whoever removes a connection's entry owns it (the poller thread on readiness, idle timeout, or close; or the worker, when registration fails). The session's cleanup (`connections.remove` and `close`) runs only when the connection ends, never when a worker parks it.
- **Tokens, not fds.** Each park registers a fresh 64-bit token in `epoll_data.u64` or kqueue's `udata`, with one-shot registration. A parked connection closed by its idle timeout frees its fd number, and a reused number can't wake the wrong connection.
- **Poller in `uni_socket_shim.c`**: epoll on Linux, kqueue on macOS and the BSDs, in the POSIX branch behind OS guards. Only ints and tokens cross into Scala. `uni_poller_wait` is `@blocking` (ADR 2026-07-08). A non-blocking pipe, registered under token 0, wakes the poller on `close()`.
- **Windows and other systems keep a worker per connection.** `uni_poller_create` returns -1 there, `newIdlePoller` gives None, and the loop never parks. A WSAPoll backend can come later.
- **Idle timeouts by a periodic sweep.** The poller's wait times out every quarter of `idleTimeoutMillis` (at most 1 s) and closes connections parked past their deadline, so one closes within `idleTimeoutMillis + tick`. The sweep runs a few times a second, not on every wake, so its cost doesn't grow with request rate. A FIFO queue of deadlines was rejected because every park would add an entry, millions under load.
- **Shutdown order.** `stop()` sets `running = false`, closes the listener, then closes the poller: it wakes the poller thread, joins it, and the thread expires every parked connection. A park after that fails (the poller is closed), and the worker's reader sees the server stopping and closes the connection. A connection resumed into a pool that has shut down is closed by the poller. Busy connections then drain as before (ADR 2026-10-27).
- **The JVM is unchanged.** `newIdlePoller` defaults to None, so the built-in server keeps one virtual thread per connection.
- **Socket I/O reads and writes Scala arrays in place.** `recvInto` and `sendAll` pass `array.at(off)` to `recv`/`send`, which Scala Native's non-moving collectors allow (its own javalib does the same), instead of copying each byte through a `stackalloc` buffer in 8 KB pieces.
- **Defaults stay**: 16 workers, and streaming connections (SSE, WebSocket) still hold a worker under `maxStreamingConnections` (12).

## Consequences

- Open connections are limited by the fd limit, not by `workerThreads`. `workerThreads` now bounds concurrent requests. Measured with `examples/rpc-native/bench/bench.py --sweep 1,16,64,256` (Apple M5 Pro, Boehm GC, release-full; client and server share cores): a fresh connection is answered in 0.5–18 ms at every level (before: no response at 16 and above), and Native throughput went from 12.6k/10.4k/8.7k/7.8k to 13.8k/13.4k/14.5k/15.0k req/s at 1/16/64/256 connections. All 256 connections are served, where only 16 were before.
- Throughput is still far below the JVM's (about 80k–125k req/s in the same run). That gap appears at 16 connections, where each connection had its own worker before this change, so it comes from multi-threaded execution under the Boehm GC (ADR 2026-10-30), not from scheduling. It is left for a separate change.
- A request whose bytes are still arriving holds its worker for up to `readTimeoutMillis`, so 16 stalled clients mid-request can still fill the pool. Parking mid-request needs a resumable parser.
- A freshly accepted connection whose request hasn't arrived yet also parks, so a client that connects and sends nothing holds no worker.

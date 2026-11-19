# Native server: TCP_NODELAY and fewer syscalls per keep-alive request

## Goals

- Match the JVM built-in server, which disables Nagle on every accepted socket. Native doesn't, so a response sent in more than one write can stall up to the client's delayed-ACK timeout (about 40 ms on Linux).
- Cut the syscalls a keep-alive request pays when its connection parks in the idle poller between requests.

## Background

- Follow-up "PR A" to `plans/2026-11-19-native-server-throughput.md` (#976, #978 done; Immix waits for Scala Native 0.5.13).
- Responses written in several pieces hit Nagle: `sendFile` (head, then chunks), bodies over 64 KB (`WriteTrackingConnection` pieces), SSE events, WebSocket frames, pipelined responses. A single-write RPC response mostly does not, so throughput benchmarks barely move.
- Parking costs, per request: `poll(0)` in `NativeIdlePoller.park` to check for bytes already waiting, then, on Linux, `epoll_ctl(ADD)`, which fails with `EEXIST` on every park after the first (a one-shot fd stays registered after it fires), then `epoll_ctl(MOD)`.
- When the next request is already waiting, the `poll(0)` is followed by a `recv` in `readRequest`, so two syscalls do one read.

## Design

Lane: Express — a small non-UI socket-layer change with a clear diagnosis; no always-gate area.

- **TCP_NODELAY on accept**, with posixlib's `netinet.tcp` (its C glue builds on Windows too). Best effort, like `SO_KEEPALIVE`.
- **Re-arm one-shot registrations with `EPOLL_CTL_MOD` first**, falling back to `ADD` on `ENOENT`. The first park of a connection costs two calls, every later one costs one. The poller's wake pipe still registers with `ADD`. kqueue's `EV_ADD` already takes one call.
- **Read instead of probing before parking.** A new reader method reads whatever is already waiting into its buffer, without waiting, and reports bytes, nothing, or EOF. Bytes are served at once; EOF ends the connection as today; only "nothing" parks. It keeps the stop check, and a "nothing" result never reaches `readRequest`, where 0 means a 408 timeout.
- `park` drops its `poll(0)`: epoll and kqueue check readiness at registration, so bytes arriving between the read and the registration still wake the poller.
- A read with timeout 0 on Native returns after the non-blocking `recv` alone, where `MSG_DONTWAIT` exists. Windows has none, so its shim reports that apart and the read still polls (SSE's disconnect probe relies on it).
- ADR 2026-11-19 (idle poller) gets the new park condition and the Windows note.
- The JVM server has no poller, so its path is unchanged.
- Measured: BSD syscalls per request on macOS (`top`'s cumulative SYSBSD count over a fixed oha run; dtruss needs SIP off), before and after. Linux epoll counts follow from the code path; `strace -c` under Docker with `--cap-add SYS_PTRACE` can confirm them in CI-like setups.
- Tests: the existing poller and keep-alive suites (pipelining, parked idle timeout, peer hang-up) cover the changed path; a check that accepted sockets have `TCP_NODELAY` set.

## Alternatives and Why Not?

- Track "registered" per connection in Scala and pass it to the shim: same syscall count as MOD-first after the first park, with more state.
- Set `TCP_NODELAY` in the C shim: works, but posixlib already covers every platform.

# Native curl client: CURLOPT_NOSIGNAL and a per-thread SIGPIPE block

- Status: Accepted
- Date: 2026-11-20
- Plan: [`plans/2026-11-20-curl-nosignal.md`](../plans/2026-11-20-curl-nosignal.md)

## Context

libcurl requires `CURLOPT_NOSIGNAL=1` in multithreaded programs, and a Uni Native process always has worker, poller, and GC threads. Without the option, an OpenSSL, LibreSSL, mbedTLS, or wolfSSL build of libcurl (Ubuntu's libcurl4-openssl, macOS system curl) sets the process-wide SIGPIPE disposition to `SIG_IGN` around every `curl_easy_perform`, `curl_multi_perform`, and cleanup call, then restores it. Concurrent transfers race on that swap, so one can restore `SIG_DFL` while another is still writing. A build with the synchronous resolver also uses `alarm()` + `siglongjmp` for DNS timeouts, and the SIGALRM can land on any thread.

With the option set, curl leaves SIGPIPE alone. curl 8.x routes all writes, TLS included, through its own send with `MSG_NOSIGNAL` (Linux) or `SO_NOSIGPIPE` (macOS). curl 7.x with OpenSSL (7.81 on Ubuntu 22.04) hands OpenSSL the raw fd, and OpenSSL's `send(fd, …, 0)` to a closed peer raises SIGPIPE on Linux. Scala Native does not ignore SIGPIPE, so the signal would kill the process.

## Decision

- **Every easy handle sets `CURLOPT_NOSIGNAL=1`** in `CurlChannel.withTransfer`, the one place the sync, async, and streaming paths create a handle.
- **Each transfer blocks SIGPIPE on its own thread.** `CurlBindings.withSigpipeBlocked` wraps the whole `withTransfer`, cleanup included (which can send a TLS close_notify). While SIGPIPE is blocked, a write to a closed peer fails with EPIPE and the signal stays pending on that thread only. Before unblocking, the helper discards it: `sigtimedwait` with a zero timeout, retried on EINTR, or `sigpending` + `sigwait` on macOS, which has no `sigtimedwait`. If SIGPIPE was already blocked, the mask and any pending signal are left to the caller. Streaming callbacks (`emit`, `onResponse`) also run in the blocked section; a SIGPIPE they raise is discarded too.
- **The helpers live in `uni_curl_shim.c`'s POSIX branch** and use only libc and pthread, so the shim still references no libcurl symbol (ADR 2026-07-06). They are no-ops on Windows, which has no SIGPIPE.

## Alternatives

- `signal(SIGPIPE, SIG_IGN)` once at factory init: simplest, but it changes the host program's signal handling. The socket shim avoids that for the same reason (ADR 2026-11-18, WebSocket relay).
- `CURLOPT_NOSIGNAL` alone: safe on curl 8.x, but not on curl 7.x with OpenSSL.
- `CURLOPT_SOCKOPTFUNCTION` setting `SO_NOSIGPIPE`: curl already does this on macOS, and Linux has no per-socket equivalent.

## Consequences

- `NativeCurlSignalTest` checks that the process's SIGPIPE handler survives a transfer (without `NOSIGNAL` it reads `SIG_IGN` mid-transfer), and that a SIGPIPE raised inside the blocked section is discarded.
- On macOS, a process-directed SIGPIPE (`kill(pid, SIGPIPE)`) that shows as pending could be taken by another thread between `sigpending` and `sigwait`, leaving the HTTP thread blocked in `sigwait`. This needs an external SIGPIPE sent while every thread blocks it, and is accepted.

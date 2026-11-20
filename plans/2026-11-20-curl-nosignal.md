# Make the Native curl client safe in multithreaded programs

## Goals

- Stop libcurl from installing process-wide signal handling for every transfer. Uni's Native runtime runs worker, poller, and GC threads, and libcurl's docs require `CURLOPT_NOSIGNAL=1` in multithreaded programs.
- Keep a peer that closes mid-write from killing the process with SIGPIPE once curl stops guarding against it.

## Background

- `CurlChannel.withTransfer` creates the only easy handle. The sync (`send`), async, and streaming (`stream`) paths all go through it, so one place covers all three.
- Without `CURLOPT_NOSIGNAL`, libcurl does two things:
  - **SIGALRM DNS timeout.** With the synchronous resolver and a timeout set, curl arms `alarm()` around `getaddrinfo` and `siglongjmp`s out of the SIGALRM handler. The alarm can land on any thread. Debian/Ubuntu and macOS builds use the threaded resolver (AsynchDNS), so this path is off there, but a sync-resolver build (some static or minimal builds) would use it.
  - **SIGPIPE swap.** In builds with OpenSSL, LibreSSL, mbedTLS, or wolfSSL (Ubuntu's libcurl4-openssl, macOS system curl), every `curl_easy_perform`, `curl_multi_perform`, and cleanup call sets SIGPIPE to `SIG_IGN` with `sigaction` and restores it afterwards (`lib/sigpipe.h`). The disposition is process-wide, so two concurrent transfers race: one restores `SIG_DFL` while the other is still writing, or the process is left with SIGPIPE ignored for good. Other threads see the swap too.
- With `NOSIGNAL=1`, curl leaves SIGPIPE alone and relies on its own socket writes: `MSG_NOSIGNAL` on Linux and `SO_NOSIGPIPE` on macOS. This covers everything on curl 8.x, which routes OpenSSL I/O through curl's own send. curl 7.x (7.81 on Ubuntu 22.04) gives OpenSSL the raw fd (`SSL_set_fd`). OpenSSL then writes with `send(fd, …, 0)`, so a TLS write or close_notify to a closed peer can raise SIGPIPE and kill the process.
- Scala Native 0.5.12 does not ignore SIGPIPE. The server side avoids SIGPIPE per socket (`uni_socket_shim.c`: `MSG_NOSIGNAL`/`SO_NOSIGPIPE`) and on purpose never sets a process-wide `SIG_IGN`.
- `curl_global_init` runs once, in the `NativeHttpChannelFactory` object initializer, before any handle is created, and nothing else in Uni calls it. Scala Native's object initialization is thread-safe, so concurrent first use initializes curl once. No change is needed there. No other per-handle option matters for thread safety: Uni uses no share handles and no global DNS cache.

## Design

Lane: **Express**. A fix with a clear diagnosis, internal to the Native HTTP client, touching no always-gate area. The launch prompt says not to merge, so the PR is held for the user.

- Set `CURLOPT_NOSIGNAL=1` in `withTransfer`, through the existing fixed-arity `uni_curl_easy_setopt_long` shim. The shim gains no new libcurl symbol.
- Replace curl's process-wide SIGPIPE swap with a per-thread one. Block SIGPIPE in the calling thread's signal mask (`pthread_sigmask`) for the whole transfer, including cleanup. A write to a closed peer then fails with EPIPE and leaves the signal pending on that thread only. Before unblocking, discard a pending SIGPIPE (`sigpending` + `sigwait`). macOS has no `sigtimedwait`. libpq uses the same technique.
  - The two helpers live in `uni_curl_shim.c`, POSIX branch. They reference only libc and pthread, so `check-curl-shim.sh` and the weak-linking ADR still hold. They are no-ops on Windows, which has no SIGPIPE.
  - If SIGPIPE was already blocked on the thread, the helpers leave the mask and any pending signal to the caller.
- Tests (Native, skipped on Windows):
  - During a transfer, the in-process server's handler reads the process's SIGPIPE disposition and checks that it is still a sentinel handler the test installed. It fails today, because curl swaps in `SIG_IGN`.
  - A SIGPIPE raised on the thread inside the blocked section is discarded and the process survives.

## Alternatives and Why Not?

- **`signal(SIGPIPE, SIG_IGN)` once at factory init.** Simplest, but it changes the host program's disposition. The socket shim deliberately avoids that.
- **`NOSIGNAL=1` alone, relying on curl's `MSG_NOSIGNAL`.** Safe on curl 8.x, but not on curl 7.x with OpenSSL (Ubuntu 22.04), where TLS writes bypass it.
- **`CURLOPT_SOCKOPTFUNCTION` to set `SO_NOSIGPIPE`.** curl already does this on macOS, and Linux has no per-socket equivalent.

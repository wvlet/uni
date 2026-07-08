# 2026-07-08: `uni_socket_shim.c` carries the Windows socket differences, in C

## Context

uni's Native HTTP server and WebSocket support (`NativeHttpServer`, `NativeWebSocket`,
`NativeSocket`) are built directly on Scala Native's posixlib sockets. On
Windows that binary would not even link:

    error LNK2019: unresolved external symbol poll
    error LNK2019: unresolved external symbol scalanative_pollin
      referenced in function ...wvlet.uni.http.NativeServerTest...
    fatal error LNK1120: 5 unresolved externals

Scala Native's `posixlib/poll.c` wraps its whole body in
`#if defined(__unix__) || defined(__unix) || defined(unix) || (defined(__APPLE__) && defined(__MACH__))`,
so `poll` and the `scalanative_poll*` constant accessors do not exist on Windows.
That single gap made uni's entire native test binary unlinkable there — which is
why uni had no Windows Scala Native CI at all, and therefore why v2026.1.17 could
ship a POSIX-only `#include <dlfcn.h>` in `uni_curl_shim.c` and break every
downstream Windows build instead of failing here (see
[`2026-07-06-curl-shim-weak-linking.md`](2026-07-06-curl-shim-weak-linking.md)).

Three calls differ on Windows. Everything else uni uses — `socket`, `bind`,
`listen`, `accept`, `recv`, `send`, `setsockopt`, `shutdown` — posixlib already
maps onto winsock, and the failed link proved it: `poll` was the *only* thing
missing.

## Decision

Put all three behind fixed-arity C functions in
`uni/.native/src/main/resources/scala-native/uni_socket_shim.c`, called from an
`@extern object SocketShim`:

| | POSIX | Windows |
|---|---|---|
| `uni_socket_startup()` | nothing | `WSAStartup`, once, via `InitOnceExecuteOnce` |
| `uni_socket_wait_readable(fd, ms)` | `poll` | `WSAPoll` |
| `uni_socket_close(fd)` | `close` | `closesocket` |

## Non-obvious points a future reader would otherwise reverse-engineer

### The OS split has to be in C — Scala cannot express it

Scala Native has no per-OS source directory (`.native` is per-*platform*, not
per-OS), and dead-code elimination keeps every *reachable* branch. So a runtime
`if (Platform.isWindows) ... else scalanative.posix.poll.poll(...)` in Scala
still links both sides, and still fails on `scalanative_pollin`. Merely
*referencing* `scala.scalanative.posix.poll` is what breaks the build. The branch
must happen at C preprocessing time.

`scala.scalanative.windows.WinSocketApi` does expose `WSAPoll`, but it is the
mirror image of the same problem: referencing it from shared Scala makes the
POSIX link fail on `ws2_32`.

### Why the whole readable-wait contract lives in C, not just the `poll` call

POSIX `struct pollfd.fd` is an `int`; Windows `WSAPOLLFD.fd` is a `SOCKET`, a
64-bit `UINT_PTR` on Win64. One Scala `CStruct3` binding cannot describe both
layouts. Returning a plain `int` verdict (1 readable / 0 timeout / -1 error)
keeps the struct on the C side entirely. The EINTR retry and the `revents`
interpretation moved along with it, since they are meaningless to split.

### `#pragma comment(lib, "ws2_32.lib")` — not `@link("ws2_32")`

Scala Native compiles this `.c` into **every** downstream binary, including ones
that never open a socket. A bare `WSAPoll` reference with no guaranteed
`-lws2_32` would break those links — exactly the trap `uni_curl_shim.c` fell into
in #622. A `#pragma comment(lib, ...)` embeds the dependency in the object file's
linker directives, so it travels with the object and always applies; a
Scala-level `@link("ws2_32")` would be dropped by DCE when `CurlBindings`-style
reachability fails. Scala Native's own `posixlib/sys/socket.c` uses the same
pragma. MSVC-family linkers only, which is what Scala Native's Windows support
targets.

### Windows needs `WSAStartup`, and nothing else was going to call it

Winsock rejects every `socket()` with `WSANOTINITIALISED` until `WSAStartup` has
run in the process. Scala Native calls it from `WinSocketApiOps.init()`, reached
only by javalib's `java.net` implementations — which uni's posixlib-based sockets
never touch. `NativeSocket.bindAndListen` and `.connect` therefore call
`ensureStarted()` first. The C side is `InitOnceExecuteOnce`-guarded and
idempotent, so callers need not track it. There is no matching `WSACleanup`: the
sockets live as long as the process.

### `close(fd)` on a Windows socket silently does the wrong thing

`close` *links* on Windows — `oldnames.lib` aliases it to the CRT's `_close` —
but `_close` operates on CRT file descriptors, not socket handles. It would fail
with `EBADF` and leak the socket. Sockets must be closed with `closesocket`. Note
this applies to the error-cleanup paths in `bindAndListen` / `connect` too, not
only the public `close`.

### Narrowing `SOCKET` to `int` is safe

Scala Native stores a socket as `CInt`, having narrowed the `SOCKET` winsock
returned. Widening it back with `(SOCKET)(UINT_PTR)fd` is sound: Windows keeps
socket handles inside the low 32 bits precisely so they can be passed through
`int` for interoperability.

### `POLLIN` still works on Windows

`WSAPoll` only ever sets `POLLRDNORM` in `revents`, but `winsock2.h` defines
`POLLIN` as `(POLLRDNORM | POLLRDBAND)`, so testing `revents & POLLIN` reads the
same on both platforms. `winsock2.h` also supplies `POLLERR`, `POLLHUP` and
`POLLNVAL`, so the verdict logic is shared, not duplicated.

## Consequences

- uni's Scala Native test binary links and runs on Windows, so CI gained a real
  `Scala Native (Windows)` job. That job — not a standalone `clang` compile — is
  now what would have caught v2026.1.17's `<dlfcn.h>` regression, and it exercises
  both shims at runtime.
- `NativeSocket` no longer imports `scalanative.posix.poll` or
  `scalanative.posix.unistd`. Re-adding either would re-break Windows; the CI job
  is what notices.
- Error *messages* on Windows are still POSIX-shaped: winsock reports failures via
  `WSAGetLastError()`, not `errno`, so a failed `bind` yields uni's generic
  message rather than a specific cause. uni never read `errno` here, so nothing
  regressed — but a future "why did bind fail" improvement needs a Windows branch.
- `WSAPoll` before Windows 10 2004 does not report a failed connection attempt in
  `revents`. uni only polls accepted/connected sockets for readability, so this
  does not bite; do not extend the shim to poll for connect-completion without
  revisiting it.

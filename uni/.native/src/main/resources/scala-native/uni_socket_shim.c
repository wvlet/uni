/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * The three places uni's Native sockets need something other than the POSIX call, so that
 * NativeHttpServer / NativeWebSocket work on Windows as well as unix:
 *
 *   startup        POSIX: nothing.            Windows: WSAStartup, or every socket() fails.
 *   readable-wait  POSIX: poll().             Windows: WSAPoll().
 *   close          POSIX: close().            Windows: closesocket().
 *
 * Everything else uni calls — socket, bind, listen, accept, recv, send, setsockopt, shutdown — is
 * mapped onto winsock by Scala Native's own posixlib, and needs nothing from us.
 *
 * Why C rather than Scala. Scala Native's posixlib does not offer poll() on Windows: posixlib/poll.c
 * wraps its whole body in `#if defined(__unix__) || (defined(__APPLE__) && defined(__MACH__))`. So
 * merely *referencing* scala.scalanative.posix.poll makes any Windows binary that reaches
 * NativeSocket fail to link on `unresolved external symbol scalanative_pollin`. Scala Native has no
 * per-OS source directory, and dead-code elimination keeps every reachable branch, so a runtime
 * `if (isWindows)` in Scala would link both sides and fail just the same. The OS split has to happen
 * at C preprocessing time.
 *
 * Keeping the whole readable-wait contract here rather than splitting it across two languages also
 * sidesteps the struct: POSIX `struct pollfd.fd` is an `int`, while Windows `WSAPOLLFD.fd` is a
 * `SOCKET` (a 64-bit UINT_PTR on Win64), so one Scala binding could not describe both layouts.
 *
 * See adr/2026-07-08-native-socket-shim.md before changing any of this.
 */

#if defined(_WIN32)

#include <winsock2.h>

/* Embeds "ws2_32.lib" in this object's linker directives, so anything that links this object gets
 * winsock without passing a flag — Scala Native's own posixlib/sys/socket.c does exactly this. That
 * property is load-bearing: Scala Native compiles this file into *every* downstream binary, so a bare
 * WSAPoll reference with no guaranteed -lws2_32 would break the link of projects that never touch a
 * socket. That is the trap uni_curl_shim.c fell into (issue #622). A `#pragma comment(lib, ...)`
 * travels with the object file, where a Scala-level @link("ws2_32") is dropped by dead-code
 * elimination. MSVC-family linkers only; Scala Native's Windows support is MSVC-based. */
#pragma comment(lib, "ws2_32.lib")

/* winsock2.h already declares POLLIN / POLLERR / POLLHUP / POLLNVAL, and its `struct pollfd` is the
 * WSAPOLLFD that WSAPoll expects. WSAPoll only ever reports POLLRDNORM in revents, but POLLIN is
 * defined as (POLLRDNORM | POLLRDBAND), so testing POLLIN keeps working. */
typedef WSAPOLLFD uni_pollfd;

/* Scala Native stores a socket as CInt, having narrowed the SOCKET winsock handed back. Widening it
 * again is safe: Windows keeps socket handles inside the low 32 bits for exactly this reason (they
 * are kernel handles, documented as safely castable to int for interoperability). */
#define UNI_POLL_FD(fd) ((SOCKET)(UINT_PTR)(fd))
#define uni_poll(fds, n, timeout) WSAPoll((fds), (ULONG)(n), (INT)(timeout))

static INIT_ONCE uni_socket_startup_once = INIT_ONCE_STATIC_INIT;
static int       uni_socket_startup_rc   = -1;

static BOOL CALLBACK uni_socket_startup_cb(PINIT_ONCE once, PVOID parameter, PVOID *context) {
  (void)once;
  (void)parameter;
  (void)context;
  WSADATA data;
  uni_socket_startup_rc = WSAStartup(MAKEWORD(2, 2), &data) == 0 ? 0 : -1;
  return TRUE;
}

/*
 * Winsock refuses every socket() until WSAStartup has run in this process. Scala Native only calls it
 * from javalib's java.net implementations (WinSocketApiOps.init), which uni's posixlib-based sockets
 * never reach — so uni has to do it. No matching WSACleanup: the sockets live as long as the process.
 */
int uni_socket_startup(void) {
  InitOnceExecuteOnce(&uni_socket_startup_once, uni_socket_startup_cb, NULL, NULL);
  return uni_socket_startup_rc;
}

/* `close()` links on Windows (via oldnames.lib, aliased to the CRT's `_close`) but operates on CRT
 * file descriptors, not socket handles. Closing a socket with it leaks the socket. */
int uni_socket_close(int fd) { return closesocket(UNI_POLL_FD(fd)); }

#else

#include <errno.h>
#include <poll.h>
#include <unistd.h>

typedef struct pollfd uni_pollfd;

#define UNI_POLL_FD(fd) (fd)
#define uni_poll(fds, n, timeout) poll((fds), (nfds_t)(n), (timeout))

int uni_socket_startup(void) { return 0; }

int uni_socket_close(int fd) { return close(fd); }

#endif

/*
 * Wait until `fd` is readable or `timeout_millis` elapses (-1 blocks indefinitely).
 * Returns 1 if readable, 0 on timeout, -1 if the peer hung up or the poll errored.
 */
int uni_socket_wait_readable(int fd, int timeout_millis) {
  uni_pollfd fds;
  fds.fd      = UNI_POLL_FD(fd);
  fds.events  = POLLIN;
  fds.revents = 0;

  int rc = uni_poll(&fds, 1, timeout_millis);

#if !defined(_WIN32)
  /* Retry on EINTR (a signal) rather than mistaking it for a dead peer. Windows has no EINTR here.
   * As before, the retry restarts the timeout rather than deducting the time already elapsed. */
  while (rc < 0 && errno == EINTR) {
    fds.revents = 0;
    rc          = uni_poll(&fds, 1, timeout_millis);
  }
#endif

  if (rc < 0) {
    return -1;
  }
  if (rc == 0) {
    return 0;
  }
  /* Prefer draining pending data over reporting a hangup: a peer that sent a final request then
   * closed shows POLLIN (+ possibly POLLHUP); returning 1 lets recv read the data, then EOF. */
  if ((fds.revents & POLLIN) != 0) {
    return 1;
  }
  if ((fds.revents & (POLLHUP | POLLERR | POLLNVAL)) != 0) {
    return -1;
  }
  return 0;
}

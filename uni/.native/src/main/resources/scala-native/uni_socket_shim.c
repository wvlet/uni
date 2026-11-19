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
 * The places uni's Native sockets need something other than the plain POSIX call, so that
 * NativeHttpServer / NativeWebSocket work on Windows as well as unix:
 *
 *   startup        POSIX: nothing.            Windows: WSAStartup, or every socket() fails.
 *   readable-wait  POSIX: poll().             Windows: WSAPoll().
 *   close          POSIX: close().            Windows: closesocket().
 *   no SIGPIPE     macOS: SO_NOSIGPIPE.       Linux: MSG_NOSIGNAL.     Windows: nothing.
 *   idle poller    Linux: epoll.              macOS/BSD: kqueue.       Windows, others: none (-1).
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

/* InitOnceExecuteOnce needs Vista+. Modern SDKs already target well above that; this only fills in a
 * baseline for toolchains that leave _WIN32_WINNT unset (some MinGW header sets default lower). */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
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
 * are kernel handles, documented as safely castable to int for interoperability). Go through
 * `unsigned int` so a handle with bit 31 set zero-extends; a direct `int`->`UINT_PTR` cast would
 * sign-extend it to 0xFFFFFFFF........ and hand winsock an invalid SOCKET. */
#define UNI_POLL_FD(fd) ((SOCKET)(unsigned int)(fd))
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

/* Windows has no SIGPIPE: a send to a closed peer fails with WSAECONNRESET. */
void uni_socket_no_sigpipe(int fd) { (void)fd; }

int uni_socket_send_flags(void) { return 0; }

/* No MSG_DONTWAIT on winsock: report "can't tell" (-3), so the caller polls as before. */
int uni_socket_recv_ready(int fd, void *buf, int len) {
  (void)fd;
  (void)buf;
  (void)len;
  return -3;
}

#else

#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

typedef struct pollfd uni_pollfd;

#define UNI_POLL_FD(fd) (fd)
#define uni_poll(fds, n, timeout) poll((fds), (nfds_t)(n), (timeout))

int uni_socket_startup(void) { return 0; }

int uni_socket_close(int fd) { return close(fd); }

/*
 * A send to a peer that has closed raises SIGPIPE, whose default action kills the process. Turn it
 * into an EPIPE error instead, per socket rather than with a process-wide signal(SIGPIPE, SIG_IGN)
 * that would change how the app's own pipes (stdout into `head`) behave. macOS has no MSG_NOSIGNAL
 * but takes SO_NOSIGPIPE on the socket; Linux takes MSG_NOSIGNAL on each send.
 */
void uni_socket_no_sigpipe(int fd) {
#if defined(SO_NOSIGPIPE)
  int on = 1;
  setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, sizeof(on));
#else
  (void)fd;
#endif
}

int uni_socket_send_flags(void) {
#if defined(MSG_NOSIGNAL)
  return MSG_NOSIGNAL;
#else
  return 0;
#endif
}

/*
 * Read the bytes already waiting on `fd`, without blocking. Returns the count (> 0), 0 when the
 * peer has closed, -1 on an error, or -2 when nothing is waiting yet (the caller then waits with
 * uni_socket_wait_readable). A connection resumed from the poller, or one whose next request is
 * already in flight, has bytes waiting, so this saves the poll() before each recv().
 */
int uni_socket_recv_ready(int fd, void *buf, int len) {
  ssize_t n;
  do {
    n = recv(fd, buf, (size_t)len, MSG_DONTWAIT);
  } while (n < 0 && errno == EINTR);
  if (n >= 0) {
    return (int)n;
  }
  if (errno == EAGAIN || errno == EWOULDBLOCK) {
    return -2;
  }
  return -1;
}

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

/*
 * The idle-connection poller: NativeHttpServer parks keep-alive connections here between requests,
 * so an idle connection holds no worker thread (adr/2026-11-19-native-server-idle-poller.md).
 *
 *   uni_poller_create(wake)   a poller fd, plus a non-blocking wake pipe in wake[0] (read) and
 *                             wake[1] (write), registered under token 0. -1 where unsupported.
 *   uni_poller_add(p, fd, t)  watch fd for readability once (one-shot), reporting token t (> 0).
 *   uni_poller_wait(p, ts, n, ms)  wait up to ms (-1: no limit) and store up to n ready tokens in
 *                             ts. Returns the count, 0 on timeout or EINTR, -1 on error. Blocks.
 *   uni_poller_wake(w)        write a byte to the wake pipe, so a wait returns token 0.
 *   uni_poller_drain(r)       empty the wake pipe.
 *
 * Events carry a token, not the fd: a connection closed while parked frees its fd number, and a new
 * connection may reuse it. Only ints and 64-bit tokens cross into Scala, so no struct layout does.
 * Close the poller and pipe fds with uni_socket_close.
 */
#if defined(__linux__)
#include <sys/epoll.h>
#define UNI_POLLER_EPOLL 1
#elif defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__) || \
    defined(__DragonFly__)
#include <sys/types.h>
#include <sys/event.h>
#include <sys/time.h>
#define UNI_POLLER_KQUEUE 1
#endif

#define UNI_POLLER_MAX_EVENTS 256

#if defined(UNI_POLLER_EPOLL) || defined(UNI_POLLER_KQUEUE)
#include <fcntl.h>
#include <stdint.h>

static int uni_set_nonblocking_cloexec(int fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
    return -1;
  }
  return fcntl(fd, F_SETFD, FD_CLOEXEC);
}

/* Register fd for readability under token. one_shot: disarm after the first event. */
static int uni_poller_register(int p, int fd, long long token, int one_shot) {
#if defined(UNI_POLLER_EPOLL)
  struct epoll_event ev;
  ev.events   = EPOLLIN | EPOLLRDHUP | (one_shot ? EPOLLONESHOT : 0);
  ev.data.u64 = (uint64_t)token;
  return epoll_ctl(p, EPOLL_CTL_ADD, fd, &ev);
#else
  struct kevent ev;
  EV_SET(&ev, fd, EVFILT_READ, EV_ADD | EV_ENABLE | (one_shot ? EV_ONESHOT : 0), 0, 0,
         (void *)(intptr_t)token);
  return kevent(p, &ev, 1, NULL, 0, NULL) < 0 ? -1 : 0;
#endif
}

int uni_poller_create(int *wake) {
#if defined(UNI_POLLER_EPOLL)
  int p = epoll_create1(EPOLL_CLOEXEC);
#else
  int p = kqueue();
#endif
  if (p < 0) {
    return -1;
  }
  if (pipe(wake) != 0) {
    close(p);
    return -1;
  }
  if (uni_set_nonblocking_cloexec(wake[0]) < 0 || uni_set_nonblocking_cloexec(wake[1]) < 0 ||
      uni_poller_register(p, wake[0], 0, 0) < 0) {
    close(wake[0]);
    close(wake[1]);
    close(p);
    return -1;
  }
  return p;
}

int uni_poller_add(int p, int fd, long long token) {
#if defined(UNI_POLLER_EPOLL)
  /* After its first park, a connection's fd stays registered (disarmed) in the epoll set, so
   * re-arm it with one MOD call and fall back to ADD only for its first park. */
  struct epoll_event ev;
  ev.events   = EPOLLIN | EPOLLRDHUP | EPOLLONESHOT;
  ev.data.u64 = (uint64_t)token;
  if (epoll_ctl(p, EPOLL_CTL_MOD, fd, &ev) == 0) {
    return 0;
  }
  if (errno == ENOENT) {
    return epoll_ctl(p, EPOLL_CTL_ADD, fd, &ev);
  }
  return -1;
#else
  return uni_poller_register(p, fd, token, 1);
#endif
}

int uni_poller_wait(int p, long long *tokens, int max, int timeout_millis) {
  if (max > UNI_POLLER_MAX_EVENTS) {
    max = UNI_POLLER_MAX_EVENTS;
  }
#if defined(UNI_POLLER_EPOLL)
  struct epoll_event events[UNI_POLLER_MAX_EVENTS];
  int n = epoll_wait(p, events, max, timeout_millis);
#else
  struct kevent events[UNI_POLLER_MAX_EVENTS];
  struct timespec ts;
  struct timespec *tsp = NULL;
  if (timeout_millis >= 0) {
    ts.tv_sec  = timeout_millis / 1000;
    ts.tv_nsec = (long)(timeout_millis % 1000) * 1000000L;
    tsp        = &ts;
  }
  int n = kevent(p, NULL, 0, events, max, tsp);
#endif
  if (n < 0) {
    return errno == EINTR ? 0 : -1;
  }
  for (int i = 0; i < n; i++) {
#if defined(UNI_POLLER_EPOLL)
    tokens[i] = (long long)events[i].data.u64;
#else
    tokens[i] = (long long)(intptr_t)events[i].udata;
#endif
  }
  return n;
}

void uni_poller_wake(int w) {
  char b = 1;
  /* A full pipe already holds a pending wake-up, so a failed write needs no retry */
  ssize_t r = write(w, &b, 1);
  (void)r;
}

void uni_poller_drain(int r) {
  char buf[64];
  while (read(r, buf, sizeof(buf)) > 0) {
  }
}

#else

/* Windows and other systems: no poller, so the server keeps a worker per connection */
int uni_poller_create(int *wake) {
  (void)wake;
  return -1;
}
int uni_poller_add(int p, int fd, long long token) {
  (void)p;
  (void)fd;
  (void)token;
  return -1;
}
int uni_poller_wait(int p, long long *tokens, int max, int timeout_millis) {
  (void)p;
  (void)tokens;
  (void)max;
  (void)timeout_millis;
  return -1;
}
void uni_poller_wake(int w) { (void)w; }
void uni_poller_drain(int r) { (void)r; }

#endif

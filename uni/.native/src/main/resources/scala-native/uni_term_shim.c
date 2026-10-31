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
 * Terminal access for wvlet.uni.cli.TerminalIO on Scala Native: raw mode, window size, stdin reads,
 * and SIGINT delivery.
 *
 * Why C rather than posixlib. `struct termios` and its flag values differ between Linux and macOS,
 * and `cfmakeraw` and `TIOCGWINSZ` are not in Scala Native's posixlib. Keeping the struct on the C
 * side lets one Scala binding serve every unix. Scala Native compiles this file into every
 * downstream binary, so it uses libc only and needs no linker flag (see
 * adr/2026-07-06-curl-shim-weak-linking.md for that trap). On Windows the functions are stubs, and
 * TerminalIO reads input line by line.
 *
 * SIGINT. A signal handler must not run Scala code (it may allocate or hit a GC safepoint), so the
 * handler only writes a byte to a pipe (write() is async-signal-safe). A Scala thread blocks in
 * uni_term_sigint_wait() and runs the registered callbacks.
 */

#if defined(_WIN32)

#include <io.h>

int uni_term_isatty(int fd) { (void)fd; return 0; }
int uni_term_enter_raw(int fd) { (void)fd; return -1; }
int uni_term_restore(int fd) { (void)fd; return -1; }
int uni_term_size(int fd, int *cols, int *rows) { (void)fd; (void)cols; (void)rows; return -1; }
int uni_term_read(int fd, void *buf, int len) { return _read(fd, buf, (unsigned int)len); }
int uni_term_sigint_install(void) { return -1; }
int uni_term_sigint_reset(void) { return -1; }
int uni_term_sigint_wait(void) { return -1; }

#else

/* cfmakeraw is a BSD/GNU extension, hidden by glibc and macOS headers under a strict -std */
#define _GNU_SOURCE
#define _DARWIN_C_SOURCE
#include <errno.h>
#include <signal.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static struct termios saved_mode;
static int saved_valid = 0;

int uni_term_isatty(int fd) { return isatty(fd); }

int uni_term_enter_raw(int fd) {
  struct termios raw;
  if (tcgetattr(fd, &saved_mode) != 0) return -1;
  saved_valid = 1;
  raw = saved_mode;
  cfmakeraw(&raw);
  return tcsetattr(fd, TCSANOW, &raw);
}

int uni_term_restore(int fd) {
  if (!saved_valid) return 0;
  return tcsetattr(fd, TCSANOW, &saved_mode);
}

int uni_term_size(int fd, int *cols, int *rows) {
  struct winsize ws;
  if (ioctl(fd, TIOCGWINSZ, &ws) != 0 || ws.ws_col == 0 || ws.ws_row == 0) return -1;
  *cols = ws.ws_col;
  *rows = ws.ws_row;
  return 0;
}

int uni_term_read(int fd, void *buf, int len) {
  ssize_t n;
  do {
    n = read(fd, buf, (size_t)len);
  } while (n < 0 && errno == EINTR);
  return (int)n;
}

static int sigint_pipe[2] = {-1, -1};
static struct sigaction previous_sigint;
static int sigint_installed = 0;

static void on_sigint(int sig) {
  char c = 1;
  int saved_errno = errno;
  (void)sig;
  if (write(sigint_pipe[1], &c, 1) < 0) {
    /* Nothing to do: the pipe is full, so a wake-up is already pending */
  }
  errno = saved_errno;
}

int uni_term_sigint_install(void) {
  struct sigaction sa;
  if (sigint_installed) return 0;
  if (sigint_pipe[0] < 0 && pipe(sigint_pipe) != 0) return -1;
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = on_sigint;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = SA_RESTART;
  if (sigaction(SIGINT, &sa, &previous_sigint) != 0) return -1;
  sigint_installed = 1;
  return 0;
}

int uni_term_sigint_reset(void) {
  if (!sigint_installed) return 0;
  sigint_installed = 0;
  return sigaction(SIGINT, &previous_sigint, NULL);
}

int uni_term_sigint_wait(void) {
  char c;
  ssize_t n;
  if (sigint_pipe[0] < 0) return -1;
  do {
    n = read(sigint_pipe[0], &c, 1);
  } while (n < 0 && errno == EINTR);
  return n == 1 ? 0 : -1;
}

#endif

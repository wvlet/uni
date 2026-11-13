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
 * Pseudo-terminals for wvlet.uni.cli.term.PosixPty on Scala Native.
 *
 * Why posix_spawn rather than forkpty. forkpty lives in libutil on glibc before 2.34, and Scala
 * Native compiles this file into every downstream binary, so it would add a linker flag to all of
 * them (see adr/2026-07-06-curl-shim-weak-linking.md). fork() in the multithreaded Scala Native
 * runtime would also leave the child running in a copy of every thread's state. posix_spawn with
 * POSIX_SPAWN_SETSID starts the child in a new session; the file action that opens the slave then
 * makes it the controlling terminal (Linux and macOS both do so when a session leader without one
 * opens a terminal). libc only. On Windows the functions are stubs, and PosixPty reports that PTYs
 * are unsupported.
 */

#if defined(_WIN32)

int uni_pty_supported(void) { return 0; }
int uni_pty_spawn(const char *file, char *const argv[], char *const envp[], const char *cwd,
                  int cols, int rows, int *master_fd) {
  (void)file; (void)argv; (void)envp; (void)cwd; (void)cols; (void)rows; (void)master_fd;
  return -1;
}
int uni_pty_read(int fd, void *buf, int len, int timeout_ms) {
  (void)fd; (void)buf; (void)len; (void)timeout_ms;
  return -1;
}
int uni_pty_write(int fd, const void *buf, int len) { (void)fd; (void)buf; (void)len; return -1; }
int uni_pty_resize(int fd, int cols, int rows) { (void)fd; (void)cols; (void)rows; return -1; }
int uni_pty_wait(int pid) { (void)pid; return -1; }
int uni_pty_kill(int pid, int sig) { (void)pid; (void)sig; return -1; }
int uni_pty_close(int fd) { (void)fd; return -1; }
const char *uni_pty_strerror(int err) { (void)err; return "PTYs are not supported on Windows"; }

#else

#define _GNU_SOURCE
#define _DARWIN_C_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

int uni_pty_supported(void) { return 1; }

/* Returns the child's pid and stores the master fd, or returns -errno */
int uni_pty_spawn(const char *file, char *const argv[], char *const envp[], const char *cwd,
                  int cols, int rows, int *master_fd) {
  char slave[128];
  struct winsize ws;
  posix_spawnattr_t attr;
  posix_spawn_file_actions_t actions;
  sigset_t none, all;
  pid_t pid;
  int err, slave_fd;
  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) return -errno;
  /* Programs started by other threads must not inherit the master, or the PTY never closes */
  fcntl(master, F_SETFD, FD_CLOEXEC);
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    err = errno;
    close(master);
    return -err;
  }
#if defined(__APPLE__)
  /* ptsname() is not thread-safe, and macOS added ptsname_r only in 13.4 */
  if (ioctl(master, TIOCPTYGNAME, slave) != 0) {
#else
  if (ptsname_r(master, slave, sizeof(slave)) != 0) {
#endif
    err = errno;
    close(master);
    return -err;
  }
  /* Set the size through the slave, held open until the child has its own: macOS resets a size
   * set on the master when the slave is first opened */
  slave_fd = open(slave, O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (slave_fd < 0) {
    err = errno;
    close(master);
    return -err;
  }
  memset(&ws, 0, sizeof(ws));
  ws.ws_col = (unsigned short)cols;
  ws.ws_row = (unsigned short)rows;
  ioctl(slave_fd, TIOCSWINSZ, &ws);

  posix_spawnattr_init(&attr);
  sigemptyset(&none);
  sigfillset(&all);
  posix_spawnattr_setsigmask(&attr, &none);
  posix_spawnattr_setsigdefault(&attr, &all);
  posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSID | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);

  posix_spawn_file_actions_init(&actions);
  posix_spawn_file_actions_addopen(&actions, 0, slave, O_RDWR, 0);
  posix_spawn_file_actions_adddup2(&actions, 0, 1);
  posix_spawn_file_actions_adddup2(&actions, 0, 2);
  if (cwd != NULL) {
#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
#endif
    /* glibc 2.29+, musl 1.1.24+, and macOS 10.15+. macOS 26 renamed it to the POSIX 2024 name
     * posix_spawn_file_actions_addchdir, but keeps this one. */
    posix_spawn_file_actions_addchdir_np(&actions, cwd);
#if defined(__clang__)
#pragma clang diagnostic pop
#endif
  }

  err = posix_spawnp(&pid, file, &actions, &attr, argv, envp);
  posix_spawn_file_actions_destroy(&actions);
  posix_spawnattr_destroy(&attr);
  close(slave_fd);
  if (err != 0) {
    close(master);
    return -err;
  }
  *master_fd = master;
  return (int)pid;
}

/* Returns the number of bytes read, 0 on timeout, or -1 when the PTY is closed */
int uni_pty_read(int fd, void *buf, int len, int timeout_ms) {
  struct pollfd p;
  ssize_t n;
  int r;
  p.fd = fd;
  p.events = POLLIN;
  p.revents = 0;
  r = poll(&p, 1, timeout_ms);
  if (r == 0 || (r < 0 && errno == EINTR)) return 0;
  if (r < 0) return -1;
  do {
    n = read(fd, buf, (size_t)len);
  } while (n < 0 && errno == EINTR);
  /* Linux reports EIO once every slave fd is closed; macOS reports end of file */
  if (n <= 0) return n < 0 && errno == EAGAIN ? 0 : -1;
  return (int)n;
}

/* Returns 0 once all bytes are written, or -1 */
int uni_pty_write(int fd, const void *buf, int len) {
  const char *p = (const char *)buf;
  while (len > 0) {
    ssize_t n = write(fd, p, (size_t)len);
    if (n < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    p += n;
    len -= (int)n;
  }
  return 0;
}

int uni_pty_resize(int fd, int cols, int rows) {
  struct winsize ws;
  memset(&ws, 0, sizeof(ws));
  ws.ws_col = (unsigned short)cols;
  ws.ws_row = (unsigned short)rows;
  return ioctl(fd, TIOCSWINSZ, &ws);
}

/* Returns the raw waitpid status, or -1 */
int uni_pty_wait(int pid) {
  int status = 0;
  pid_t r;
  do {
    r = waitpid((pid_t)pid, &status, 0);
  } while (r < 0 && errno == EINTR);
  return r < 0 ? -1 : status;
}

int uni_pty_kill(int pid, int sig) { return kill((pid_t)pid, sig); }

int uni_pty_close(int fd) { return close(fd); }

const char *uni_pty_strerror(int err) { return strerror(err); }

#endif

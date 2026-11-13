# A JVM and Scala Native PTY backend for `cli.term`

## Goals

- Run a program in a real pseudo-terminal on the JVM and Scala Native, behind the existing `PtyProcess` interface, so a JVM or Native app can drive `TerminalEmulator` the way Electron apps drive it through `NodePty` today.
- Keep uni dependency-free: no JNA, pty4j, or `-lutil` link flag.

## Background

- #856 shipped `PtyProcess` (write, resize, onData, onExit, kill) with only a Scala.js backend, `NodePty`, which wraps node-pty. `PtyOptions` lives in the `.js` sources.
- uni already calls libc through the Foreign Function & Memory API on Java 22+ (`PosixTerminal`, for raw mode) and through a small libc-only C shim on Native (`uni_term_shim.c`). Both fall back cleanly on older JVMs and Windows.
- `forkpty` lives in `libutil` on older glibc, which would add a linker flag to every downstream Native binary (see the curl shim ADR). On the JVM, `fork` is unsafe because the child would run Java code in a copy of a multithreaded process.
- Checked on macOS 26: `posix_spawn` with `POSIX_SPAWN_SETSID`, plus a file action that opens the PTY slave as fd 0, gives the child a controlling terminal. `tty`, `stty size`, the foreground process group, and SIGWINCH on resize all behave as in a real terminal. Linux acquires the controlling terminal the same way on open.

## Design

Lane: Standard. This adds a public API on two platforms.

- **Entry point** `PosixPty.spawn(file, args, options): PtyProcess` on the JVM and Native, with the same signature as `NodePty.spawn`. `PtyOptions` moves to the shared sources unchanged, so JS behavior stays the same.
- **One mechanism on both platforms:** `posix_openpt` → `grantpt`/`unlockpt` → set the size with `TIOCSWINSZ` on the master → `posix_spawnp` with SETSID, reset signal mask and dispositions, slave on fds 0–2, master closed, optional `chdir`. JVM code calls libc through FFM; Native code calls a libc-only `uni_pty_shim.c`.
- **Supported:** Linux (glibc 2.29+) and macOS 10.15+. `PosixPty.isSupported` reports this. On Windows, and on JVMs older than 22, `spawn` throws `UnsupportedOperationException` with the reason. It never falls back to plain pipes.
- **Output:** a daemon thread polls and reads the master, and decodes UTF-8 incrementally so a character split across reads arrives whole. Output that arrives before the first `onData` listener registers is buffered, not dropped.
- **Exit:** a waiter thread reaps the child, lets the reader drain the remaining output (bounded wait), closes the master, and then fires `onExit`. A signal death reports `128 + signal`, as shells do. Registering `onExit` after the exit fires the listener immediately.
- **kill():** sends SIGHUP, as node-pty does and as a closing terminal would. It is idempotent. After exit, `kill()`, `write()`, and `resize()` do nothing.
- **Tests:** shared tests that run on the JVM and Native against real PTYs with bounded waits:
  - the child sees a TTY with the initial size;
  - resize reaches it via SIGWINCH;
  - input echoes back, and split UTF-8 decodes correctly;
  - exit codes 0, nonzero, and signal;
  - kill and repeated kill work.
  
  On unsupported platforms (Windows Native CI), the tests `cancel` with the reason instead of passing.
- An ADR entry records the posix_spawn choice. `for-ai-agents.md` and the `uni` skill gain a short `cli.term` PTY entry.

## Alternatives and Why Not?

- **`forkpty` on Native:** needs `-lutil` on glibc before 2.34. A fork in the multithreaded Scala Native runtime is also riskier than posix_spawn.
- **pty4j / JNA on the JVM:** adds dependencies.
- **`script(1)` or a helper binary:** no resize control, and the behavior differs between platforms.
- **ProcessBuilder redirected to the slave path:** the child gets no controlling terminal, so SIGWINCH and Ctrl-C would never reach it.

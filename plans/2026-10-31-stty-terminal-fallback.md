# stty fallback for JVM TerminalIO (JDK 17–21)

## Goals

- Give `LineReader` full line editing (raw mode, history keys, Ctrl-R, completion, window size) on non-Windows JVMs older than 22.
- Unblock wvlet's move from JLine to `LineReader`: wvlet supports JDK 17, and line mode there would regress its `wv` REPL.

## Background

- `JvmTerminalIO` uses `PosixTerminal` (FFM, libc termios) only on Java 22+ and not on Windows; otherwise `isTTY` is false and input is read line by line.
- The ADR `2026-10-31-cross-platform-line-reader.md` explicitly rejected an `stty` subprocess fallback ("Java 22+ is acceptable"). wvlet's JDK 17 support changes that premise, so this PR amends the ADR.
- `LineEditor` reads `io.size.columns` on every redraw, so a per-call `stty size` subprocess would spawn a process per keystroke.

## Design

- **Lane: Standard** — reverses a recorded ADR decision and changes runtime behaviour on JDK 17–21.
- Add a small private seam, `NativeTerminal` (`isTTY`, `enterRawMode`, `restore`, `windowSize`), with two implementations: the existing FFM `PosixTerminal` and a new `SttyTerminal`.
- Selection: Windows → none (line mode); Java 22+ → FFM, falling back to stty if FFM fails to load; Java 17–21 → stty. Kept as a pure function of (java version, isWindows) so it is unit-testable.
- TTY detection (stty path): `System.console() != null` (true only when stdin and stdout are both terminals before Java 22) and `/dev/tty` is readable.
- Raw mode: `stty -g` saves settings, `stty raw -echo` enters raw mode, `stty <saved>` restores; each runs through `ProcessBuilder` with stdin redirected from `/dev/tty`. `raw` clears ISIG like `cfmakeraw`, so Ctrl-C arrives as a byte on both paths.
- Failure safety: a missing `stty` or a non-zero exit makes `enterRawMode` return false, so `LineReader` stays in today's line mode. The existing shutdown hook restores the terminal for both implementations.
- Size: `stty size` parsed as `rows cols`, cached, and refreshed on SIGWINCH via `sun.misc.Signal("WINCH")`. The cache also refreshes on each `enterRawMode`, so a resize between prompts is picked up even if WINCH registration fails.
- Tests cover the selection logic and the `stty size` parser; CI has no TTY. Docs: update the platform table and the "older than 22" sentence in `docs/cli/line-reader.md`, and amend the ADR's JVM bullet and Rejected list.

## Alternatives and Why Not?

- Keep line mode on JDK 17–21: regresses wvlet's REPL on a supported JDK.
- JNA/JNI for termios: adds a dependency or native build, against uni's minimal-dependency rule.
- Require Java 22 in wvlet: a user-facing breaking change, outside this repo's control.

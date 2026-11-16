# Short stack traces in UniTest console output

## Goals

- A test that throws an unexpected exception (`x name: ...`) should print a few relevant frames, not 50+ lines of JDK, reflection, and sbt frames that push the next test's output off screen.
- The full trace stays one click away in the terminal, and fully visible where clicking is impossible (CI logs).

## Background

- Today `UniTestTask` hands the throwable to sbt's `Logger.trace`, which prints every frame of every cause. Assertion failures already print only a source snippet, so this is about errors (`TestResult.Error`) and suite-level errors.
- Terminals cannot fold text, but most modern ones (iTerm2, WezTerm, kitty, VS Code, JetBrains, GNOME Terminal, Windows Terminal) open OSC 8 hyperlinks on click. Terminals without OSC 8 show the link text as plain text.
- The JUnit Platform engine (IntelliJ runs) already folds traces in the IDE, so it stays as is.

## Design

Lane: Standard — new console behavior for the logger and uni-test.

- **One rule: shorten only for an interactive terminal.** Output to a terminal gets the short trace; output to a pipe, file, log collector, or CI log keeps the full trace exactly as today.
- **Short trace:** the exception line, then up to 5 frames with framework frames removed (`sbt.`, `wvlet.uni.test.`, `java.lang.reflect`, `jdk.internal.`, `scala.concurrent.`), one line per `Caused by:` with 2 frames, then `... 42 more lines: <path>`.
- **Click for the full trace:** the full trace goes to a temp file (`target/uni-test/stacktraces/<Suite>/<test>.txt` in tests), and `<path>` is an OSC 8 `file://` link whose text is the path, so it is clickable or copyable.
- **Shared code in uni-core:** the logger's console handler and uni-test use the same formatter, so `warn("...", e)` inside a test is shortened the same way as a test error.
- **Terminal detection:** uni-test uses sbt's `Logger.ansiCodesSupported()`, since sbt (not the test JVM) owns the terminal. The logger checks stderr is a TTY (JVM `System.console`, Node `isTTY`, Native `isatty`). `CI` set always means full.
- **No new test options:** the click covers the terminal, and CI or redirected output already gets the full trace. Apps can force a style with `ShortStackTrace.setForConsole`.
- **Shorten only when the full trace was saved:** Scala.js, which has no files, prints the full trace.
- Tests cover frame filtering and truncation, cause chains, the file write, and the terminal/CI switch.

## Alternatives and Why Not?

- **Shorten everywhere by default.** Production logs in files or collectors would lose frames, and a link to a file on a server is useless.
- **uni-test only.** Leaves exceptions logged inside tests at full length, which are often the noisier part.
- **Rely on sbt's `traceLevel`.** A global sbt setting most users don't know, and it gives no way to reach the full trace afterwards.

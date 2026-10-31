# Cross-platform terminal and line editor in uni.cli

## Goals

- Let wvlet (and other uni CLIs) build an interactive REPL once, in shared code, for JVM, Scala Native, and Scala.js (Node).
- Cover what wvlet's REPL uses from JLine today and nothing more: line editing, history, completion, syntax highlighting, multiline input, a few custom key bindings, Ctrl-C handling, `printAbove`, and terminal size.
- Keep uni's minimal-dependency rule: no JLine, JNA, or new native libraries.

## Background

- wvlet's REPL (`wvlet-cli`, JVM-only) uses JLine 4: `LineReader` with `Parser` (multiline), `Highlighter`, `Completer`, history file, `KeyMap` widgets (`^J^A`, `^J^R`, …), `Signal.INT`, and `InfoCmp` for clear-screen. Query results go to an external `less`.
- wvlet already isolates this behind its own `REPLTerminal` trait (JLine3 + Headless), so swapping the backend is local to wvlet.
- uni.cli already has output-side pieces shared across platforms: `Terminal` (ANSI cursor/clear), `CliEnv` (color level, size, isInteractive), `Tint`, `Spinner`, `ProgressBar`. Input side is missing entirely.
- The hard, platform-specific part is small: raw mode, reading bytes, size and resize, and Ctrl-C. Everything else (key decoding, the edit buffer, rendering) is pure Scala.
- Platform raw-mode options: Native has `termios` in scala-native's posixlib; Node has `process.stdin.setRawMode`; the JVM has no API, only FFM (`tcsetattr`, final in Java 22) or running `stty`.

## Design

Lane: **Standard** — new public API surface in `uni`, and the choice of option needs your call.

Three layers, each usable on its own:

- **1. `TerminalIO` (platform layer)** — enter/leave raw mode, read input bytes, size plus resize events, interrupt hook, and restore on exit or crash.
  - Native: posix `termios` + `ioctl(TIOCGWINSZ)`; Windows console mode through the existing C-shim pattern (later PR).
  - JS (Node): `setRawMode` + `stdin` data events; input arrives asynchronously, so the API is `Rx`-based on every platform.
  - JVM: FFM (`tcgetattr`/`cfmakeraw`/`tcsetattr`, `ioctl(TIOCGWINSZ)`) on Java 22+ only; older JVMs, Windows, and non-TTY input fall back to plain line mode (as JLine's dumb terminal does).
- **2. `KeyDecoder` (shared)** — turns bytes into `Key` events (chars, arrows, Home/End, Ctrl/Alt, bracketed paste, CSI/SS3 variants). Pure, fully unit-testable.
- **3. `LineReader` (shared)** — a small Emacs-style editor written against layers 1–2.
  - Plug-in points, kept as plain Scala functions: `completer: (buffer, cursor) => Seq[Candidate]`, `highlighter: String => String` (returns `Tint`-styled text), `isComplete: String => Boolean` for multiline continuation, and a `KeyMap` of named actions that users can rebind, including multi-key chords like `^J^R`.
  - Built in: history (file-backed on JVM/Native, in-memory on JS), Ctrl-R reverse search, `printAbove`, and a secondary prompt.
  - Rendering redraws only the edited region with the existing `Terminal` escapes, so resize and wide characters (CJK) are handled in one place.
- **Out of scope**: vi mode, an `stty` fallback for Java < 22, a built-in pager (wvlet keeps `less`), Windows input, and terminfo; uni assumes ANSI/VT100, as `Terminal` already does.
- **Rollout**: one PR adds all three layers on JVM, Native, and Node, plus an `examples/repl` demo. wvlet then adds a `UniTerminal` beside `JLine3Terminal` and drops JLine once it reaches parity.
- Tests cover the key decoder and the edit buffer purely (scripted key sequences → buffer/screen state), with no TTY needed in CI.

## Alternatives and Why Not?

- **Keep JLine on JVM, write something else for Native/JS** — two REPL code paths in wvlet, which is the problem we want to remove.
- **Port JLine to Native/JS** — tens of thousands of lines, terminfo, and many features wvlet does not use.
- **Bundle linenoise/replxx through a C shim** — Native only, adds C code to maintain, and JS still needs a separate path.
- **Node `readline` on JS** — fine for JS alone, but its completion and highlighting model differs, so the shared `LineReader` would split.
- **Output-only helpers (no raw mode)** — cheap, but gives no editing, history, or completion, so wvlet would still need JLine.

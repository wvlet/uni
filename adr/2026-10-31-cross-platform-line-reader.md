# Cross-platform line editing: `TerminalIO` + shared `LineReader`

## Context

wvlet's REPL depends on JLine3, which runs only on the JVM and carries far more than a CLI needs (terminfo, widgets, pagers, many terminal back-ends). That kept the REPL from running as a Scala Native binary or on Node.js. Of JLine, the REPL uses only raw-mode line editing, history, completion, highlighting, multi-line input, a few chord key bindings, Ctrl-C handling, `printAbove`, and the terminal size.

## Decision

- Split the stack into three layers in `wvlet.uni.cli`. `TerminalIO` is the only per-platform part: raw mode, input bytes, window size, and SIGINT. `KeyDecoder` (bytes to `Key`) and `LineReader`/`LineEditor` (editing, history, completion, rendering) are shared pure Scala.
- **JVM**: libc through the Foreign Function & Memory API, Java 22+ only, with no `stty` fallback. Raw mode uses `tcgetattr` → `cfmakeraw` → `tcsetattr` on an opaque 256-byte buffer, so uni never reads `struct termios` fields, whose layout and flag values differ between Linux and macOS. The FFM code lives in its own class (`PosixTerminal`) and is reached only after a `Runtime.version().feature() >= 22` check, so older JVMs never load it and fall back to line mode.
- **Scala Native**: a C shim (`uni_term_shim.c`) for the same reasons: the struct layout, and `cfmakeraw`/`TIOCGWINSZ` being absent from posixlib. It uses libc only and stubs every function on Windows. That follows the link-safety lesson of `2026-07-06-curl-shim-weak-linking.md`: the shim is compiled into every downstream binary.
- **SIGINT on Native**: the signal handler only writes a byte to a non-blocking, close-on-exec self-pipe, and a daemon thread blocked in `uni_term_sigint_wait` (`@blocking`) runs the Scala callbacks. Scala code never runs inside a signal handler.
- **Node.js**: `process.stdin.setRawMode` and `data`/`end` events. `TerminalIO.readInput` returns `Rx[Unit]` on every platform: it blocks on the JVM and Native, and completes asynchronously on Node. `LineReader.readLine` returns `Rx[LineResult]`, an enum of `Line`, `Interrupted`, and `EndOfInput`, instead of JLine-style exceptions.
- **Rendering**: each redraw moves up to the prompt's first row, clears to the end of the screen, and redraws everything. Positions come from walking the code points the way a terminal wraps them, where a wide character that does not fit at the edge moves to the next row and a row filled exactly holds a pending wrap. This keeps multi-line input, wrapping, CJK text, and ANSI-colored highlighting correct in one place.
- **ESC**: a chunk that ends in a lone ESC is the Esc key, since terminals send each key sequence in one write. ESC followed by a key in the same chunk is Alt + that key, with no timer.
- **History**: one escaped entry per line, appended to the file, which is rewritten only after it doubles past `historySize`. Lines in JLine's `timestamp:entry` format are read too, so existing JLine history files keep working.

Rejected: keeping JLine on the JVM with a separate path for Native and JS (two REPL code paths), porting JLine (size, terminfo), a bundled linenoise/replxx through C (Native only, and more C to maintain), Node's `readline` (a different completion and highlighting model), and an `stty` subprocess fallback (process spawn, Unix only; Java 22+ is acceptable).

## Consequences

- Java 24+ prints a native-access warning unless the JVM runs with `--enable-native-access=ALL-UNNAMED`. Applications should add that flag to their launcher.
- Windows has no raw input yet: `isTTY` is false, so input is read line by line. Adding it means Windows console-mode calls in the shim's `_WIN32` branch and in a JVM path.
- uni assumes ANSI/VT100 output, as `Terminal` already does. There is no terminfo.
- Code built on `LineReader` can be tested without a TTY through `TerminalIO.inMemory`. Rendering tests assert on exact escape sequences, so they change whenever the redraw strategy changes.

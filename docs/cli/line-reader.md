# Line Editing

`LineReader` reads user input with line editing for REPLs and interactive command-line programs. The
same code runs on the JVM, as a Scala Native binary, and on Node.js.

It provides:

- Emacs-style editing (Ctrl-A/E, Ctrl-K/U/W/Y, Alt-B/F, arrows, Home/End)
- History with Up/Down, Ctrl-P/N, and reverse search (Ctrl-R), saved to a file
- Tab completion
- Syntax highlighting with [Tint](./tint)
- Multi-line input, e.g., until a statement ends with `;`
- Custom key bindings, including chords such as Ctrl-J Ctrl-R
- Bracketed paste, so pasted multi-line text stays in one input
- Wide characters (CJK, emoji) and long lines that wrap

## Basic Usage

```scala
import wvlet.uni.cli.*

val reader = LineReader(
  LineReaderConfig()
    .withHistoryFile(".my_history")
    .withCompleter(Completer.words(Seq("select", "from", "where")))
    .withHighlighter(_.replace("select", Tint.cyan("select")))
    .withInputComplete(_.trim.endsWith(";"))
    .withContinuationPrompt("  | ")
)

// The same loop on every platform
def loop(reader: LineReader): Rx[Unit] = reader
  .readLine("> ")
  .flatMap {
    case LineResult.Line("exit") | LineResult.EndOfInput =>
      Rx.single(())
    case LineResult.Line(text) =>
      println(s"input: ${text}")
      loop(reader)
    case LineResult.Interrupted(_) =>
      loop(reader)
  }

loop(reader).run()
```

`readLine` returns an `Rx[LineResult]` that completes when the user presses Enter (`Line`), Ctrl-C
(`Interrupted`), or Ctrl-D on an empty line (`EndOfInput`). Chaining reads with `flatMap` keeps one
code path for the JVM, Scala Native, and Node.js, where nothing can block. Code that never runs on
Scala.js can also block with `reader.readLine("> ").await`.

`LineReader` uses `TerminalIO.default`, the terminal of the platform it runs on, so the code above
has no platform-specific parts.

## Wiring with Design

Pass the terminal to `LineReader` through [Design](../core/design) to swap it in tests. The
application binds `TerminalIO.default`, and a test overrides it with `TerminalIO.inMemory`, which
replays keystrokes and records the output:

```scala
import wvlet.uni.design.Design

class Repl(reader: LineReader):
  def run(): Rx[Unit] = loop(reader)

val design = Design
  .newDesign
  .bindInstance[LineReaderConfig](LineReaderConfig().withHistoryFile(".my_history"))
  .bindInstance[TerminalIO](TerminalIO.default)
  .bindProvider { (config: LineReaderConfig, io: TerminalIO) =>
    LineReader(config, io)
  }

// Application
design.build[Repl](_.run().run())

// Test: type "hello", then "exit"
val io = TerminalIO.inMemory(Seq("hello\r".getBytes, "exit\r".getBytes))
(design + Design.newDesign.bindInstance[TerminalIO](io))
  .run[Repl, Rx[Unit]](_.run())
```

The test runs the same way on the JVM, Scala Native, and Scala.js.

## Completion

A `Completer` returns the candidates that replace part of the input before the cursor.
`Completer.word` completes the word before the cursor; a one-element result is inserted with a
trailing space, and several are listed above the prompt after their common prefix is inserted.

```scala
val files = Completer.word(delimiters = "'\"") { prefix =>
  listFiles(prefix).map { f =>
    // Keep typing into directories: no trailing space
    Candidate(f.path, complete = !f.isDirectory)
  }
}
```

## Key Bindings

`KeyMap.emacs` is the default. Bind a key sequence, written in `^X` notation, to a built-in
`LineAction` or to custom code with `LineAction.Custom`. The custom code receives the
`LineEditor`, which can read and change the input, print above it, or accept it.

```scala
val runStatement = LineAction.Custom(
  "run-statement",
  editor =>
    editor.moveCursor(editor.buffer.length)
    editor.insert(";")
    editor.accept()
)

val config = LineReaderConfig()
  .withKeyMap(KeyMap.emacs.unbind("^J")) // Ctrl-J accepts by default; free it for chords
  .bind("^J^R", runStatement)
```

While the keys typed so far start a longer binding, the editor waits for the next key.

## Output While Reading

`reader.printAbove(message)` prints a message above the prompt and draws the input again below it,
for example, from a background task. To stop a running task on Ctrl-C without exiting, register a
handler on the terminal:

```scala
val cancel = reader.terminal.onInterrupt(() => task.cancel())
try task.await
finally cancel.cancel
```

## Terminal Access

`TerminalIO` is the layer `LineReader` is built on, and can be used directly for key-driven UIs:
`enterRawMode()`/`exitRawMode()`, `readInput`, `size`, `write`, and `onInterrupt`. `KeyDecoder`
turns input bytes into `Key` events.

| Platform | Raw mode and window size |
|----------|--------------------------|
| JVM | libc `termios` through the Foreign Function & Memory API; needs Java 22 or later |
| Scala Native | libc `termios` through a small C shim bundled with uni |
| Scala.js | Node.js `process.stdin.setRawMode`; not available in browsers |

When stdin or stdout is not a terminal (a pipe, a file, CI), on Windows, or on a JVM older than 22,
input is read line by line without editing, and nothing is drawn.

On Java 24 and later, run the JVM with `--enable-native-access=ALL-UNNAMED` to turn off the warning
the JVM prints on the first native call.

`TerminalIO.inMemory(...)` replays input and records output, for testing code built on
`LineReader` without a terminal. See [Wiring with Design](#wiring-with-design).

## Example

[`examples/repl`](https://github.com/wvlet/uni/tree/main/examples/repl) is a small SQL-like REPL
with completion, highlighting, multi-line statements, and chord bindings.

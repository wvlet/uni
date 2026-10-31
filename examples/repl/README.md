# REPL: line editing on the JVM, Node.js, and Scala Native

A small SQL-like REPL built on `LineReader`. The same source runs on the JVM, on Node.js, and as a
Scala Native binary.

```text
Statements end with ';'. Enter on an unfinished statement starts a new line.
uni> select 1
  │   from t;
ran: select 1   from t;
```

## What it shows

- **Editing**: Emacs-style keys, history saved to `target/.uni-repl-history`, and Ctrl-R search.
- **Completion**: `Completer.words(...)` completes keywords on Tab.
- **Highlighting**: keywords colored with `Tint.cyan`.
- **Multi-line input**: `withInputComplete` keeps reading until the statement ends with `;`.
- **Chords**: Ctrl-J Ctrl-R runs the statement through a `LineAction.Custom`; Ctrl-J Ctrl-D prints
  a description above the prompt with `printAbove`.
- **One loop for every platform**: `readLine` returns an `Rx`, and the loop chains reads with
  `flatMap`, so it also works on Node.js, where nothing can block.
- **No platform-specific code**: `MiniRepl.design` wires the `LineReader` to `TerminalIO.default`,
  the terminal of whichever platform runs it. The test,
  [`MiniReplTest.scala`](src/test/scala/example/repl/MiniReplTest.scala), overrides it with
  `TerminalIO.inMemory` and runs on all three platforms.

Source: [`MiniRepl.scala`](src/main/scala/example/repl/MiniRepl.scala).

## Run

```bash
./sbt exampleReplJVM/test exampleReplJS/test exampleReplNative/test   # the test, on each platform
```

Line editing needs a real terminal, which `sbt run` does not provide, so build first and run the
result from your shell.

```bash
# Scala Native
./sbt exampleReplNative/nativeLink
./target/out/native0.5/scala-3.9.0/uni-example-repl/uni-example-repl

# Node.js
./sbt exampleReplJS/fastLinkJS
node target/out/sjs1/scala-3.9.0/uni-example-repl/uni-example-repl-fastopt/main.js

# JVM (Java 22 or later)
./sbt "export exampleReplJVM/Runtime/fullClasspath"   # prints the classpath
java --enable-native-access=ALL-UNNAMED -cp <classpath> example.repl.MiniRepl
```

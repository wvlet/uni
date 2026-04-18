# 1.2 Hello, Uni!

With Uni on the classpath, the shortest useful program you can write is
one that logs a line. That seems small, but it introduces two of Uni's
core ideas — the **LogSupport trait** and **source-location capture** —
and sets up the next step, where we let **Design** wire objects together
for us.

## The smallest Uni program

Replace `src/main/scala/Main.scala` from the previous chapter with:

```scala
import wvlet.uni.log.LogSupport

object Main extends LogSupport:
  def main(args: Array[String]): Unit =
    info("Hello, Uni!")
```

Run it:

```bash
$ sbt run
2026-04-18 09:00:00.000-0700  info [Main] Hello, Uni!  - (Main.scala:5)
```

Four things are worth pausing on.

### `LogSupport` is a trait, not a wrapper

`LogSupport` mixes in two things: a `logger` bound to the enclosing
class name, and methods (`info`, `warn`, `error`, `debug`, `trace`) that
call it. You do not construct a `Logger`; you declare that this class
*has* one. That is the whole of the logging API for day-to-day code.

### The logger name is the class name

The output starts with `[Main]`. That is the simple name of the class
that mixed in `LogSupport`. When you search logs later, the grep is on a
string you can see right here in the source file.

### The source location is captured automatically

The `(Main.scala:5)` at the end of the line is free. Uni captures it at
compile time using Scala 3's `scala.quoted.Quotes` API, so there is no
stack-walking cost at runtime. When a production incident makes you ask
*"which line produced this?"*, the answer is already printed.

### Scala 3 syntax is the default

No `new`, no `class Main { ... }` braces, no `extends App`. The Scala 3
indentation style is what Uni uses in its own code and what this book
will use throughout. If you prefer braces, the compiler still accepts
them — but the examples here will stay in the indented style.

## Adding a collaborator: wire it by hand

A real program has more than one class. Let's add a `Greeter` and have
`Main` use it.

```scala
import wvlet.uni.log.LogSupport

class Greeter extends LogSupport:
  def greet(name: String): Unit =
    info(s"Hello, ${name}!")

object Main extends LogSupport:
  def main(args: Array[String]): Unit =
    val greeter = Greeter()
    greeter.greet("Uni")
```

Output:

```
info [Greeter] Hello, Uni!  - (Main.scala:5)
```

Two things changed. The logger name shifted to `[Greeter]` (because the
`info` call now happens inside the `Greeter` class), and `Main` now
constructs the `Greeter` explicitly with `Greeter()`.

In a three-line program, manual construction is fine. In a real
application it is not:

- Every caller needs to know how every callee is built.
- When a collaborator grows a dependency ("now `Greeter` needs a
  `Config`"), every caller changes.
- Swapping an implementation in a test means passing a different
  constructor argument in every call site.

Uni offers a better tool.

## Letting Design do the wiring

The same program, this time wired by `Design`:

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport

class Greeter extends LogSupport:
  def greet(name: String): Unit =
    info(s"Hello, ${name}!")

@main def main(): Unit =
  val design = Design.newDesign
    .bindSingleton[Greeter]

  design.withSession { session =>
    val greeter = session.build[Greeter]
    greeter.greet("Uni")
  }
```

Run it again — same output, but now something structural has changed.

- `Design.newDesign` gives you an empty, immutable wiring
  configuration.
- `.bindSingleton[Greeter]` says *"when someone asks for a `Greeter`,
  build one and share it."* No instance is created yet.
- `design.withSession { session => ... }` opens a **session**. Inside
  the block, `session.build[Greeter]` gets (or creates on first ask)
  the `Greeter`. When the block ends, the session is shut down and any
  resources attached to it are released.

You did not have to tell `Main` how `Greeter` is constructed. You did
not have to thread a `Greeter` through intermediate functions. You
asked for one, and you got one.

### Why this instead of `Greeter()`?

Fair question — this is exactly the point. With three lines of code,
`Greeter()` is less ceremony. What `Design` buys you shows up when:

- `Greeter` gains a dependency (a `Config`, an `HttpClient`). You only
  update the `Design`, not every call site.
- You want a different `Greeter` in tests (say, one that captures
  messages). You replace one line in a test-specific design.
- You want to tie *shutdown behavior* to *construction* (close the HTTP
  pool when the session ends). Design has a hook for that.

We will meet all three of those in Part III. For now, treat
`Design.newDesign.bindSingleton[X].withSession { ... }` as the shape
your `main` methods will have from here on.

## What you have, what comes next

You can now:

- Print log lines from a Scala 3 program with source locations.
- Construct one object, then three, using `Design` instead of by hand.
- Read a Uni-idiomatic `main` method and recognize its shape.

The [next chapter](./ch02-00-cli-app) takes these primitives and
builds something you would actually run: a small command-line tool
that fetches a URL and prints what it gets back. That single program
will introduce argument parsing, HTTP, and the first failure modes
Uni helps you handle.

[← 1.1 Installation](./ch01-01-installation) | [Next → 2. A URL Fetcher](./ch02-00-cli-app)

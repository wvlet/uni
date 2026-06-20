# 5. Logging That Finds You

A log line that reads `save failed` is almost useless if your codebase
has twenty `save` methods. The first question you ask is always the same:
*where did this come from?* This chapter is about a logger that answers
that for you, for free.

## Logging in one line

Mix in `LogSupport` and call a level method:

```scala
import wvlet.uni.log.LogSupport

class OrderService extends LogSupport:
  def placeOrder(id: String): Unit =
    info(s"Placing order ${id}")
    // ... do the work ...
```

```bash
$ sbt run
2024-01-15 10:30:45.123+0900  info [OrderService] Placing order A-100 - (OrderService.scala:5)
```

Look at the end of that line: `(OrderService.scala:5)`. The log tells
you the exact file and line that produced it. You did not pass it; you
did not configure it. That is the whole idea.

## Walking the code

**`LogSupport` is a mixin.** Extending it gives the class `trace`,
`debug`, `info`, `warn`, and `error` methods and a logger named after the
class (`[OrderService]`). There is no `private val logger =
LoggerFactory.getLogger(getClass)` line to copy-paste and get wrong.

**The five levels are ordered.** From quietest to loudest: `trace`,
`debug`, `info`, `warn`, `error`. You set a threshold and everything at
or above it prints. The [levels reference](/core/logging#log-levels) has
the full table.

**The source location is captured at compile time.** `(file:line)` is not
found by walking a stack trace at runtime — that would be slow and would
break under inlining. Uni captures it with a Scala 3 macro when your code
compiles, so it is exact and free. This is the "finds you" payoff: in a
real incident, the location turns "something failed" into "*this line*
failed," and you skip the search.

## You don't guard log calls

In many logging frameworks you learn to write this, because building the
message is expensive and you don't want to pay for it when `debug` is
off:

```scala
// The defensive pattern you do NOT need here:
if logger.isDebugEnabled then
  logger.debug(s"State dump: ${expensiveSnapshot()}")
```

With Uni you write the obvious thing:

```scala
debug(s"State dump: ${expensiveSnapshot()}")
```

The level methods are `inline` macros, so the argument is only evaluated
when the level is enabled. If `debug` is off, `expensiveSnapshot()` never
runs and there is no allocation — the call compiles down to nothing. The
guard is built in, so the readable form is also the fast form. See
[zero-overhead logging](/core/logging#zero-overhead-logging-with-scala-macros)
for what the macro expands to.

## Errors carry their cause

Pass the exception as a second argument and the stack trace rides along:

```scala
import wvlet.uni.log.LogSupport

class PaymentService extends LogSupport:
  def charge(amount: Int): Unit =
    try
      gateway.charge(amount)
    catch
      case e: Exception =>
        error(s"Charge of ${amount} failed", e)  // message + full stack trace
        throw e
```

The log keeps your message *and* the exception's stack trace together, so
you see the human-readable context and the machine detail in one record.

## Why the message is a sentence, not a struct

Some ecosystems push you toward structured logs — every line a bag of
key/value fields, meant to be parsed by a machine. Uni's logger is
deliberately *not* that. A log line here is a sentence written for a
person reading it during an incident: prose, with the source location and
any exception attached.

That is a choice, not an omission. Uni's position is that logs are
**developer context**, not your metrics or analytics pipeline. When you
need machine-readable records — counters, traces, events to aggregate —
emit them explicitly to a system built for it, and keep logs for the
human who is paging through them at 2 a.m. Mixing the two jobs tends to
serve neither well.

## Tuning what prints

You usually leave the defaults alone, but two knobs cover most needs:

```scala
import wvlet.uni.log.{Logger, LogLevel}

Logger.setDefaultLogLevel(LogLevel.DEBUG)          // global threshold
Logger("OrderService").setLogLevel(LogLevel.TRACE) // one logger, louder
```

A class that mixes in `LogSupport` already has a logger named after it,
so you can dial one noisy component up or down without touching the
others. Outside a class, `Logger("name")` gives you a named logger
directly. To send logs to a rotating file — on the JVM, Node.js, or
Native — point a [`FileLogHandler`](/core/logging#writing-logs-to-a-file)
at a path.

## What you have, what comes next

Logging is now a one-line, zero-ceremony tool:

- **`extends LogSupport`** gives any class `trace`/`debug`/`info`/`warn`/`error`
  and a logger named after it.
- Every message carries its **`(file:line)`**, captured at compile time.
- Level methods are **macros**, so unguarded `debug(...)` calls cost
  nothing when disabled.
- Logs are **human context** by design; `error(msg, e)` keeps the cause
  attached.

Next, [Chapter 6](./ch06-00-data) turns to data crossing your program's
edges — parsing JSON, writing MessagePack, and deriving both from your
case classes with one line.

[← 4. Testing with UniTest](./ch04-00-testing) | [Next → 6. Data In, Data Out](./ch06-00-data)

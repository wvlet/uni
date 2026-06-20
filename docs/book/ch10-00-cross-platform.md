# 10. One Codebase, Three Runtimes

Here is something that has been quietly true for the whole book: almost
every snippet you've written — `Design`, `Rx`, `Weaver`, the HTTP client —
already runs on the JVM, in the browser through Scala.js, and as a native
binary through Scala Native. You didn't do anything special to make that
happen. This chapter explains *how*, and what to do for the handful of
things that genuinely can't be shared.

## One build, three targets

A cross-platform module is declared once with `crossProject`:

```scala
// build.sbt
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

lazy val app = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(
    libraryDependencies += "org.wvlet.uni" %%% "uni" % "__UNI_VERSION__"
  )
```

That one declaration produces three projects — `appJVM`, `appJS`,
`appNative`. The `%%%` (three percent signs) is the cross-platform
dependency operator: it pulls in the build of `uni` that matches each
target. `sbt appJVM/run`, `sbt appJS/fastLinkJS`, and `sbt appNative/run`
build the same source three ways.

## Where code lives

`CrossType.Pure` gives each module these source roots:

```
app/
  src/main/scala/        # shared — compiled for all three platforms
  .jvm/src/main/scala/   # JVM-only
  .js/src/main/scala/    # Scala.js-only
  .native/src/main/scala/ # Native-only
```

The vast majority of your code goes in the shared `src/` and is compiled
three times. The `.jvm` / `.js` / `.native` folders hold only the code
that *must* differ per platform. If you never need them, you never create
them — and most application logic never does.

## Why most code doesn't care

The reason the shared folder stays large is that Uni already wraps the
platform-specific pieces behind cross-platform APIs:

- File I/O goes through the [FileSystem](/core/filesystem) abstraction
  (`IOPath`), not `java.nio.file.Path` — so reading a file is the same
  call everywhere.
- The [HTTP client](./ch08-00-http) resolves to `java.net.http`, the
  Fetch API, or libcurl depending on the runtime, behind one
  `Http.client`.
- `Rx`, `Design`, and `Weaver` are pure Scala with no platform
  assumptions.

Because your business logic talks to these abstractions instead of to JDK
classes directly, it lands in the shared folder and compiles everywhere
unchanged. That is the whole trick: depend on the abstraction, and the
platform detail is someone else's problem.

## Isolating what you truly can't share

Sometimes you need a real platform primitive — a JVM library with no JS
equivalent, a browser DOM call, a native syscall. The pattern is to
declare a small shared interface and put one implementation in each
platform folder:

```scala
// src/main/scala — shared: the shape everyone agrees on
trait Clock:
  def nowMillis(): Long

// .jvm/src/main/scala — the JVM implementation
object PlatformClock extends Clock:
  def nowMillis(): Long = System.currentTimeMillis()
```

Each platform folder supplies its own `PlatformClock`; the shared code
only ever sees the `Clock` interface. The seam is exactly the one from
[Chapter 3](./ch03-00-design) — program against an interface, bind the
implementation elsewhere — applied to platforms instead of test doubles.

The payoff is that the gap is caught at **compile time**: if a platform
is missing its implementation, that platform's build fails. You don't
discover a missing piece when a user in a browser hits it; you discover it
when `appJS` won't compile. The type system, not a test matrix, keeps the
three builds honest.

::: tip A few things are inherently platform-bound
Browsers have no synchronous HTTP and no filesystem; native binaries
have no DOM. Uni surfaces these honestly — for example, the sync HTTP
client throws in the browser rather than pretending. When a capability
only exists on one platform, keep the code that uses it in that platform's
folder.
:::

## What you have, what comes next

You can now ship one codebase to three runtimes:

- **`crossProject(JVMPlatform, JSPlatform, NativePlatform)`** with `%%%`
  dependencies builds the same source three ways.
- Shared code lives in **`src/`**; only genuinely platform-specific code
  goes in **`.jvm` / `.js` / `.native`**.
- Uni's abstractions (FileSystem, HTTP, Rx, Design, Weaver) keep most
  code in the shared folder.
- Unshareable primitives hide behind a **shared interface with a
  per-platform implementation**, and missing platforms fail at compile
  time.

Next, the final chapter, [Chapter 11](./ch11-00-testing), tests all of
this — and the same suite runs on all three runtimes.

[← 9. Typed RPC](./ch09-00-rpc) | [Next → 11. Testing with UniTest](./ch11-00-testing)

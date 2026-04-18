# 1.1 Installation

Uni is a library, not a framework. You add it to a Scala 3 project with
a single line in `build.sbt` and you are done. This chapter walks you
through getting the tools, creating a minimal project, and confirming
the setup.

If you already have Scala 3 and sbt installed, jump to [Adding Uni to a
project](#adding-uni-to-a-project).

## Prerequisites

You need three things:

| Tool | Minimum version | Why |
|------|-----------------|-----|
| JDK | 17 | Scala 3 targets modern JVMs. 17 is the LTS floor most of the ecosystem is on. |
| sbt | 1.9 | The build tool. Anything older may not know about current Scala.js / Native plugins. |
| Scala 3 | 3.3 or newer | Uni uses Scala 3 features (match types, given imports, `extends` with indentation). |

A JDK 17+ and sbt 1.9+ will pull the right Scala 3 compiler on demand.

### Installing the JDK and sbt

The single easiest path on any OS is [Coursier](https://get-coursier.io/):

```bash
cs setup
```

That command installs a JDK, `sbt`, `scala`, and sets up your `PATH`. If
you prefer native package managers:

- **macOS:** `brew install sbt openjdk@21`
- **Linux (Debian/Ubuntu):** install `sbt` from the [official APT
  repo](https://www.scala-sbt.org/download.html) and `openjdk-21-jdk`
  from your distribution.
- **Windows:** use [Scoop](https://scoop.sh/) or the
  [sbt MSI installer](https://www.scala-sbt.org/download.html).

Confirm both tools:

```bash
$ java --version
openjdk 21.0.4 2024-07-16 LTS
$ sbt --version
sbt script version: 1.9.8
```

## Adding Uni to a project

Create a directory and drop two files in it.

```
hello-uni/
├── build.sbt
└── src/main/scala/Main.scala
```

`build.sbt`:

```scala
val scala3Version = "3.3.4"

ThisBuild / scalaVersion := scala3Version

lazy val hello = project
  .in(file("."))
  .settings(
    name := "hello-uni",
    libraryDependencies += "org.wvlet.uni" %% "uni" % "2026.1.0"
  )
```

`src/main/scala/Main.scala`:

```scala
@main def main(): Unit =
  println("hello")
```

Run it:

```bash
$ sbt run
[info] running main
hello
```

The first `sbt run` will download Scala 3 and Uni's transitive
dependencies. Subsequent runs are fast.

> **Why no "framework initialization" step?** Uni is deliberately a
> library — a collection of small pieces you pull from. There is no
> bootstrap ceremony, no annotation processor to wire up, no agent to
> install. The moment sbt resolves the dependency, you can import and
> use any of it.

## Cross-platform projects

Most Uni code works unchanged on **JVM**, **Scala.js**, and **Scala
Native**. For a cross-build project, use
[sbt-crossproject](https://github.com/portable-scala/sbt-crossproject)
and reference Uni with `%%%` instead of `%%`:

```scala
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"       % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                    % "1.16.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"               % "0.5.7")
```

```scala
// build.sbt
import sbtcrossproject.CrossPlugin.autoImport.CrossType

lazy val hello = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("."))
  .settings(
    scalaVersion := "3.3.4",
    libraryDependencies += "org.wvlet.uni" %%% "uni" % "2026.1.0"
  )
```

Two parts of that snippet are worth a moment.

`.crossType(CrossType.Pure)` says *"all of this project's sources are
shared across platforms"*. With `Pure`, your project looks like this
on disk:

```
hello/
├── build.sbt
├── .jvm/          ← auto-generated per-platform project root
├── .js/           ← auto-generated per-platform project root
├── .native/       ← auto-generated per-platform project root
└── src/
    ├── main/scala/    ← shared code, compiled for all three platforms
    └── test/scala/    ← shared tests
```

The `.jvm`, `.js`, and `.native` directories are **not** where you put
code. sbt-crossproject creates them as per-platform project roots
(each is a distinct sbt subproject with its own `target/`), but your
Scala lives at the module root under `src/main/scala` and
`src/test/scala`. Compile once, run on three platforms.

> **Why this works with Uni.** Uni's public API — `Design`, `Logger`,
> `Http`, `Rx`, `JSON`, `MessagePack` — is the same on every platform.
> Where a platform difference exists (filesystem access, timers,
> threading), Uni exposes a single abstraction that resolves to the
> right implementation under the covers. For a typical Uni
> application, *all* of your code can live in the shared `src/main/scala`,
> and that is usually the whole story.
>
> If you do need truly platform-specific code — say, calling a
> JVM-only Java library — sbt-crossproject lets you drop files into
> `.jvm/src/main/scala`, `.js/src/main/scala`, or
> `.native/src/main/scala`, and only the matching platform sees them.
> You will see examples of this in Chapter 10.

The other alternative, `CrossType.Full`, uses a different layout with
a `shared/` folder alongside `jvm/`, `js/`, and `native/` folders for
per-platform sources. **Prefer `Pure`.** Reach for `Full` only when
platform-specific code is a substantial part of the project, at which
point splitting into separate modules is usually cleaner still.

The `%%%` operator tells sbt: *pick the version of `uni` that was built
for whichever platform I am compiling for right now*. Your Scala source
does not change between platforms; only the build setting does.

We will build a real cross-platform codebase in Chapter 10. For now
know that it is one cross-plugin, one `CrossType.Pure`, and a `%%%`
away — and that everything you write goes in `src/main/scala`.

## IDE setup

Uni uses only standard Scala 3 features, so any Scala 3-aware IDE
works. The common choices:

- **IntelliJ IDEA** with the Scala plugin.
- **VS Code** with [Metals](https://scalameta.org/metals/).
- **Neovim** with Metals.

Open the project directory, let the IDE import the build, and you are
done.

## What you have, what comes next

You now have:

- A JDK, sbt, and Scala 3 on your `PATH`.
- A `build.sbt` that pulls Uni in as a dependency.
- Proof that `sbt run` works on an empty program.

The [next chapter](./ch01-02-hello-uni) replaces that `println` with
actual Uni code — and in doing so meets the two ideas that thread
through the rest of the book: **logging** and **object wiring**.

[← 1. Getting Started](./ch01-00-getting-started) | [Next → 1.2 Hello, Uni!](./ch01-02-hello-uni)

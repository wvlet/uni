# sbt-uni Plugin

`sbt-uni` is uni's sbt 2.x plugin. It does two jobs:

1. **HTTP/RPC client generation** — turn a Scala 3 service trait into a type-safe
   client at build time (see [RPC](/http/rpc) for the full guide).
2. **Background fork-run** — start your application in a forked JVM and relaunch
   it on every code change with `~uniRestart`.

::: tip sbt 2.x
The plugin targets **sbt 2.x** (the metabuild runs on Scala 3, which lets the
plugin call uni directly as a library). It is not published for sbt 1.x.
:::

## Installation

In `project/plugins.sbt`:

```scala
addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "__UNI_VERSION__")
```

`UniPlugin` is not a triggered plugin, so enable it explicitly on each project
that should use it:

```scala
lazy val app = project
  .enablePlugins(UniPlugin)
```

## Background Fork-Run

`uniRestart` runs your application in a **forked JVM in the background**, so the
sbt shell stays free. It is a port of [sbt-revolver](https://github.com/spray/sbt-revolver)'s
`reStart` for sbt 2.x. Starting a new run stops the previous one first, which
makes it ideal under sbt's file watch:

```
sbt> ~app/uniRestart
```

`~uniRestart` recompiles and relaunches the process whenever a source file
changes — a tight edit/run loop for servers and long-running apps. Without the
`~`, `uniRestart` starts (or restarts) the app once.

| Task         | Description                                              |
| ------------ | -------------------------------------------------------- |
| `uniRestart` | (Re)start the app in a background forked JVM             |
| `uniStop`    | Stop the running app (kills the forked JVM)              |
| `uniStatus`  | Report whether the app is currently running              |

The forked JVM is also stopped automatically when you `reload` the build or quit
sbt, so it never leaks.

### Passing arguments

Everything after `uniRestart` is passed to your application's `main`. Arguments
after a `---` separator become **JVM options** for the forked process instead:

```
sbt> app/uniRestart --port 8080 --- -Xmx512m -Dconfig.file=dev.conf
```

Here `--port 8080` reaches `main(args)`, while `-Xmx512m` and the `-D` property
configure the forked JVM. Defaults that should always apply can be set with
`uniRestartArgs`:

```scala
uniRestartArgs := Seq("--port", "8080")
```

### Working directory

By default the forked process starts from the project's base directory. Override
`uniRestart / baseDirectory` when the app must run from somewhere else (for
example a directory holding config or static assets):

```scala
uniRestart / baseDirectory := baseDirectory.value / "server"
```

### Main class

The main class is auto-detected from `Compile / run / mainClass`. When a project
has several `main` methods, pin the one to fork:

```scala
uniRestart / mainClass := Some("com.example.MyServer")
```

### Settings reference

| Setting                     | Type          | Default                          | Description                                          |
| --------------------------- | ------------- | -------------------------------- | ---------------------------------------------------- |
| `uniRestart / mainClass`    | `Option[String]` | from `Compile / run / mainClass` | Main class to fork                                |
| `uniRestart / baseDirectory`| `File`        | project base directory           | Working directory of the forked JVM                  |
| `uniRestart / javaOptions`  | `Seq[String]` | project `javaOptions`            | JVM options for the forked process                   |
| `uniRestart / envVars`      | `Map[String,String]` | project `envVars`         | Environment variables for the forked process         |
| `uniRestartArgs`            | `Seq[String]` | `Seq.empty`                      | Default program arguments                            |
| `uniRestartForkOptions`     | `ForkOptions` | derived from the above           | Full fork configuration (advanced override)          |
| `uniRestartLogTag`          | `String`      | project name                     | Prefix tagging the forked app's console output       |
| `uniRestartColors`          | `Seq[String]` | basic colors + underlined        | Console colors assigned to background apps           |

::: tip Migrating from sbt-revolver
The tasks are intentionally uni-prefixed (`uniRestart` vs `reStart`) so both
plugins can coexist on a build while you migrate. Map `reStart` → `uniRestart`,
`reStop` → `uniStop`, `reStatus` → `uniStatus`.
:::

## HTTP/RPC Client Generation

Enable `UniPlugin`, depend on the project that defines the service trait, and
list your generation targets:

```scala
lazy val app = project
  .enablePlugins(UniPlugin)
  .dependsOn(api) // project containing the service trait
  .settings(
    uniHttpClients := Seq("com.example.api.UserService:rpc")
  )
```

`uniHttpClients` is wired into `Compile / sourceGenerators`, so clients are
regenerated and compiled on the next `compile`. Generation runs in-process (no
forked JVM) and skips files whose content is unchanged. See [RPC](/http/rpc) for
the target spec format and generated client API.

| Setting                | Type          | Default                  | Description                            |
| ---------------------- | ------------- | ------------------------ | -------------------------------------- |
| `uniHttpClients`       | `Seq[String]` | `Seq.empty`              | Generation targets (`fqcn:type[:pkg]`) |
| `uniHttpCodegenOutDir` | `File`        | `Compile / sourceManaged`| Output directory for generated code    |

# sbt-uni-crossproject Plugin

`sbt-uni-crossproject` lets one source tree cross-build for **JVM, Scala.js, and
Scala Native**. It is a small, uni-owned re-implementation of
[portable-scala/sbt-crossproject](https://github.com/portable-scala/sbt-crossproject),
which has not been ported to sbt 2.x.

It provides just the surface uni uses: `crossProject(...)` with the
**`CrossType.Pure`** layout — shared sources in `<base>/src`, platform-specific
code in `<base>/.jvm`, `.js`, and `.native`.

::: warning sbt 2.x only
The plugin targets **sbt 2.x** (its metabuild runs on Scala 3). It is not
published for sbt 1.x — if you are on sbt 1.x, use the original
`sbt-crossproject` instead.
:::

::: tip One plugin, all three platforms
Unlike upstream's three separate artifacts (`sbt-crossproject` +
`sbt-scalajs-crossproject` + `sbt-scala-native-crossproject`), this is a single
plugin that depends on both `sbt-scalajs` and `sbt-scala-native` and defines all
three platforms.
:::

## Installation

In `project/plugins.sbt`, add the Scala.js and Scala Native plugins (for their
`scalaJSLinkerConfig` / `nativeConfig` settings) and this one:

```scala
addSbtPlugin("org.scala-js"     % "sbt-scalajs"          % "1.22.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native"     % "0.5.12")
addSbtPlugin("org.wvlet.uni"    % "sbt-uni-crossproject" % "__UNI_VERSION__")
```

The plugin is triggered automatically, so `crossProject`, the platforms, and
`CrossType` are available in `build.sbt` without an explicit `enablePlugins`.

## Usage

```scala
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(/* shared settings */)
  .jvmSettings(/* JVM-only */)
  .jsSettings(/* Scala.js-only */)
  .nativeSettings(/* Scala Native-only */)

lazy val app = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("app"))
  .dependsOn(core, core % "test")
```

`crossProject` must be assigned directly to a `val` — it derives the project id
from the **val name** (just like sbt's own `project`). So `val core = …` defines
the sub-projects `coreJVM`, `coreJS`, and `coreNative`, which you reach as
`core.jvm`, `core.js`, and `core.native` (e.g. when aggregating or depending on a
single platform):

```scala
lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, core.native, app.jvm, app.js, app.native)

sbt> coreJVM/test
```

::: tip sbt 2.x dependencies
On sbt 2.x, `%%` already encodes the platform suffix (`_sjs1` for Scala.js,
`_native0.5` for Native), so use `%%` (not the old `%%%`) for your cross-platform
library dependencies inside `.settings(...)`.
:::

## Directory layout (`CrossType.Pure`)

```
core
├── .js      // core/.js/src/main/scala     — Scala.js-only sources
├── .jvm     // core/.jvm/src/main/scala     — JVM-only sources
├── .native  // core/.native/src/main/scala  — Scala Native-only sources
└── src      // core/src/main/scala          — shared across all platforms
             // core/src/main/scala-3, scala-2.13, ... — shared, version-specific
```

Shared sources live under `<base>/src`. Scala-version-specific shared sources
(`src/main/scala-3`, `src/main/scala-2.13`, …) are picked up too when `crossPaths`
is enabled (the default). The same structure applies under `src/test` for tests.

## Supported API

The cross-project value supports the methods uni's own build relies on:

| Method                                            | Description                                                    |
| ------------------------------------------------- | ------------------------------------------------------------- |
| `.crossType(CrossType.Pure \| CrossType.Full)`    | Choose the directory layout (default `Pure`)                  |
| `.in(file("..."))`                                | Set the cross-project base directory                          |
| `.settings(...)`                                  | Settings applied to every platform project                    |
| `.jvmSettings` / `.jsSettings` / `.nativeSettings`| Settings applied to one platform only                         |
| `.configure(...)` / `.jvmConfigure` / `.jsConfigure` / `.nativeConfigure` | Apply `Project => Project` transforms       |
| `.enablePlugins(...)` / `.disablePlugins(...)`    | Enable/disable sbt plugins on every platform project          |
| `.dependsOn(other, other % "test")`               | Depend on another cross-project, optionally scoped to a config|
| `.aggregate(other)`                               | Aggregate another cross-project                               |
| `.jvm` / `.js` / `.native`                        | The underlying single-platform `Project` for that platform    |

::: tip Scope
Only the `CrossType.Pure` (and `CrossType.Full`) layouts are supported. Upstream
features uni does not use — partially-shared sources, the `Dummy` layout, and
`withoutSuffixFor` — are intentionally omitted.
:::

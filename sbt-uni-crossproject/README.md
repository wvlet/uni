# sbt-uni-crossproject

A small, uni-owned **sbt 2.x** re-implementation of
[portable-scala/sbt-crossproject](https://github.com/portable-scala/sbt-crossproject), which has not
been ported to sbt 2.x.

It provides just the surface uni uses: `crossProject(...)` with the **`CrossType.Pure`** layout
(shared sources in `<base>/src`, platform-specific code in `<base>/.jvm`, `.js`, `.native`). The
full sbt-crossproject feature set (Full/Dummy layouts, partially-shared sources, `withoutSuffixFor`,
the deprecated APIs) is intentionally omitted.

Unlike upstream's three artifacts (`sbt-crossproject` + `sbt-scalajs-crossproject` +
`sbt-scala-native-crossproject`), this is a single plugin that depends on both
[`sbt-scalajs`](https://www.scala-js.org/) and
[`sbt-scala-native`](https://scala-native.org/) and defines all three platforms.

## Usage

`project/plugins.sbt`:

```scala
addSbtPlugin("org.scala-js"     % "sbt-scalajs"           % "1.22.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native"      % "0.5.12")
addSbtPlugin("org.wvlet.uni"    % "sbt-uni-crossproject"  % "<version>")
```

`build.sbt`:

```scala
lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(/* shared settings */)
  .jvmSettings(/* JVM-only */)
  .jsSettings(/* Scala.js-only */)
  .nativeSettings(/* Native-only */)

lazy val app = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("app"))
  .dependsOn(core, core % "test")
```

The `crossProject` macro derives the project id from the enclosing `val` name (just like sbt's own
`project` macro), so `val core = crossProject(...)` defines the sub-projects `coreJVM`, `coreJS`, and
`coreNative`, reachable as `core.jvm`, `core.js`, `core.native`.

### Directory layout (`CrossType.Pure`)

```
core
├── .js      // core/.js/src/main/scala     — Scala.js-only sources
├── .jvm     // core/.jvm/src/main/scala     — JVM-only sources
├── .native  // core/.native/src/main/scala  — Scala Native-only sources
└── src      // core/src/main/scala          — shared across all platforms
             // core/src/main/scala-3, scala-2.13, ... — shared, Scala-version-specific
```

## Supported API

On the builder: `.crossType`. On the cross-project: `.in`, `.settings`,
`.jvmSettings` / `.jsSettings` / `.nativeSettings`,
`.configure` / `.jvmConfigure` / `.jsConfigure` / `.nativeConfigure`, `.configurePlatforms`,
`.enablePlugins` / `.disablePlugins`, `.configs`, `.dependsOn` (with `% "test"`), `.aggregate`, and
the `.jvm` / `.js` / `.native` project accessors.

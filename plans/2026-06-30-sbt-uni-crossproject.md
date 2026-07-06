# sbt-uni-crossproject: a minimal sbt 2.x crossproject plugin

## Motivation

`portable-scala/sbt-crossproject` (and its companions `sbt-scalajs-crossproject` /
`sbt-scala-native-crossproject`) have not been ported to sbt 2.x. Uni's build relies on
`crossProject(...)` to cross-build `uni-core`, `uni`, and `uni-test` for JVM / Scala.js /
Scala Native, so a port is a prerequisite for moving the main build to sbt 2.x.

Uni only ever uses the `CrossType.Pure` layout (shared sources in `<base>/src`, platform code
in `<base>/.jvm`, `.js`, `.native`). The full sbt-crossproject feature set (Full/Dummy layouts,
partially-shared sources, `withoutSuffixFor`, deprecated APIs) is unnecessary. So we ship a small,
uni-owned, sbt 2.x plugin that re-implements just the surface uni uses.

## Deliverable

A new build at `sbt-uni-crossproject/`, modelled on `sbt-uni` / `sbt-uni-playwright`:

- A single sbt 2.x AutoPlugin `UniCrossProjectPlugin` exposing, via `autoImport`:
  - `crossProject(platforms: Platform*)` — an `inline` macro capturing the enclosing `val` name
    (replicating sbt 2.x's own `KeyMacro.definingValName`), so project ids stay `coreJVM`, `coreJS`, …
  - `JVMPlatform`, `JSPlatform`, `NativePlatform`
  - `CrossType` (with `Pure` and `Full`)
  - `given` conversions: `Builder => CrossProject` (drop-in for upstream's
    `crossProjectFromBuilder`) and `CrossProject => CrossClasspathDependency`.
- `CrossProject` is a `CompositeProject`; methods used by uni: `.crossType`, `.in`, `.settings`,
  `.jvmSettings`/`.jsSettings`/`.nativeSettings`, `.configure` variants, `.enablePlugins`,
  `.dependsOn(cp % "test")`, `.aggregate`, and `.jvm`/`.js`/`.native` accessors.
- Depends on `sbt-scalajs` and `sbt-scala-native` (both published for sbt 2.x as `_sbt2_3`) so
  `JSPlatform`/`NativePlatform` can `enablePlugins(ScalaJSPlugin / ScalaNativePlugin)`.

## Design notes

- Unlike upstream (3 artifacts so JVM-only users avoid the JS/Native plugin jars), uni needs all
  three platforms, so we keep one plugin that hard-depends on both and defines all three platforms
  and the `js`/`jvm`/`native` ops directly on `CrossProject` — no per-platform `*Ops` split.
- Default `CrossType` is `Pure` (uni always sets it explicitly anyway).
- Shared-source wiring mirrors upstream `sharedSrcSettings`: appends `<base>/src/<conf>/scala`
  plus the cross-version variants (`-3`, `-2.13`) to `unmanagedSourceDirectories`, gated on
  `crossPaths`. Partially-shared sources are dropped (uni doesn't use them).

## Validation

- `scripted crossproject/basic`: a `CrossType.Pure` project crossing JVM+JS+Native with shared
  and platform-specific sources, plus a cross `dependsOn`, compiling and running a test on each.

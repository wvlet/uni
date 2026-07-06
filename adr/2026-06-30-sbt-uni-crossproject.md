# 2026-06-30: uni-owned minimal sbt 2.x crossproject plugin

## Context

Uni cross-builds `uni-core`, `uni`, and `uni-test` for JVM / Scala.js / Scala Native using
`crossProject(...)` from [portable-scala/sbt-crossproject][xp] (plus its `sbt-scalajs-crossproject`
and `sbt-scala-native-crossproject` companions). Those plugins have **not** been ported to sbt 2.x,
which blocks uni's eventual sbt 2.x migration.

Uni only ever uses the **`CrossType.Pure`** layout (shared sources in `<base>/src`, platform code in
`<base>/.jvm`, `.js`, `.native`), and a small slice of the API. Re-implementing just that slice is
cheap, so we own it rather than wait for an upstream sbt 2.x port.

[xp]: https://github.com/portable-scala/sbt-crossproject

## Decision

Add a standalone sbt 2.x build, `sbt-uni-crossproject/`, publishing one plugin
(`org.wvlet.uni:sbt-uni-crossproject`, coordinate `_sbt2_3`). It re-implements `sbt-crossproject`'s
core: `Platform` (`JVMPlatform`/`JSPlatform`/`NativePlatform`), `CrossType` (`Pure` + `Full`),
`CrossProject` + `Builder`, `CrossClasspathDependency`, the `crossProject(...)` macro, and an
`AutoPlugin` exposing them via `autoImport`.

Feasibility verified before building: `sbt-scalajs` 1.22.0 and `sbt-scala-native` 0.5.12 are both
published for sbt 2.x (`_sbt2_3`), and `nscplugin_3.8.4/0.5.12` exists (so Scala 3.8.4 — uni's
version — is supported on Native).

### Key design points (and the traps behind them)

- **Single plugin, not three.** Upstream splits JVM/JS/Native into separate artifacts so a JVM-only
  user doesn't pull the JS/Native plugin jars. Uni needs all three, so we keep one plugin that
  hard-depends on both `sbt-scalajs` and `sbt-scala-native` and defines all three platforms — plus
  the `js`/`jvm`/`native` ops **directly on `CrossProject`**, dropping upstream's per-platform
  `*CrossProjectOps` implicit-class split entirely.

- **The val-name macro.** `val core = crossProject(...)` must yield ids `coreJVM`/`coreJS`/
  `coreNative`. Upstream uses a Scala 2 `Context` macro (`MacroUtils.definingValName`); on sbt 2.x
  (Scala 3) that is gone. We replicate sbt 2.x's *own* `KeyMacro.definingValName` verbatim — walk
  `Symbol.spliceOwner` up past `Macro`/`Synthetic`/non-term owners to the enclosing `val`, then
  `Expr(term.name)` — so behavior matches sbt's `project` macro exactly. Lives in
  `CrossProjectMacro.scala`, kept brace-style/folded in `.scalafmt.conf` (optional-brace removal and
  `newlines.source = unfold` both mangle quote/splice code).

- **`new` to call the private constructor.** The companion has both `CrossProject.apply(id, base)
  (platforms*): Builder` (used by the macro) and the private 3-arg primary constructor. Internal
  re-materialization (`mapProjectsByPlatform`, `build()`) **must** write `new CrossProject(id,
  crossType, projects)` — a bare `CrossProject(...)` resolves to `apply` and fails to typecheck.
  (This is the one place uni's "omit `new`" style is deliberately broken.)

- **Builder → CrossProject via `given Conversion`.** `crossType` lives only on `Builder` (it changes
  the directory layout, so it must be set before projects are built); every `CrossProject` method
  called on a `Builder` (e.g. `.in`) triggers `given Conversion[Builder, CrossProject] =
  _.build()`. A second `given Conversion[CrossProject, CrossClasspathDependency]` lets a bare cross
  project be used as a dependency (`dependsOn(core)`), while `core % "test"` builds a scoped one.
  sbt 2.x `.sbt` files apply `given` conversions implicitly (sbt's own `Project => ProjectReference`
  works the same way), so no `import scala.language.implicitConversions` is needed at the call site.

- **Shared sources read `baseDirectory.value`, not a captured path.** `build()` adds the
  `<base>/src/<conf>/scala` (+ `scala-3` / `scala-2.13` cross-version variants, gated on
  `crossPaths`) directories as settings that resolve `baseDirectory` at load time. So `.in(dir)` can
  re-base each platform project afterward and the shared dirs still point at the right place.

**Scope: sbt 2.x / Scala 3 only**, `CrossType.Pure` (Full kept since it's free; Dummy and
partially-shared sources dropped — uni uses neither). The main uni build stays on sbt 1.x with
upstream sbt-crossproject for now; this plugin unblocks the migration but does not perform it.

## Validation

`scripted crossproject/basic` cross-builds a `CrossType.Pure` `core` + dependent `app` for all three
platforms: it compiles JVM/JS/Native (Native to NIR only — no clang/Node needed), compiles the
shared `Test` sources, and runs the JVM app whose runtime assertion proves the shared, per-platform,
and `scala-3` cross-version sources all resolve. Wired into CI as `test_sbt_uni_crossproject` and
released by `release-sbt-uni-crossproject.yml`, mirroring `sbt-uni-playwright`.

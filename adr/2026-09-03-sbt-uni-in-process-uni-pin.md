# sbt-uni pins its in-process uni to a release built with sbt's metabuild Scala

Date: 2026-09-03

## Context

`sbt-uni` is an sbt 2.x plugin that calls uni's HTTP/RPC code generator
(`wvlet.uni.http.codegen.{HttpCodeGenerator, ServiceScanner}`) **in-process**,
inside sbt's metabuild. That is possible because sbt 2.x compiles the metabuild
with Scala 3, so the plugin can depend on `"org.wvlet.uni" %% "uni"` like any
library. Until now `sbt-uni/build.sbt` used `UNI_VERSION` (the locally published
snapshot of the current tree) for that dependency, so plugin and library moved in
lockstep.

Scala 3.9.0 (the new LTS) broke that coupling. Scala 3's TASTy rule is one-way: a
compiler in `3.x` can read TASTy produced by `3.y` only if `x >= y`. uni now
compiles with 3.9.0 (`build.sbt` `SCALA_3`), but sbt 2.0.8's metabuild runs Scala
3.8.4 (`./sbt "eval scala.util.Properties.versionNumberString"` → `3.8.4`, and
`sbt/sbt` tag `v2.0.8` has `scala3 = "3.8.4"`). Compiling the plugin against the
3.9-built uni fails with

```
Forward incompatible TASTy file has version 28.9, produced by Scala 3.9.0
expected stable TASTy from 28.0 to 28.8.
```

The failure first showed up on the "sbt plugin scripted test" CI job after the
Scala Steward bump in #688 (not a required check, so the PR auto-merged).

## Decision

`sbt-uni/build.sbt` depends on a **pinned** uni release whose Scala minor version
matches sbt's metabuild (`UNI_IN_PROCESS_VERSION = "2026.1.21"`, built with Scala
3.8.4), independent of the uni version being developed in this tree. The pin is
wrapped in `// scala-steward:off` / `:on`: Scala Steward rewrites same-shaped
`val ... = "2026.1.x"` literals (it did so for `UNI_PLUGIN_VERSION` in
`project/plugin.sbt`) and its PRs auto-merge, so an unguarded pin would advance to
the next, 3.9-built uni release and re-arm the exact failure above.

The `UNI_VERSION` env var is kept only for `scriptedLaunchOpts` (`-Duni.version=`),
because the scripted test *apps* must consume the 3.9-built local snapshot. Their
`scalaVersion` comes from a `scala.version` property passed next to it, which
`sbt-uni/build.sbt` reads from `val SCALA_3` in the root `build.sbt`, so a future
`SCALA_3` bump cannot leave the apps on a compiler too old to read the snapshot,
in CI or locally. Both properties fail loudly when missing.

The scripted tests' `project/plugins.sbt` no longer add `uni` to the metabuild
classpath. That line was redundant (the plugin already brings its own uni
transitively; the generated client is compiled in the `app` project, which
declares uni in `build.sbt`), and with a 3.9-built snapshot it re-introduces the
exact TASTy clash inside the test's metabuild. The same applies to consumers:
**never add `uni` to `project/plugins.sbt`** next to `addSbtPlugin(sbt-uni)`; a
newer uni there evicts the plugin's pinned one and breaks the metabuild.

Alternatives rejected or deferred:

- **Compile sbt-uni with Scala 3.9.** Consumers' sbt 2.0.x metabuild (3.8) could
  not read the plugin's own TASTy, so the plugin would be unusable for everyone.
- **Keep uni on Scala 3.8 until sbt catches up.** Blocks the LTS upgrade on an
  unknown sbt release date.
- **Vendor the codegen sources into the plugin.** The codegen files only import
  `wvlet.uni.log.LogSupport` and `wvlet.uni.text.CodeFormatter*` (both uni-core),
  so this is feasible, but it forks the sources and still compiles them with the
  metabuild Scala. Not worth it for a temporary pin.
- **Fork the codegen in a JVM on the user's classpath** (what sbt-airframe does;
  `ReStartActions` already forks). This removes the metabuild-Scala coupling
  permanently: the user's own `uni_3` carries the codegen package, so the plugin
  would need no `uni` dependency at all. Deferred because it needs a `main` entry
  point and process plumbing; it is the right move if this pin has to be re-done
  at the next sbt/Scala minor mismatch.

## Why the pin is safe today

- `ServiceScanner` inspects compiled `.class` files with plain Java reflection
  (`uni/.jvm/src/main/scala/wvlet/uni/http/codegen/ServiceScanner.scala`), so a
  plugin running on Scala 3.8 can scan service traits compiled by Scala 3.9. The
  scripted tests (`sbt-uni/src/sbt-test/codegen/*`) exercise exactly that
  combination.
- The codegen package had no functional change between v2026.1.21 and the pin.

## Consequences and constraints

- **The generated code's API surface is frozen.** The pinned generator emits code
  against `RPCClient.build`, `Surface.of/methodsOf`, `callSync/callAsync`,
  `HttpSyncClient/HttpAsyncClient` and `Rx`, which the *consumer* compiles against
  its own, newer uni. Changing any of those in uni without keeping the old shape
  breaks every sbt-uni consumer, and the pin cannot be advanced to fix it: uni is
  published under a single `_3` coordinate (`crossScalaVersions := List(SCALA_3)`),
  so no future uni release is 3.8-readable. Treat the generated-code contract as
  binary-compatible until the pin is lifted.
- **The scan class loader is parent-first.** `UniPlugin` scans user classes with
  `URLClassLoader(userUrls, pluginClassLoader)`, so any `wvlet.uni.*` or `scala.*`
  type appearing in a service trait signature (`Rx`, `HttpMessage`, ...) resolves
  from the plugin's pinned uni 2026.1.21 and scala3-library 3.8.4, not from the
  user's uni. Changing the class shape of such types (trait↔class, removed
  superinterface, `final`) will surface as `IncompatibleClassChangeError` inside
  consumers' metabuilds. Prefer keeping those types shape-stable; a child-first
  loader for the user classpath is the fix if that becomes untenable.
- **Coverage loss.** The scripted job now runs the *released* codegen against the
  snapshot runtime; changes to `HttpCodeGenerator`/`ServiceScanner` in this tree
  are no longer compiled or executed end-to-end by CI. Expect API drift to
  surface when the pin is lifted; a JVM test that compiles the current generator's
  output would close that gap.
- **Lifting the pin.** Needs a released sbt whose metabuild is Scala 3.9 (sbt's
  `develop` already has `scala3 = "3.9.0"`). Then bump `sbt.version` in
  `sbt-uni/project/build.properties` **and** the four scripted tests'
  `project/build.properties` (the root build's is irrelevant to the plugin),
  verify with `sbt "eval scala.util.Properties.versionNumberString"` inside
  `sbt-uni/`, and set `UNI_IN_PROCESS_VERSION` back to `UNI_VERSION`. Note that a
  lifted plugin depends on a 3.9-built uni, so its minimum sbt version rises to
  that release; consumers on older sbt 2.0.x keep using the pinned plugin version.
- The same rule applies to the other metabuild-loaded artifacts:
  `sbt-uni-playwright`'s `uni-jsenv-playwright` and `sbt-uni-crossproject` must
  stay on the metabuild's Scala minor (3.8.x for sbt 2.0.x), not on uni's
  `SCALA_3`. Do not "modernize" those to 3.9 until sbt moves.
- Downstream users of uni itself now need Scala 3.9 or later; the docs state
  that minimum, and code fences use `__SCALA_VERSION__` (substituted from the
  root `build.sbt` at docs build time) so samples cannot drift again.

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
3.8.4), independent of the uni version being developed in this tree. The
`UNI_VERSION` env var is kept only for `scriptedLaunchOpts` (`-Duni.version=`),
because the scripted test *apps* compile with Scala 3.9 and must consume the
3.9-built local snapshot.

The scripted tests' `project/plugins.sbt` no longer add `uni` to the metabuild
classpath. That line was redundant (the plugin already brings its own uni
transitively; the generated client is compiled in the `app` project, which
declares uni in `build.sbt`), and with a 3.9-built snapshot it re-introduces the
exact TASTy clash inside the test's metabuild.

Alternatives rejected:

- **Compile sbt-uni with Scala 3.9.** Consumers' sbt 2.0.x metabuild (3.8) could
  not read the plugin's own TASTy, so the plugin would be unusable for everyone.
- **Keep uni on Scala 3.8 until sbt catches up.** Blocks the LTS upgrade on an
  unknown sbt release date.
- **Vendor the codegen sources into the plugin.** Codegen depends on
  `wvlet.uni.log` and friends; far more machinery than a one-line, intentionally
  temporary pin.

## Why the pin is safe

- `ServiceScanner` inspects compiled `.class` files with plain Java reflection
  (`uni/.jvm/src/main/scala/wvlet/uni/http/codegen/ServiceScanner.scala`), so a
  plugin running on Scala 3.8 can scan service traits compiled by Scala 3.9.
  The scripted tests (`sbt-uni/src/sbt-test/codegen/*`) exercise exactly that
  combination.
- The codegen package had no functional change between v2026.1.21 and the pin.

## Consequences

- Codegen changes in `uni/.jvm/.../http/codegen` no longer reach `sbt-uni` until
  the pin is bumped to a release that sbt's metabuild Scala can read. Bump the
  pin deliberately when such a release exists.
- **Lifting the pin:** once a released sbt compiles its metabuild with Scala 3.9
  (sbt's `develop` branch already has `scala3 = "3.9.0"`) and
  `project/build.properties` is on that sbt, `UNI_IN_PROCESS_VERSION` can go back
  to `UNI_VERSION`. Verify with `./sbt "eval scala.util.Properties.versionNumberString"`.
- The same rule applies to the other metabuild-loaded artifacts:
  `sbt-uni-playwright`'s `uni-jsenv-playwright` and `sbt-uni-crossproject` must
  stay on the metabuild's Scala minor (3.8.x for sbt 2.0.x), not on uni's
  `SCALA_3`. Do not "modernize" those to 3.9 until sbt moves.
- Downstream users of uni itself now need Scala 3.9 or later; the docs state
  that minimum (`docs/guide/installation.md`, `docs/book/ch01-01-installation.md`).

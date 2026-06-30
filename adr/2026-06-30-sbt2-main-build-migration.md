# 2026-06-30: Migrate the main uni build to sbt 2.x

## Context

The main build ran on **sbt 1.x** (Scala 2.12 metabuild). The blocker for sbt 2.x was that several
plugins had no sbt 2.x release — chiefly `sbt-crossproject` (JVM/JS/Native cross-building) and the
third-party Playwright `JSEnv`. Those are now replaced by uni-owned sbt 2.x plugins
(`sbt-uni-crossproject`, `uni-jsenv-playwright`, see their ADRs), and the only other gap —
`sbt-revolver` — is unused in the build and replaced by `sbt-uni`'s `uniRestart`/`uniStop`/
`uniStatus`. So the migration became possible.

## Decision

Move `project/build.properties` to **sbt 2.0.1** and swap the metabuild plugins:

| Removed (no sbt 2.x build / superseded) | Replacement |
| --- | --- |
| `sbt-scalajs-crossproject` + `sbt-scala-native-crossproject` | `org.wvlet.uni:sbt-uni-crossproject` |
| `io.github.gmkumar2005:scala-js-env-playwright` | `org.wvlet.uni:uni-jsenv-playwright` |
| `sbt-revolver` | `org.wvlet.uni:sbt-uni` |
| `sbt-buildinfo` (was unused), `addDependencyTreePlugin` (built into sbt 2.x) | — |

`sbt-pack` bumped `0.23` → `1.0.0`. Kept (already on sbt 2.x): `sbt-pgp`, `sbt-scalafmt`,
`sbt-ide-settings`, `sbt-scalajs`, `sbt-scala-native`, `sbt-dynver`, `scalajs-env-nodejs`.

Because `sbt-uni-crossproject` reproduces the exact `crossProject` / `CrossType.Pure` API, the
`build.sbt` project definitions were unchanged — only sbt-2.x source-level adjustments were needed.

## The non-obvious migration traps (each cost a compile/load cycle)

1. **Output-directory collision.** sbt 2.x derives each project's output dir from its **name**
   (`target/out/<platform>/<scala>/<name>`), not its id. The root project and the `uni` library
   project were both `name := "uni"` → *"Overlapping output directories"* at load. Fixed by naming
   the root `uni-root`. (Per-platform crossproject projects don't collide — different platform dir.)

2. **`%%%` is gone; `%%` is now platform-aware.** On sbt 2.x `%%` already encodes the platform
   suffix (`_sjs1` / `_native0.5`), so every old `%%%` becomes `%%`. Conversely, a dependency that
   must stay platform-less needs care — `scalajs-test-interface` is published only as
   `scalajs-test-interface_2.13` (JVM-side), and in a Scala.js project **any** cross-version (`%%`
   or `.cross(for3Use2_13)`) injects the unwanted `_sjs1`. Name that fixed artifact directly with a
   single `%`: `"org.scala-js" % "scalajs-test-interface_2.13" % scalaJSVersion`. (Genuine JS
   platform libs like `scalajs-java-securerandom`/`-time`/`-logging` keep `%% … .cross(for3Use2_13)`
   → `_sjs1_2.13`.)

3. **`Test / jsEnv` must be `Def.uncached(...)`.** sbt 2.x caches setting values and a `JSEnv` is
   not serializable, so both the `NodeJSEnv` and the `PlaywrightJSEnv` are wrapped in `Def.uncached`.

4. **Opt into the `Project => ProjectReference` conversion.** `core.jvm` / `.js` / `.native`
   (a `Project`) used in the `Seq[ProjectReference]` aggregation lists needs
   `import scala.language.implicitConversions` at the top of `build.sbt` on Scala 3.

5. **Deprecations now warn.** `xs: _*` → `xs*`, and infix `classifier "x"` → `.classifier("x")`.

CI needs no change: the repo's `./sbt` runner reads `build.properties`, so it picks up 2.0.1, and
`code_format` runs `scalafmtCheckAll` against the (kept) `sbt-scalafmt`.

## Validation

All three platforms compile and test on sbt 2.0.1: JVM (1655 passed + netty/book 39), JS via
NodeJSEnv (~1491) and `domTest` via Playwright/Chromium (374), Native (1417 + 71). `uni-core`
reports 0 tests on every platform (it has no `src/test`) — not a regression.

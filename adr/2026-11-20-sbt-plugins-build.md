# The sbt plugins share one sbt-plugins/ build

## Context

uni publishes five sbt 2.x artifacts: sbt-uni, sbt-uni-crossproject, sbt-uni-javadoc, uni-jsenv-playwright, and sbt-uni-playwright. Each plugin started as a standalone sbt 2.x build in its own top-level directory, because the root build was still on sbt 1.x (`2026-06-26-uni-jsenv-playwright.md`). The root moved to sbt 2.x (`2026-06-30-sbt2-main-build-migration.md`), but the four builds stayed separate, each with its own `project/`, `.scalafmt.conf`, and a copy of the same `ThisBuild` publishing settings. CI started sbt four times for the plugin format check, and the release started four plugin builds.

## Decision

- The four directories move under `sbt-plugins/` with their layout unchanged. One `sbt-plugins/build.sbt` defines all five projects, and its root aggregate publishes nothing, so `sbt publishSigned` stages exactly the five artifacts. Coordinates, POMs, and jar entries are unchanged (checked by publishing both layouts to a local directory and diffing them).
- `sbt-plugins/` stays a separate build from the root. Uni's modules and the plugins keep separate CI jobs and release steps, and a plugin change does not reload the root's 45k-setting build (and the reverse).
- `sbt-plugins/.scalafmt.conf` is a symlink to the root config, which also carries the `CrossProjectMacro.scala` override. `.sbt` files are formatted with the Scala 3 dialect.
- CI checks plugin formatting with one `(cd sbt-plugins && ../sbt "scalafmtCheckAll; scalafmtSbtCheck")`. The four scripted jobs stay parallel, because they need different tools (Chromium, a locally published uni). Each runs `sbt "<project>/scripted"` in `sbt-plugins/`.
- The release stages the plugins with one `sbt publishSigned` in `sbt-plugins/`, in parallel with the root build's step. Both write to the root's `target/sona-staging`.

## Why not

- Plugins as subprojects of the root build: this would remove one more sbt start, but it would couple the plugins' CI and publishing to the library build, and every root load would also configure the plugin projects.
- One scripted job for all plugins: this would need every tool in one job and would lose the parallelism, so wall-clock time would go up.

## Consequences

- Plugin commands run from `sbt-plugins/` (`../sbt compile`, `../sbt sbtUni/scripted`).
- The root `project/plugin.sbt` compiles sbt-uni-javadoc from `sbt-plugins/sbt-uni-javadoc/src/main/scala` until it resolves the published plugin.
- This supersedes the per-directory build layout described in `2026-06-26-uni-jsenv-playwright.md`, `2026-06-30-sbt-uni-crossproject.md`, `2026-10-13-single-sonatype-bundle.md`, and `2026-11-16-sbt-uni-javadoc-plugin.md`.

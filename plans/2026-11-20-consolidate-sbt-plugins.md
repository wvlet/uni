# Build the sbt plugins in one sbt-plugins/ build

## Goals

- Stop starting sbt once per plugin. Today CI and the release start a separate sbt build for each of the four plugin directories. Each start loads its own metabuild and resolves its own dependencies.
- Keep the published artifacts as they are: the same coordinates and versions, the same POM content, and the same scripted tests.

## Background

- Today there are four standalone sbt 2.1.0 builds: `sbt-uni`, `sbt-uni-crossproject`, `sbt-uni-javadoc`, and `sbt-uni-playwright` (which holds `uni-jsenv-playwright` and the `sbt-uni-playwright` plugin).
  - Their `project/plugin.sbt` files load the same plugin set: pgp, dynver, scalafmt, scripted-plugin, and `sbt-uni-javadoc` compiled from source.
  - They share the same `ThisBuild` settings: organization `org.wvlet.uni`, dynver, `publishTo`, staging into the root `target/sona-staging`, and `javadocSiteUrl`.
  - Each has its own `.scalafmt.conf`. Their content matches the root file, except that crossproject adds a `fileOverride` for `CrossProjectMacro.scala`.
- sbt starts per change:
  - The Code format job runs `scalafmtCheckAll` in each of the four directories: 4 sbt starts.
  - Each of the four scripted jobs starts its own build. `sbt-uni` also runs `./sbt` twice in the root first, to publish uni locally and to read uni's version.
  - Release staging runs 4 plugin `publishSigned` calls in parallel with the root's.
- Why they were separate: when they were added, the root build was still on sbt 1.x, and the plugins had to build with sbt 2.x (`adr/2026-06-26-uni-jsenv-playwright.md`). The root moved to sbt 2.1.0 (`adr/2026-06-30-sbt2-main-build-migration.md`), so that reason is gone.
  - The main build consumes the plugins at the released `UNI_PLUGIN_VERSION`, not from source, so building them in the same build creates no bootstrapping cycle.
- Constraints that carry over:
  - The plugin projects set no `scalaVersion`, so they compile with sbt's own Scala (3.9.0 for sbt 2.1.0, the same as the root `SCALA_3`). `uni-jsenv-playwright` pins 3.8.2.
  - `sbt-uni` depends on uni `2026.1.21` from the repository (ADR `2026-09-03-sbt-uni-in-process-uni-pin`). The merged build will also contain the `uni` project itself.
  - Scripted tests run plugin code in a forked sbt, so they still need their own jobs and prerequisites (Chromium for playwright, a locally published uni for `sbt-uni`).

## Design

- Lane: **Standard**. This changes how release artifacts are staged (always-gate: packaging).
- User direction (2026-11-20): one separate `sbt-plugins/` build, so that publishing and CI stay isolated between uni's modules and the plugins.
- Move the four directories under `sbt-plugins/` with `git mv`, keeping their inner layout: `sbt-plugins/sbt-uni`, `sbt-uni-crossproject`, `sbt-uni-javadoc`, and `sbt-uni-playwright/{jsenv,plugin}`. Plugin sources do not change.
- `sbt-plugins/` gets one `build.sbt`, `project/`, and `.scalafmt.conf`, which replace the four copies.
  - The shared `ThisBuild` settings are written once. Staging still goes to the repo's `target/sona-staging`.
  - Each project keeps its `name`, `description`, dependencies, and scripted settings, so the coordinates and POMs stay the same.
  - A `noPublish` root aggregates the five projects, so `sbt publishSigned` stages exactly the five artifacts.
- The root build only updates the source path of `sbt-uni-javadoc`, which its metabuild compiles from source.
- CI:
  - Code format: one `(cd sbt-plugins && ../sbt scalafmtCheckAll)` replaces the 4-directory loop.
  - Scripted: four parallel jobs stay, each running `sbt "<project>/scripted"` in `sbt-plugins/`.
- Release: one `sbt publishSigned` in `sbt-plugins/` replaces the four plugin steps. It still runs in parallel with the root's step.
- Docs: update the paths in the docs, the READMEs, the workflow path filters and comments, and the ADRs that describe the per-build setup. Add an ADR recording the move.
- Done when:
  - CI's scripted jobs pass.
  - A local stage produces the same five artifacts, with the same `.pom` contents apart from source paths.

## Alternatives and Why Not?

- Plugins as subprojects of the root build (the first proposal): this removes one more sbt start, but it couples plugin CI and publishing to the library build.
- Merge the four scripted jobs into one: this saves runner minutes but loses parallelism, and the job would need every tool (Chromium, Node, a published uni). Wall-clock time goes up.

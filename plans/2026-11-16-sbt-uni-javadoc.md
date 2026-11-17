# sbt-uni-javadoc: reusable javadoc minimization

## Goals

- Let Airframe and other sbt 2 projects shrink their `-javadoc.jar` with one `addSbtPlugin`, instead of copying uni's build-local code.
- Maven Central caps monthly publishing per organization; the `wvlet` org (uni + airframe) already hit it with uni v2026.2.0.
- Keep uni's own javadoc jars byte-for-byte equivalent to main (same files, same links).

## Background

- `project/MinimizeScaladoc.scala` (#927) adds `-dynamic-side-menu` and patches Scaladoc's `scripts/ux.js` (scala/scala3#27403).
- `project/MinimizeJavadoc.scala` (#926, #928) writes link-only jars: `minimizeJavadoc` for Scala.js/Native artifacts, `minimizePluginJavadoc` for sbt plugins. URLs to wvlet.org/uni and github.com/wvlet/uni are hard-coded.
- The plugin sub-builds compile `MinimizeJavadoc.scala` into their metabuilds; uni's builds resolve uni plugins at the last published version, so a new plugin cannot be used via `addSbtPlugin` until its first release.

## Design

Lane: Standard (new published artifact), pre-approved: the launch prompt carries the full design.

- New sub-build `sbt-uni-javadoc` (`org.wvlet.uni % sbt-uni-javadoc`), same layout as the other plugin sub-builds, with scripted tests.
- `UniJavadocPlugin` (auto-triggered, adds no settings itself) only exposes keys; users add `minimizeScaladoc`, `minimizeJavadoc`, or `minimizePluginJavadoc` explicitly.
- `minimizeScaladoc` applies the flag and the ux.js patch only when `scalaBinaryVersion` is 3; Scala 2 builds are left untouched. The "patch no longer needed" warning stays.
- New keys `javadocSiteUrl` and `javadocSourceUrl`; when unset they fall back to `homepage` and `scmInfo.browseUrl`, so `ThisBuild / javadocSiteUrl := ...` works too.
- `minimizePluginJavadoc` links to the site and the source URL; `minimizePluginJavadoc(guide, sourceDir)` appends a guide path and `/tree/<tag or main>/<sourceDir>` (GitHub-style), which keeps uni's call sites unchanged.
- The plugin depends on sbt-salad-days 0.2.0, so one `addSbtPlugin` brings all reductions.
- Bootstrap: uni's root build and all plugin sub-builds (including sbt-uni-javadoc itself) add `sbt-uni-javadoc/src/main/scala` to their metabuild sources; `project/Minimize*.scala` are deleted. A comment says to switch to `addSbtPlugin` after the first release.
- CI/release: add the sub-build to the scalafmt loop, a scripted-test job, and a "Stage sbt-uni-javadoc" release step.
- Docs: `docs/build/sbt-uni-javadoc.md` (with a respectful sbt-salad-days mention), sidebar and README plugin table; update the ADR. `for-ai-agents.md` and the skill do not list the sbt plugins, so they stay unchanged.

## Alternatives and Why Not?

- Fold into sbt-uni: sbt-uni pulls in uni in-process; Airframe should not need that to shrink javadoc.
- Auto-apply settings: choosing which artifacts get link-only jars is each build's policy (see the ADR).

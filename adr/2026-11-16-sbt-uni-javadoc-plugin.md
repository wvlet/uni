# Publish the javadoc minimization as sbt-uni-javadoc

## Context

Uni shrank its `-javadoc.jar` files with build-local code (`project/MinimizeJavadoc.scala`, `project/MinimizeScaladoc.scala`, see [2026-11-16-minimize-js-native-javadoc.md](2026-11-16-minimize-js-native-javadoc.md)) plus sbt-salad-days. Maven Central's publishing limit applies to the whole `wvlet` organization, so Airframe needs the same reductions, and its sessions were reimplementing them. Airframe is on sbt 2, so it can use a uni sbt 2 plugin.

## Decision

- The code moved into a new sub-build, `sbt-uni-javadoc` (`org.wvlet.uni % sbt-uni-javadoc`), staged by the release workflow with the other plugins.
- `UniJavadocPlugin` is auto-triggered but adds no settings. Builds add `minimizeScaladoc`, `minimizeJavadoc`, or `minimizePluginJavadoc` to the projects they choose, because which artifacts keep full Scaladoc is each build's policy.
- `minimizeScaladoc` adds `-dynamic-side-menu` and the `ux.js` patch only when `scalaBinaryVersion` is 3: Scala 2 Scaladoc rejects the flag, and a cross-built project can share the setting across Scala versions.
- The uni URLs became `javadocSiteUrl` and `javadocSourceUrl`. When unset they fall back to `homepage` and `scmInfo.browseUrl`; the fallback is read with `.?` in the task rather than set as a project default, so `ThisBuild / javadocSiteUrl` works.
- `minimizePluginJavadoc(guide, sourceDir)` appends paths to those URLs (`/tree/<v-tag or main>/<sourceDir>`, GitHub style), so uni's call sites and pages stayed the same. The parameterless form links to the two URLs as they are.
- The plugin depends on sbt-salad-days, so one `addSbtPlugin` brings every reduction.
- Bootstrap: uni's builds resolve uni plugins at `UNI_PLUGIN_VERSION`, which predates the plugin. The root build and every plugin sub-build, including sbt-uni-javadoc itself, add `sbt-uni-javadoc/src/main/scala` to their metabuild sources instead of keeping copies under `project/`.

## Consequences

- The javadoc jars of uni and its plugins are unchanged (same files and links).
- After sbt-uni-javadoc's first release, the builds should switch to `addSbtPlugin("org.wvlet.uni" % "sbt-uni-javadoc" % UNI_PLUGIN_VERSION)` and drop the root's direct sbt-salad-days line. Until then, the plugin's sources must compile with every metabuild that loads them.
- Remove the `ux.js` patch once supported Scala versions include scala/scala3#27403; the plugin warns when Scaladoc's `ux.js` no longer has the bug.

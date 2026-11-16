# Scaladoc dynamic side menu

## Goals

- Shrink -javadoc.jar so uni releases fit Maven Central's per-organization publishing limits (the `wvlet` org is shared with airframe; v2026.2.0 was blocked).
- Keep the published API docs usable on javadoc.io.

## Background

- Scaladoc embeds the whole navigation menu in every HTML page (~100 KB each); `-dynamic-side-menu` writes it once and renders it with JS.
- Scaladoc up to 3.10 builds broken menu links when the docs are served under a sub-path, as javadoc.io does. scala/scala3#27403 fixes it, but no release includes the fix yet.
- #925 already strips fonts and inkuire.js through sbt-salad-days, which filters `packageDoc / mappings`.

## Design

Lane: Express. This is a build-only change with no change to library behaviour. It touches published artifact packaging, which the launch prompt already asked for.

- Add `-dynamic-side-menu` in the root `buildSettings`. Since #926 the Scala.js and Native jars of uni-core, uni and uni-test only link to the JVM docs, so this shrinks the remaining full Scaladoc jars (JVM, uni-ui).
- Patch the one broken link expression in `scripts/ux.js` in `packageDoc / mappings`, the same hook salad-days uses. Remove the patch once uni builds with a Scala release that includes scala/scala3#27403.
- Name the setting `minimizeScaladoc` (`project/MinimizeScaladoc.scala`), after the action it performs, next to #926's `minimizeJavadoc`.
- Leave the sbt plugin sub-builds unchanged: the flag saves about 5 KB per plugin jar, because their few pages carry little menu.

## Alternatives and Why Not?

- Add the flag without the patch: menu links would 404 on javadoc.io.
- Wait for Scala 3.11: blocks releases now.

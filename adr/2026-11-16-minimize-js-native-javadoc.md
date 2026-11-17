# Publish full Scaladoc only with the JVM artifact

## Context

Maven Central caps how much each organization can publish per month (https://central.sonatype.org/publish/maven-central-publishing-limits/). The `wvlet` organization is shared by uni and airframe, and uni v2026.2.0 was blocked by the size limit. About 58% of a uni release (~88 MB) was `-javadoc.jar`. The Scala.js and Scala Native jars of uni-core, uni, and uni-test repeated nearly the same API as their JVM jars: about 25 MB per release.

Maven Central still requires a `-javadoc.jar` for every artifact, so these jars cannot simply be dropped.

## Decision

- The `minimizeJavadoc` setting (now in the sbt-uni-javadoc plugin, see [2026-11-16-sbt-uni-javadoc-plugin.md](2026-11-16-sbt-uni-javadoc-plugin.md)) replaces `Compile / doc` with a task that writes only `index.html` and `README.md`. Both link to the JVM artifact's Scaladoc on javadoc.io and to the uni website. Scaladoc does not run, so the release build gets faster too.
- The jar is still the regular `packageDoc` artifact, so `publishSigned` signs and checksums it as before.
- It applies to the Scala.js and Scala Native platforms of uni-core, uni, and uni-test. The JVM jars are unchanged.
- Scala.js is included even though it adds JS-only APIs (`wvlet.uni.dom`, `wvlet.uni.electron`, the Playwright test helpers). Those stay documented in the guides on the uni website. Keeping full Scala.js docs would have saved only about half as much.
- Modules without a JVM artifact keep full Scaladoc: uni-ui is Scala.js only, so its Scaladoc is the only place its API is published.
- The sbt plugins (sbt-uni, sbt-uni-crossproject, sbt-uni-playwright) and uni-jsenv-playwright use `minimizePluginJavadoc` from the same file. Their page links to the plugin's guide on the uni website and its source on GitHub, because there is no other artifact with their Scaladoc. Users configure the plugins through settings described in the guides, so the Scaladoc added little, while each jar was ~0.5 MB, mostly Scaladoc's own scripts. The plugin sub-builds compile the sbt-uni-javadoc sources into their metabuild instead of copying them.
- The setting is not part of sbt-uni-crossproject, and sbt-uni-javadoc never applies it automatically: choosing what to publish is the build's policy, not the plugin's.

## Consequences

- javadoc.io no longer shows Scaladoc for the `_sjs1_3` and `_native0.5_3` artifacts of these modules. Readers follow the link to the `_3` artifact instead.
- javadoc.io no longer shows Scaladoc for the sbt plugins or uni-jsenv-playwright. Readers follow the links to the guide and the source.
- A new cross-built module with a published JVM artifact should add `minimizeJavadoc` to its `.jsSettings` and `.nativeSettings`. A Scala.js-only or Native-only module must not.
- If the JS-only APIs need Scaladoc again, a possible follow-up is to run scaladoc on only the JS-only packages (`-skip-by-id`) and link shared types to the JVM docs with `-external-mappings`.

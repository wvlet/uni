# Publish full Scaladoc only once per module

## Goals

- Stay within Maven Central's monthly per-organization publishing limit. The `wvlet` org is shared with airframe, and uni v2026.2.0 was blocked by the size limit.
- Stop publishing near-duplicate Scaladoc for Scala.js and Scala Native: about 26 MB of each release, out of 88 MB. The release build also gets faster.

## Background

- About 58% of a release is `-javadoc.jar`. Maven Central still requires every artifact to ship a javadoc jar, signed and checksummed.
- Published modules: uni-core, uni and uni-test (JVM/JS/Native), uni-ui (JS only) and uni-netty (JVM only). Examples and snippets use `noPublish`.
- Native-only sources implement shared packages (io, log, http, cli) and add little public API.
- Scala.js adds large APIs that exist only there: `wvlet.uni.dom` (48 files in uni-core), `wvlet.uni.electron` in uni, and `wvlet.uni.test.playwright` / `dom.testing` in uni-test.
- sbt-uni-crossproject is consumed as a published plugin (2026.1.41), so a setting added there would take effect only after the next plugin release.

## Design

Lane: Standard. This changes what we publish to Maven Central (packaging), and the Scala.js scope is a judgment call.

- Add a reusable `minimizeJavadoc` setting in `project/MinimizeJavadoc.scala`, with a comment that explains the publishing limit.
- With it, `Compile / packageDoc` builds a small jar without running scaladoc. The jar holds `index.html` and `README.md`, which say the API matches the JVM artifact and link to `https://javadoc.io/doc/org.wvlet.uni/<module>_3/<version>` and https://wvlet.org/uni/.
- The jar is still a regular `-javadoc.jar`, so publishSigned signs and checksums it as before.
- Apply it to uni-core, uni and uni-test on Scala.js and Scala Native.
- Apply it to Scala.js too (approved): the JS-only APIs (uni-dom, Electron) stay documented on the uni website.
- JVM jars, uni-ui (JS only) and uni-netty keep full Scaladoc unchanged.
- Add an ADR, since this decides what goes to Maven Central.
- Verification: `publishLocal` for JS and Native, comparing jar sizes before and after.

## Alternatives and Why Not?

- **Scaladoc of only the JS-only packages** (`-skip-by-id` for shared packages): keeps uni-dom on javadoc.io, but shared types would not link and the docs look half-broken. It is a possible follow-up.
- **Add the setting to sbt-uni-crossproject**: it could not be used until the plugin is released, and the plugin should not decide publishing policy for other builds.
- **An empty javadoc jar**: Central accepts it, but a page with links helps anyone who opens it on javadoc.io.

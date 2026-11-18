# Publish every release to repo.wvlet.org, and copy some to Maven Central

## Context

Since 2026-10-01 Maven Central enforces monthly publishing limits per organization: about 7 releases, 1,167 files and 78 MB (https://central.sonatype.org/publish/maven-central-publishing-limits/). The `wvlet` organization is shared by uni and airframe. One uni release is still ~60 MB after the javadoc trim ([2026-11-16-minimize-js-native-javadoc.md](2026-11-16-minimize-js-native-javadoc.md)), and uni v2026.2.0 was blocked. uni tagged 16 releases in October 2026. A higher limit has been requested, but release cadence should not depend on one vendor's policy.

Other projects took the same path: Scala moved Scala 3 nightlies to `repo.scala-lang.org`, and Codename One moved its releases to a Cloudflare R2 Maven repository while keeping old versions on Central.

## Decision

- Every `v*` tag publishes to the wvlet Maven repository, `https://repo.wvlet.org/maven2`: a Maven layout in the Cloudflare R2 bucket `wvlet-maven` (prefix `maven2/`), served through the `repo.wvlet.org` custom domain. The policy covers all of `wvlet` (uni and airframe share the repository and the Central budget).
- `release.yml` stages the single bundle as before ([2026-10-13-single-sonatype-bundle.md](2026-10-13-single-sonatype-bundle.md)), uploads the artifacts, then merges and uploads `maven-metadata.xml` for each artifact (`.github/scripts/maven_repo.py`). Metadata goes last, so a failed upload never lists an incomplete version. The publish job runs in the `wvlet-maven-repo` concurrency group so two releases never merge metadata at the same time.
- The merge reads the current metadata through the S3 API, not the public URL, and uploads metadata with `Cache-Control: no-cache`, so the Cloudflare cache never hides a version from Scala Steward or coursier.
- Maven Central gets at most about one uni release per month, chosen by hand: run the Release workflow with `central_version`. It downloads that version's files from R2, zips them into a Central Portal bundle and uploads it (`.github/scripts/central-release.sh`). Central therefore gets the same bytes and signatures as repo.wvlet.org.
- A failed Central upload does not affect the release on repo.wvlet.org. The job turns red and opens an issue; re-running the workflow with the same `central_version` retries it.
- The docs show the newest version on Maven Central (read from its `maven-metadata.xml`), so every snippet works without the extra resolver. The installation guide explains the resolver for newer versions, and the rule that a library published to Central depends on Central versions only and does not add the resolver.

## Consequences

- Applications that want a release that is not on Central yet add `resolvers += "wvlet" at "https://repo.wvlet.org/maven2"` (and the same in `project/plugins.sbt` for sbt plugin versions). No plugin adds it automatically, so a build's resolvers never change without the user knowing.
- The wvlet repository is a permanent obligation: removing it or its domain would break builds that pin versions found only there. The R2 credentials (`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_ACCOUNT_ID`) are scoped to the bucket.
- When airframe adopts this, its release workflow uses the same bucket, prefix and scripts. The `wvlet-maven-repo` concurrency group only serializes runs within one GitHub repository, so uni and airframe releases should not run at the same moment until the metadata merge is moved behind a shared lock.
- Earlier versions remain on Maven Central only; repo.wvlet.org starts with the first release after this change.
- Possible follow-ups: give the sbt plugins their own versions so unchanged plugins are not copied to Central every time, and measure the bundle size in the release job.

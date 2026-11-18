# Release often without being blocked by Maven Central limits

## Goals

- Keep shipping uni fixes and features as often as we need (October 2026 had 16 tags), without hitting Maven Central's monthly limits.
- Keep the setup simple for users: `"org.wvlet.uni" %% "uni" % version` keeps working, and stable versions stay on Maven Central.
- Treat Maven Central as one of the places we publish, so one service's policy can no longer block a fix from reaching users.

## Background

- Since 2026-10-01 Sonatype enforces monthly limits per **organization**, across all of its namespaces. The documented reference is about 7 releases, 1,167 files and 78 MB per month. Going over starts a 30-day grace period, and after it releases are rejected (https://central.sonatype.org/publish/maven-central-publishing-limits/).
- The `wvlet` org is shared by uni and airframe, so both projects draw on the same budget.
- One uni release was ~88 MB, and the minimized JS/Native javadoc (ADR 2026-11-16) cut about 25 MB. A release is still near the monthly size budget by itself, so size limits us more than the release count does.
- One release is a single Sonatype bundle (ADR 2026-10-13): uni-core/uni/uni-test × JVM/JS/Native, uni-ui, uni-netty, four sbt plugins (sbt-uni, sbt-uni-crossproject, sbt-uni-javadoc, sbt-uni-playwright) and uni-jsenv-playwright. The plugins share uni's version through the git tag, and the docs tell users to use the uni version for them. Each jar also needs a POM, sources, javadoc, signatures and checksums, so the file count grows with the cross-build matrix, not with how much code changed.
- Sonatype's stated path for community OSS is to **ask for higher limits**. Splitting into more namespaces or orgs to dodge the limits is treated as circumvention. Publisher Pro (paid) is required only above the limits or for commercial artifacts.
- How other projects handle it:
  - **Scala** moved Scala 3 nightlies to `repo.scala-lang.org` (Artifactory) in 2025 because of their size. Stable releases stay on Central, and sbt has `Resolver.scalaNightlyRepository`.
  - **Codename One** (2026) cut its release from 229 MB to 77 MB, published to both Central and a Cloudflare R2-backed Maven repo, then stopped publishing new versions to Central. Old versions stay on Central, and their project templates include the new repo.
  - **Kotlin Multiplatform** projects are hit hardest, since each platform variant adds artifacts. They are discussing fewer archives, project-hosted repos and limit requests; no shared repo has emerged.
  - Typelevel and other Scala.js/Native cross-builders have the same matrix as uni; requesting higher limits is the common first step.

## Design

Lane: Standard. This changes where and how often we publish (packaging, an always-gate area), and it affects airframe, which shares the org.

- **Two channels, same coordinates and signatures.**
  - **wvlet Maven repo** (new, e.g. `https://repo.wvlet.org/maven2`, a static Maven layout on Cloudflare R2): every tagged release goes here, as often as we like.
  - **Maven Central**: at most one uni release per calendar month, picked by hand (usually a milestone like `2026.2.0`, or the newest patch). This fits only if uni and airframe agree on a monthly MB split. Every version on Central also exists in the wvlet repo.
  - The wvlet repo must be a real Maven repo: the release job also rebuilds `maven-metadata.xml` from the bucket listing, so Scala Steward, coursier `latest.release` and version ranges see new versions, not only exact pins.
- **User flow**
  - Library authors and conservative users change nothing. They depend on Central versions, and Scala Steward proposes them as before.
  - Applications that want a fix now add one resolver by hand. The docs give the line for sbt `resolvers` (and `project/plugins.sbt` for plugin versions), scala-cli `//> using repository`, Mill and Gradle. No plugin adds it silently. They know it worked when the version resolves and Scala Steward starts proposing wvlet-repo versions.
  - To back out, pin a Central version and drop the resolver. Nothing on Central is removed or rewritten.
- **Rule for libraries:** a library published to Central should not add the wvlet resolver, so it (and Scala Steward) only sees Central versions, and its users never need the extra resolver for transitive deps.
- **Release workflow:** the existing single bundle is staged once. It is uploaded to the wvlet repo on every tag, and `sonaRelease` runs only when the tag is marked as a Central release (a tag suffix or a manual workflow input; decided in the PR). The Central upload is a separate job: if Sonatype rejects it, the wvlet release still stands, but the job turns red and opens an issue, and it can be re-run later for the same tag.
- **Higher Sonatype limits** for the `wvlet` org (uni and airframe together) have already been requested. Explain the JVM/JS/Native matrix, the shared airframe usage and the steps already taken (javadoc trim, fewer Central releases). If they approve, we can put more releases on Central, but the wvlet repo stays, so one vendor's policy no longer decides our release cadence.
- **Keep shrinking what goes to Central:**
  - Measure files and MB per artifact in the release job (`target/sona-staging`), so we see the budget before Sonatype does and know which parts to cut next.
  - Leave the sbt plugins out of Central-only months when they did not change. This needs their own versions and tags, so it is a follow-up with its own ADR, since today the docs pin them to the uni version.
  - Drop optional checksum files if the measurement shows sbt 2 stages sha256/sha512.
- **Cost of a self-hosted R2 repo:** about $0 to a few dollars a month. R2 charges nothing for egress, and the free tier covers 10 GB of storage, 1M writes and 10M reads a month. A year of frequent releases (~60 MB × ~20 a month) is about 15 GB, which costs roughly $0.08 a month above the free tier. Uploads are about 20k files a month. A custom domain with Cloudflare caching keeps most dependency downloads from counting as R2 reads.
- **Scope (decided):** one wvlet-wide policy. uni and airframe share repo.wvlet.org and the Central budget; airframe adopts the same scripts in its own PR.
- **Hosting (done):** Cloudflare R2 bucket `wvlet-maven` behind `repo.wvlet.org`; wvlet.org DNS moved to Cloudflare; bucket-scoped `R2_*` secrets on wvlet/uni.
- **Central copies the wvlet repo's files:** the Central job downloads a version from R2 and uploads it as a Central Portal bundle, so both repositories serve the same bytes and signatures.
- **The docs show the newest Central version**, so copied snippets resolve without the extra resolver.
- Deliverables in this PR: this note, the ADR, the release workflow and scripts, and the resolver docs.

## Alternatives and Why Not?

- **Only ask Sonatype for higher limits:** cheap and worth doing, but it leaves our release cadence to a policy that has already changed twice this year.
- **Leave Maven Central entirely (as Codename One did):** every new user would need the extra resolver, Scala Steward and library authors would be hurt, and Central is where the Scala ecosystem expects to find libraries.
- **Release less often:** fixes wait for the monthly budget, which is the problem we want to solve.
- **Central snapshots for the frequent builds:** they are mutable and deleted after 90 days, and Sonatype does not say whether they count toward the limits. They are fine for CI previews but not for releases users pin.
- **GitHub Packages:** downloads need a token even for public packages, so it is not usable as a public resolver.
- **JitPack:** builds from source on demand, which is unreliable for the Scala.js/Native cross-build and plugin sub-builds.
- **Split namespaces or orgs:** Sonatype counts this as circumvention.
- **Publisher Pro:** costs money for a community project, and it moves the limit without removing it.

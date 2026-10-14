# Bundle all release artifacts into a single Sonatype deployment

## Goals

- Every `v*` tag currently sends **six** separate deployments to Sonatype Central: one each from the
  `release.yml` (JVM), `release-js.yml`, `release-native.yml`, `release-sbt-plugin.yml`,
  `release-sbt-uni-crossproject.yml` and `release-sbt-uni-playwright.yml` workflows.
- Sonatype Central is not a robust deployment target. Each upload/validate/publish round trip is a separate
  chance to time out, hit rate limits, or leave a release half-published (for example, JVM artifacts
  published but Scala.js stuck in validation). Partial releases are also hard to retry, because
  already-published coordinates cannot be re-uploaded.
- Goal: stage every artifact for a tag locally, then run **one** `sonaRelease` that uploads a single
  bundle. A release then publishes either completely or not at all, and the number of Sonatype
  interactions drops from 6 to 1.

## Background

- sbt 2.x's built-in Sonatype support (in `main_3`, `Defaults`):
  - `stagingDirectory := (ThisBuild / baseDirectory).value / "target" / "sona-staging"`
  - `localStaging := Some(Resolver.file("local-staging", stagingDirectory.value))`
  - `sonaBundle` zips `stagingDirectory` into `target/sona-bundle/bundle.zip`; the `sonaRelease` and
    `sonaUpload` commands upload that zip as one deployment.
- All four builds (root, `sbt-uni`, `sbt-uni-crossproject`, `sbt-uni-playwright`) use
  `publishTo := localStaging.value` for releases, but each is a separate sbt build. Each therefore stages
  into its own `target/sona-staging`, which is why each workflow runs its own `sonaRelease`.
- The plugin builds do not depend on the uni version being released. `sbt-uni` pins
  `UNI_IN_PROCESS_VERSION`, and the other two have no uni dependency. All builds can therefore be
  published from the same checkout in any order.
- The JS and Native releases already run the root build. The only reason they are split out is per-platform
  toolchain setup (Node and libcurl).
- sbt 2.x client/server caveat (already documented in the plugin workflows): env vars must be present when
  the sbt server starts, so credentials are set on the job's steps that launch sbt.

## Design

1. **Point every plugin build's staging dir at the root one.** In `sbt-uni/build.sbt`,
   `sbt-uni-crossproject/build.sbt` and `sbt-uni-playwright/build.sbt` add:
   ```scala
   // Stage into the uni root's staging dir so one `sonaRelease` from the root uploads every artifact
   Global / stagingDirectory := (ThisBuild / baseDirectory).value.getParentFile / "target" / "sona-staging"
   ```
   `Global` scope is required: sbt defines `localStaging` as `Global / localStaging`, so it reads
   `Global / stagingDirectory`. A `ThisBuild` override is ignored, as verified locally.
2. **Merge the six workflows into a single `release.yml` job**. The four staging steps run in an Actions `parallel:` step group (GA since 2026-06-25):
   - Setup: checkout (fetch-depth 0), Java 23, Node 22, libcurl, `sbt/setup-sbt`, and GPG import.
   - `./sbt "projectJVM/publishSigned; projectJS/publishSigned; projectNative/publishSigned"`
   - `sbt publishSigned` in `sbt-uni` and `sbt-uni-crossproject`, and
     `sbt "jsenv/publishSigned; plugin/publishSigned"` in `sbt-uni-playwright`
   - The final step runs `./sbt sonaRelease` from the root, with the Sonatype credentials, to upload one
     bundle.
   - Delete `release-js.yml`, `release-native.yml`, `release-sbt-plugin.yml`,
     `release-sbt-uni-crossproject.yml`, and `release-sbt-uni-playwright.yml`. Keep `release-note.yml`
     unchanged.
3. **Sonatype credentials.** Every publish step needs `PGP_PASSPHRASE`. Because of the sbt 2.x
   client/server caveat, the root sbt server must see `SONATYPE_*` when it starts. Set the credentials at
   the job level, or shut down the root server before the final step (`./sbt shutdown`) and launch
   `sonaRelease` in a fresh server with the credentials. I'll use **job-level env** because it is the
   simplest option.
4. Keep `workflow_dispatch` so a failed release can be re-run as one unit.
5. Local check: run `publishSigned` with a dummy snapshot→release version in a plugin dir, and confirm that
   the files land under `../target/sona-staging`. `sonaBundle` in the root should then include them.

## Alternatives and Why Not?

- **Parallel build jobs, single upload job.** Keep JVM/JS/Native/plugins as parallel jobs that
  `upload-artifact` their `sona-staging` trees, then run a final job that merges them and calls
  `sonaRelease`. This is faster in wall-clock time, but it adds artifact plumbing and more workflow YAML.
  Release builds are not latency-critical. Revisit this if the serial job becomes too slow.
- **Keep separate workflows and use `sonaUpload` plus manual publish.** This still creates multiple
  deployments and needs manual work in the Sonatype UI. It does not solve partial releases.
- **Env-var-configured staging dir** (`UNI_STAGING_DIR`). This is more flexible, but always pointing at the
  parent is simpler. A local `publishSigned` of a plugin should also stage alongside uni.

## Open questions

- Is the longer serial runtime acceptable? JVM, JS, and Native builds plus three plugin builds probably take
  roughly 20–30 min in one job.

# Publish sbt-uni plugin to Maven Central

## Problem

The Sonatype release pipeline currently ships only the main library artifacts:

- `.github/workflows/release.yml` — publishes `projectJVM/publishSigned` and calls `sonaRelease`
- `.github/workflows/release-js.yml` — publishes `projectJS/publishSigned`
- `.github/workflows/release-native.yml` — publishes `projectNative/publishSigned`

The `sbt-uni` plugin (in `sbt-uni/`) is a separate sbt 2.x build that is **never** published on release. It only runs as a scripted test in `test.yml` (`test_sbt_plugin` job). Consumers cannot declare `addSbtPlugin("org.wvlet.uni" % "sbt-uni" % "...")` today because no artifact is uploaded to Central.

## Plan

Add a new workflow `release-sbt-plugin.yml` that:

1. Triggers on `v*` tags (matching the other three release workflows) and `workflow_dispatch`.
2. Imports the GPG key and uses the existing `PGP_SECRET` / `PGP_PASSPHRASE` secrets.
3. Runs `./sbt "coreJVM/publishLocal; uniJVM/publishLocal"` from the root so `sbt-uni` can resolve the just-built library (the Central sync for the library finishes asynchronously in `release.yml`; we avoid a race by using `publishLocal` on the same runner).
4. Extracts `UNI_VERSION` from `uniJVM/version` (mirroring how `test.yml` does it) and passes it to the sbt-uni metabuild via the `UNI_VERSION` env var — `sbt-uni/build.sbt` already honours this.
5. From `sbt-uni/`, runs `sbt "publishSigned"` then `sbt sonaRelease` using `SONATYPE_USER` / `SONATYPE_PASS` secrets, identical to the other three workflows.

The `sbt-uni/build.sbt` already has the Maven Central metadata (organization, licenses, homepage, scmInfo, developers, `publishTo` with `localStaging`), so no changes to that file are required.

## Why a separate workflow (vs. chaining into release.yml)

- Matches the existing split (`release-js.yml`, `release-native.yml`).
- Keeps Scala Native tooling out of the sbt-plugin job (and vice versa).
- Runs in parallel with the main JVM publish; neither depends on the other's network upload because the plugin uses the locally-published library artifact.

## Non-goals

- Snapshot publishing for the plugin (matches current behaviour: snapshots are a local-only concern).
- Rewriting the sbt 2.x sbt-uni build. Its Maven metadata already matches the root.

## Verification

- `gh workflow run release-sbt-plugin.yml` manually after merge to dry-run.
- Next `v2026.x.y` tag should produce `sbt-uni_3_2.0.0-RC10` artefact on `https://central.sonatype.com/search?q=org.wvlet.uni`.

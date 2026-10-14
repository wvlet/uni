# Release every artifact as one Sonatype deployment

## Context

Each `v*` tag used to start six independent release workflows: JVM, Scala.js, Scala Native, `sbt-uni`, `sbt-uni-crossproject`, and `sbt-uni-playwright`. Each one ran its own `sonaRelease`, so a single release produced six Sonatype Central deployments. Sonatype Central is not a reliable target: uploads time out and validation can stall. When one deployment failed, the release was left half-published, and the published coordinates could not be re-uploaded to retry it.

## Decision

- `.github/workflows/release.yml` is the only release workflow. One job signs and stages the root build (`projectJVM`, `projectJS`, `projectNative`) and the three plugin builds into the root's `target/sona-staging`. A single `./sbt sonaRelease` from the root then uploads them all as one bundle, so a release publishes completely or not at all.
- Each plugin build sets `Global / stagingDirectory := (ThisBuild / baseDirectory).value.getParentFile / "target" / "sona-staging"`.
- The setting must be in **`Global`** scope. sbt 2.0.x defines `localStaging` (which `publishTo` uses for releases) as `Global / localStaging := Some(Resolver.file("local-staging", stagingDirectory.value))`, so it reads `Global / stagingDirectory`. A `ThisBuild / stagingDirectory` override still shows the new path in `show stagingDirectory`, but artifacts are silently published to the plugin's own `target/`. Verify with `show localStaging`.
- Credentials are set only on the sbt steps. Each sbt step carries both the PGP and Sonatype variables, because sbt 2.x captures env vars when its server starts.

## Consequences

- A release runs serially in one job, so it takes longer than the old parallel workflows. If that becomes a problem, use parallel build jobs that upload their `sona-staging` trees as workflow artifacts, followed by a single merge-and-`sonaRelease` job. Do not go back to per-workflow `sonaRelease`.
- A new published build (for example another sbt plugin) must stage into the root staging dir the same way and get a step in `release.yml`. It must not get its own release workflow.
- Running `publishSigned` locally in a plugin dir stages into the root's `target/sona-staging`.

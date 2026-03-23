# Publish uni 2026.1.0 to Maven Central

## Context
uni needs to be published to Maven Central so wvlet (and GitHub Actions CI) can depend on it as a normal library dependency. The versioning scheme is `YYYY.(milestone).patch`, with `2026.1.0` as the first release.

## Approach
Mirror airframe's publishing setup, adapted for uni's Scala 3-only structure.

## Changes

### Plugins (`project/plugin.sbt`)
- Added `sbt-pgp` 2.3.1 for GPG signing
- Added `sbt-sonatype` 3.12.2 for Maven Central release

### Build settings (`build.sbt`)
- `ThisBuild` level: `organization`, `dynverSonatypeSnapshots`, `dynverSeparator`, `publishTo`
- POM metadata: `licenses`, `homepage`, `scmInfo`, `developers`
- Command aliases: `publishSnapshots`, `publishJSSigned`, `publishNativeSigned`

### GitHub Actions release workflows
- `release.yml` — JVM artifacts
- `release-js.yml` — Scala.js artifacts
- `release-native.yml` — Scala Native artifacts

All triggered on `v*` tags. Required secrets: `PGP_SECRET`, `PGP_PASSPHRASE`, `SONATYPE_USER`, `SONATYPE_PASS`.

### Published modules
- `uni-core` (JVM/JS/Native)
- `uni` (JVM/JS/Native)
- `uni-test` (JVM/JS/Native)
- `uni-agent`, `uni-bedrock`, `uni-netty` (JVM only)

### Skipped modules
- root, projectJVM, projectJS, projectNative, domTest, integrationTest

## Release process
1. Tag: `git tag v2026.1.0 && git push origin v2026.1.0`
2. GitHub Actions automatically publishes to Maven Central
3. Verify at https://central.sonatype.com/search?q=org.wvlet.uni

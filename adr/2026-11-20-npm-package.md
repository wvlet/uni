# One npm package for Uni: `@wvlet/uni` with subpath exports

## Context

TypeScript and JavaScript users want uni-parquet without a Scala toolchain. uni-parquet already
cross-builds to Scala.js as ES modules, but its API returns Scala types (`Seq`, `Map[String, Any]`,
`Array[Byte]`, `Rx`). Scala.js does not generate `.d.ts` files. Two separately linked Scala.js
bundles each carry their own Scala runtime and cannot exchange Scala objects.

## Decision

- Publish one npm package, `@wvlet/uni`, built by the Scala.js-only `uni-npm` sbt project, which
  is not published to Maven. Each Uni module becomes a subpath export (`@wvlet/uni/parquet` first)
  with `@JSExportTopLevel(..., moduleID = "<subpath>")`. All subpaths come from one link run with
  `ModuleSplitStyle.FewestModules`, so they share one Scala runtime in an `internal-*.js` module.
- The facade (`wvlet.uni.parquet.npm`) converts to JS values: INT64 as `bigint`, decimals as
  strings, DATE as a UTC-midnight `Date`, TIME and UUID as strings, timestamps as `Date` or, with
  `{ timestamps: "bigint" }`, epoch values in the column's unit. Plain numeric batch columns are
  copied into typed arrays. Rx results become Promises and an `AsyncIterable`.
- Type declarations are written by hand next to the facade (`uni-npm/src/main/npm/parquet.d.ts`).
  CI packs the staged package, installs the tarball, runs a Node smoke test, and type-checks a TS
  consumer with `tsc`, so the declarations and the facade can't drift apart silently.
- `npm/npmPackage` stages the package in `uni-npm/target/npm`. `main.js`, which holds uni-core's own
  top-level exports, is left out because the package exports only its subpaths.
- npm publishing lives in its own workflow, `release-npm.yml`, not in `release.yml`. npm trusts
  exactly one workflow file, so only this file can mint a publish token; the Maven jobs and their
  R2 and GPG secrets never get `id-token: write`, and the npm job never sees those secrets. It runs
  on `v*` tag pushes, in parallel with the Maven release, since the package does not depend on it.
  It runs in an `npm` environment, where protection rules such as required reviewers can be added.
- The job checks that the package version equals the tag, skips a version already on npm, and
  publishes with provenance through npm trusted publishing (OIDC). No npm token is stored.
- A failed npm publish is retried on its own: re-run the workflow, or dispatch it with `tag`. The
  Maven release is unaffected either way.
- npm only lets a trusted publisher be configured for a package that exists. The first version is
  therefore published by a maintainer, from the staged directory of the first release tag
  (`./sbt npm/npmPackage`, then `npm publish --access public` in `uni-npm/target/npm`), and
  `wvlet/uni`'s `release-npm.yml` (environment `npm`) is then added as the trusted publisher on
  npmjs.com.
- That happened for 2026.2.2: its tag's `release-npm.yml` run failed with E404, as expected, and a
  maintainer published the staged package by hand (`npm publish` needs `--otp=<code>` when run
  non-interactively). npm then asks for one publish through the new trusted publisher within two
  days to validate it. Re-running the workflow for the first tag does not count, since that version
  is already on npm and the job skips it, so the next tag is cut right away.

## Alternatives

- **A package per module (`@wvlet/uni-parquet`).** Each would ship its own copy of the Scala
  runtime, and values could not pass between them.
- **Publishing the Scala.js output with no facade.** TS users would get Scala collections and an
  untyped API.
- **Generated declarations.** There is no Scala.js generator; a small hand-written file checked by
  `tsc` in CI costs less.
- **A job inside `release.yml`.** npm would then trust the whole file, giving the Maven jobs a way
  to publish, and a failed npm step could only be retried through "Re-run failed jobs" of the
  Maven release.
- **An `NPM_TOKEN` secret.** Trusted publishing keeps no long-lived credential and adds provenance,
  at the cost of one manual first publish.

## Consequences

- The package is about 360 KB minified and gzipped for read and write. Reducing it (lazily loaded
  codecs and HTTP, fewer immutable collections on the read path) is follow-up work.
- The linked code imports Node.js built-ins (`os`, `path`, `zlib`) at the top level, so browser
  bundlers must stub them (Vite does by default; webpack needs `resolve.fallback`).
- A new subpath needs its facade, a `.d.ts`, an `exports` entry in `uni-npm/src/main/npm/package.json`,
  and smoke tests in `uni-npm/npm-test/`.

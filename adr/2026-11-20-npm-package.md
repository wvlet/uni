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
- The `npm` job in `release.yml` runs on `v*` tag pushes after the Maven upload, never on the
  Maven Central copy dispatch. It checks that the package version equals the tag, skips a version
  already on npm, and publishes through npm trusted publishing (OIDC, `id-token: write`) with
  provenance. No npm token is stored.
- If only the npm job fails, recover with "Re-run failed jobs": the Maven versions are already
  uploaded and are never overwritten, so rerunning the whole workflow would stop at the Maven check.
- npm only lets a trusted publisher be configured for a package that exists. The first version is
  therefore published by a maintainer, from the staged directory of the first release tag
  (`./sbt npm/npmPackage`, then `npm publish --access public` in `uni-npm/target/npm`), and
  `wvlet/uni`'s `release.yml` is then added as the trusted publisher on npmjs.com.

## Alternatives

- **A package per module (`@wvlet/uni-parquet`).** Each would ship its own copy of the Scala
  runtime, and values could not pass between them.
- **Publishing the Scala.js output with no facade.** TS users would get Scala collections and an
  untyped API.
- **Generated declarations.** There is no Scala.js generator; a small hand-written file checked by
  `tsc` in CI costs less.
- **An `NPM_TOKEN` secret.** Trusted publishing keeps no long-lived credential and adds provenance,
  at the cost of one manual first publish.

## Consequences

- The package is about 360 KB minified and gzipped for read and write. Reducing it (lazily loaded
  codecs and HTTP, fewer immutable collections on the read path) is follow-up work.
- The linked code imports Node.js built-ins (`os`, `path`, `zlib`) at the top level, so browser
  bundlers must stub them (Vite does by default; webpack needs `resolve.fallback`).
- A new subpath needs its facade, a `.d.ts`, an `exports` entry in `uni-npm/src/main/npm/package.json`,
  and smoke tests in `uni-npm/npm-test/`.

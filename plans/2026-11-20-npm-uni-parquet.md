# Publish uni-parquet to npm as `@wvlet/uni/parquet`

## Goals

- Let TypeScript and JavaScript users read and write Parquet with Uni from npm, with no Scala toolchain: `import { openParquet, writeParquet } from "@wvlet/uni/parquet"`.
- Read and write the same files as uni-parquet on the JVM and Scala Native, because the package is built from the same code.
- Start an npm package shape that later Uni modules (YAML, Markdown, cron, JSON/MessagePack) can join without a breaking change.

## Background

- uni-parquet already cross-builds to Scala.js as an ES module, with a Node file source and async HTTP range reads. What is missing is a JS-shaped API, type declarations, and publishing.
- The Scala API leaks Scala types (`Seq`, `Map[String, Any]`, `Array[Byte]`, `Rx`). TS users expect `Uint8Array`, plain objects, `bigint`, `Promise`, and `AsyncIterable`.
- Size, measured in this session with a probe facade, `fullLinkJS` and then esbuild, min + gzip: read from bytes 234 KB, plus URL reads 311 KB, read + write 363 KB. hyparquet with its compressors, for reference, is about 94–113 KB.
- Separately linked Scala.js bundles each carry their own Scala runtime and can't share Scala objects. So future modules should be linked together into one package, not shipped as separate packages.
- npm trusted publishing (OIDC from GitHub Actions, no long-lived token) can only be set up after the package exists. So the very first version has to be published once by a maintainer.
- Uni versions such as `2026.1.42` are valid semver, so the npm version can equal the git tag.

## Design

Lane: Standard. This is new packaging and release automation (an always-gate area), plus a new public API.

User flow:
- The user runs `npm i @wvlet/uni` and imports `@wvlet/uni/parquet`. Types come with the package, so the editor completes names right away.
- `await openParquet(source)` takes a `Uint8Array`/`ArrayBuffer`, a path (Node), an `http(s)` URL (range reads), or a `Blob`/`File` (browser).
- From the returned file, the user reads `schema`, `numRows`, and key-value metadata. Then:
  - `await file.readRows({ columns })` returns plain objects.
  - `for await (const batch of file.batches({ columns }))` gives column vectors.
- `writeParquet(rows, schema, { compression })` returns a `Uint8Array`, which the user saves or uploads. `schema` is a list of `{ name, type, optional }`.
- Errors are thrown as JS `Error`s with the Parquet message, for example for a corrupt footer, an unknown column, or a type mismatch on write.

Decisions:
- **Package:** a single package `@wvlet/uni` with subpath exports, starting with `./parquet`. Future modules become more subpaths of the same link run, so they share one Scala runtime.
- **Value mapping on read:**
  - INT64 and UINT64 become `bigint`. INT32, UINT32, FLOAT16, FLOAT and DOUBLE become `number`.
  - STRING, ENUM and JSON become `string`, BINARY becomes `Uint8Array`, and UUID becomes a lowercase string.
  - Decimals become strings, so precision isn't lost.
  - DATE becomes a `Date` at UTC midnight, and TIME becomes an `HH:mm:ss.fffffffff` string.
  - Timestamps (all units, and INT96) become `Date` by default, the usual JS choice but limited to milliseconds. The read option `{ timestamps: "bigint" }` instead returns epoch micros or nanos in the column's own unit, without loss.
  - Null becomes `null`. Lists become arrays, and maps and structs become plain objects.
- **Writing:** the writer accepts the same shapes back, and also `bigint` epoch values for timestamp columns.
- **Writer schema:** a list of `{ name, type, optional? }`, where `type` is either:
  - a name: `"boolean" | "int32" | "int64" | "float" | "double" | "string" | "binary" | "json" | "uuid" | "date" | "timestamp-millis" | "timestamp-micros" | "timestamp-nanos"`;
  - or a nested shape: `{ decimal: { precision, scale } }`, `{ list: T }`, `{ map: { key, value } }`, or `{ struct: [columns] }`.
- **Compression on write:** `"snappy"` (the default), `"none"`, `"lz4"`, or `"gzip"` (Node only; browsers can't write GZIP). ZSTD and Brotli files can be read but not written, as on the other platforms.
- **Batches:**
  - Plain physical columns (INT32, INT64, FLOAT, DOUBLE, without a decimal, date or time logical type) come back as copied typed arrays (`Int32Array`, `BigInt64Array`, `Float32Array`, `Float64Array`), together with a `nulls` mask.
  - All other columns come back as arrays mapped the same way as rows.
- **Browser files:** a new `BlobSource` (an `AsyncByteSource` built on `blob.slice().arrayBuffer()`) in uni-parquet's Scala.js sources makes `Blob`/`File` inputs work. Scala.js users get it too.
- **New sbt module `uni-npm`:**
  - Scala.js only, and not published to Maven.
  - The facade lives in package `wvlet.uni.parquet.npm`, so it can read column null masks.
  - The module also holds a hand-written `parquet.d.ts`, and an `npmPackage` task that stages `package.json`, the linked JS, types and a README under `target/npm`.
- **CI:**
  - The JS test job builds the package, then runs a Node smoke test against the staged package: write and read back, read from a path, and read through a local HTTP server.
  - `tsc --noEmit` checks a small TS consumer against the declarations.
  - `typescript` is added to the root pnpm devDependencies, with the lockfile updated, since CI installs with `--frozen-lockfile`.
- **Release:**
  - A new `npm` job in `release.yml` runs only on `v*` tag pushes, never on the Central-copy dispatch (`needs: publish`, `if: github.event_name == 'push'`).
  - It strips the tag's `v` for the npm version, then runs `npm publish --provenance --access public` through trusted publishing (`id-token: write`), with no token in secrets.
  - If the npm step fails after the Maven upload, the recovery is "Re-run failed jobs", since the Maven versions are already in place and can't be overwritten. The ADR records this.
- **One-time setup by the maintainer:**
  - create the `@wvlet` npm org;
  - publish the first version by hand, with a documented command;
  - add this repo's `release.yml` as the trusted publisher.
- **Docs:**
  - Add a "From TypeScript" section to `docs/text/parquet.md` and an npm entry to `for-ai-agents.md`.
  - Following AGENTS.md, also update `plugins/uni/skills/uni/` and bump the plugin version.
  - Add an ADR for the single-package, shared-runtime layout and the release recovery path.
- **Out of scope (follow-ups):**
  - size reductions: splitting codecs and HTTP into lazily loaded chunks, and trimming collections on the read path;
  - the Scala writer silently writing nulls for parsed `JSONValue` rows;
  - the sbt 2 disk cache not relinking `fullLinkJS` after a `scalaJSLinkerConfig` change.

## Alternatives and Why Not?

- **`@wvlet/uni-parquet` as its own package.** Simpler today, but every later module would ship a second Scala runtime and couldn't exchange values with it.
- **Publishing the raw Scala.js output without a facade.** TS users would get Scala collections and an untyped `any` API.
- **Generating `.d.ts` from Scala.** Scala.js has no generator, and a small hand-written file checked by `tsc` in CI is cheaper to keep correct.
- **A long-lived `NPM_TOKEN` secret.** Trusted publishing avoids storing a token and adds provenance. It only costs a one-time manual first publish.

# Parquet in the `uni` module

## Context

`2026-11-20-pure-scala-parquet-reader.md` put the reader in its own module, `uni-parquet`, because its roughly 6k lines were "too much to add to every `uni` user". It grew to about 23k lines with the writer, datasets, and Delta Lake and Iceberg tables, but it has no dependency besides `uni`. Elsewhere in this repo, modules split only to isolate a dependency (`uni-netty` for Netty); `uni` already holds the LLM APIs, object storage, MCP, telemetry, Markdown, and YAML. The Parquet codecs (Snappy, LZ4, Inflate, ZSTD, Brotli) were `private[parquet]` in that module, out of reach of the HTTP client and the rest of `uni`. Plan: `plans/2026-11-22-parquet-into-uni.md`.

## Decision

- `wvlet.uni.parquet` moves into `uni` with its package unchanged, and the `uni-parquet` artifact is removed. Users drop the `uni-parquet` dependency; `uni` is enough.
- Size: the Scala.js and Scala Native linkers drop code an app never reaches, so an app that doesn't read Parquet pays nothing there. On the JVM the `uni` jar grows by about 2.2 MB (7.5 MB before); classes load lazily.
- The Native test binary stays on Immix, `uni`'s default; the Boehm deadlock note from the old module moves to `uni`'s `nativeSettings`.
- Test fixtures move to `uni/src/test/resources/{parquet,codec}` and the generator scripts to `uni/scripts/`. Files the writer creates now say `created_by = "uni"`.
- `uni-npm` and the Parquet benchmark depend on `uni`.

## Consequences

- One artifact for everything Uni does without outside dependencies, which matches how `uni` is documented.
- `uni`'s clean compile and its Native test link get slower; sbt 2's cache keeps incremental builds close to before.
- Next: the codecs move to `wvlet.uni.io.codec` (still internal) so other `uni` code can use them.
- Supersedes the "separate cross-built module" item of `2026-11-20-pure-scala-parquet-reader.md`.

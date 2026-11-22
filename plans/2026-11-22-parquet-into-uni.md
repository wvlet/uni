# Merge uni-parquet into uni

`uni-parquet` has no dependency besides `uni`, and module splits in this repo exist for dependency isolation. Merge it, keep the package.

1. **Merge the module** (this PR): move `uni-parquet` sources, tests, resources, and scripts into `uni`; drop the `parquet` project and the `uni-parquet` artifact; point `uni-npm`, the benchmark, and `docSnippets` at `uni`; update docs, README, AGENTS.md, the skill, and add `adr/2026-11-22-parquet-in-uni.md`.
2. **Codecs to `wvlet.uni.io.codec`**: Snappy, LZ4, Inflate, ZSTD, and Brotli leave `wvlet.uni.parquet.codec`, staying internal (`private[uni]`) until a public API is designed (for example HTTP `Content-Encoding: zstd`/`br`).

Keep `wvlet.uni.parquet` as the package: Parquet is a binary format like `msgpack`. Datasets, Delta Lake, Iceberg, and Avro stay under it; they are reached only through `Parquet.dataset`.

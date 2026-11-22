# @wvlet/uni

[Uni](https://wvlet.org/uni/) libraries for TypeScript and JavaScript, compiled from Scala with
Scala.js. The first module is `@wvlet/uni/parquet`: a pure-JS Apache Parquet reader and writer
built from the same code as uni-parquet on the JVM and Scala Native, so files read and write the
same on every platform. No WebAssembly or native add-on is needed.

```sh
npm install @wvlet/uni
```

```ts
import { openParquet, writeParquet } from "@wvlet/uni/parquet";

const bytes = writeParquet(
  [{ id: 1n, name: "apple", price: 1.5 }],
  [
    { name: "id", type: "int64" },
    { name: "name", type: "string" },
    { name: "price", type: "double" },
  ],
);

const file = await openParquet(bytes); // or a path, an http(s) URL, or a Blob/File
console.log(file.schema, file.numRows);
console.log(await file.readRows({ columns: ["id", "name"] }));

for await (const batch of file.batches()) {
  const price = batch.column("price").values; // Float64Array
}
```

- Reads SNAPPY, GZIP, LZ4, ZSTD, and BROTLI files. Writes SNAPPY (default), LZ4, ZSTD (with
  `compressionLevel` 1-22), uncompressed, and GZIP (Node.js only).
- INT64 values are `bigint`, decimals are strings, timestamps are `Date` by default; pass
  `{ timestamps: "bigint" }` to read them without losing precision. See `parquet.d.ts` for the full
  mapping.
- Datasets: `openDataset(root | glob | files[], { storage })` reads partitioned datasets from a
  local directory (Node.js) or S3, GCS, and Azure, with PyArrow-style filters such as
  `[["dt", ">=", "2026-11-15"]]`; `writeDataset` and `newDatasetWriter` write them. Object-store
  I/O is async, so it works in browsers too; pass short-lived credentials there.
- Browsers: the bundle refers to Node.js built-ins (`os`, `path`, `zlib`) that only run for local
  files and GZIP. Vite stubs them automatically; with webpack, set
  `resolve.fallback: { os: false, path: false, zlib: false }`.

Documentation: https://wvlet.org/uni/text/parquet#from-typescript. Source and issues:
https://github.com/wvlet/uni.

If you need a smaller bundle for the browser, [hyparquet](https://github.com/hyparam/hyparquet) is
a good choice; [parquet-wasm](https://github.com/kylebarron/parquet-wasm) and
[DuckDB-Wasm](https://github.com/duckdb/duckdb-wasm) pair well with Apache Arrow.

Apache License 2.0

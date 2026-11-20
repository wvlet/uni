#!/usr/bin/env bash
# Generates the DuckDB-written test files under src/test/resources/parquet/duckdb.
# Run by hand when the test data needs to change; the outputs are committed so tests never call DuckDB.
# Every value is a function of the row number i, so tests can check values without golden files.
set -euo pipefail
cd "$(dirname "$0")/.."
out=src/test/resources/parquet/duckdb
mkdir -p "${out}"
rm -f "${out}"/*.parquet

query="SELECT
  i::BIGINT AS id,
  (i * 3 - 100)::INTEGER AS i32,
  CASE WHEN i % 7 = 0 THEN NULL ELSE 'name-' || i END AS name,
  (i * 0.5)::DOUBLE AS d,
  (i / 4)::FLOAT AS f,
  i % 3 = 0 AS b,
  DATE '2020-01-01' + i::INTEGER AS dt,
  TIMESTAMP '2020-01-01 00:00:00' + to_seconds(i) AS ts,
  (i * 1.25)::DECIMAL(10, 2) AS dec,
  (i * 1.5)::DECIMAL(30, 3) AS bigdec,
  ['a', 'b', 'c'][i % 3 + 1] AS cat,
  CASE WHEN i % 5 = 0 THEN NULL ELSE list_transform(range(i % 4), lambda x: 't' || x) END AS tags,
  CASE WHEN i % 11 = 0 THEN NULL ELSE {'x': i::INTEGER, 'y': CASE WHEN i % 2 = 0 THEN NULL ELSE 'y' || i END} END AS s,
  CASE WHEN i % 13 = 0 THEN NULL ELSE map(list_transform(range(i % 3), lambda x: 'k' || x), list_transform(range(i % 3), lambda x: (x * i)::INTEGER)) END AS m,
  CASE WHEN i % 4 = 0 THEN NULL ELSE list_transform(range(i % 3), lambda x: [x, x + i]) END AS nested,
  ('00000000-0000-0000-0000-' || lpad(i::VARCHAR, 12, '0'))::UUID AS uuid
FROM range(0, 3000) t(i)"

for codec in uncompressed snappy gzip zstd lz4_raw brotli; do
  for version in V1 V2; do
    duckdb -no-agent -c "COPY (${query}) TO '${out}/all_${codec}_${version}.parquet' (FORMAT parquet, COMPRESSION ${codec}, PARQUET_VERSION ${version}, ROW_GROUP_SIZE 1000)"
  done
done

# Empty file, and a file with no row groups
duckdb -no-agent -c "COPY (SELECT * FROM (${query}) WHERE id < 0) TO '${out}/empty.parquet' (FORMAT parquet)"
ls -la "${out}"

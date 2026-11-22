# DuckDB's Iceberg writer over the benchmark's lineitem table, for comparison with Uni's
# (`run.sh -- --filter iceberg`). DuckDB writes Iceberg only through a catalog, so this needs an
# Iceberg REST catalog at ICEBERG_REST_URI (default http://localhost:8181), for example
# apache/iceberg-rest-fixture. With a local warehouse, set ICEBERG_WAREHOUSE to its folder:
# DuckDB doesn't create a table's metadata/ folder on local disk, so this script does.
#
#   uv run --with duckdb python examples/parquet-bench/duckdb_iceberg.py <data dir> [iterations]
#
# The rows are loaded into a DuckDB table first, so the timings cover the Iceberg write only, as
# Uni's cases do. Each case runs with Snappy (Uni's default) and ZSTD (DuckDB's), and with one
# thread and with DuckDB's default thread count.
import os
import shutil
import statistics
import sys
import time

import duckdb

data = sys.argv[1] if len(sys.argv) > 1 else "examples/parquet-bench/out/data"
iterations = int(sys.argv[2]) if len(sys.argv) > 2 else 5
uri = os.environ.get("ICEBERG_REST_URI", "http://localhost:8181")
warehouse = os.environ.get("ICEBERG_WAREHOUSE")

con = duckdb.connect()
con.execute("INSTALL iceberg; LOAD iceberg;")
con.execute(
    f"ATTACH '' AS cat (TYPE iceberg, ENDPOINT '{uri}', AUTHORIZATION_TYPE 'none')"
)
con.execute("CREATE SCHEMA IF NOT EXISTS cat.bench")
con.execute(
    f"CREATE TABLE lineitem AS SELECT * FROM read_parquet('{data}/lineitem.uncompressed.parquet')"
)
rows = con.execute("SELECT count(*) FROM lineitem").fetchone()[0]
uncompressed_mb = (
    con.execute(
        f"SELECT sum(total_uncompressed_size) FROM parquet_metadata('{data}/lineitem.snappy.parquet')"
    ).fetchone()[0]
    / 1e6
)


def run(name, threads, codec, body, warmup=2):
    con.execute(f"SET threads = {threads}")
    times = []
    for i in range(warmup + iterations):
        con.execute("DROP TABLE IF EXISTS cat.bench.t")
        if warehouse:
            # DROP TABLE leaves the files, so remove the old table's folder
            table = os.path.join(warehouse, "bench", "t")
            if os.path.isdir(table):
                shutil.rmtree(table)
            os.makedirs(os.path.join(table, "metadata"))
        start = time.perf_counter()
        body(codec)
        elapsed = time.perf_counter() - start
        if i >= warmup:
            times.append(elapsed)
    n = con.execute("SELECT count(*) FROM cat.bench.t").fetchone()[0]
    assert n == rows, f"{name}: {n} rows, expected {rows}"
    m = statistics.median(times)
    print(
        f"| lineitem | {name}, {codec}, threads={threads} | {rows} | {uncompressed_mb:.1f} | {m * 1000:.0f} | {rows / m:,.0f} | {uncompressed_mb / m:.1f} |"
    )


def unpartitioned(codec):
    con.execute(
        f"CREATE TABLE cat.bench.t WITH ('write.parquet.compression-codec' = '{codec}') AS SELECT * FROM lineitem"
    )


def partitioned(codec):
    con.execute(
        f"CREATE TABLE cat.bench.t WITH ('write.parquet.compression-codec' = '{codec}') AS SELECT * FROM lineitem LIMIT 0"
    )
    con.execute("ALTER TABLE cat.bench.t SET PARTITIONED BY (l_shipmode)")
    con.execute("INSERT INTO cat.bench.t SELECT * FROM lineitem")


print(f"## DuckDB {duckdb.__version__} Iceberg writes")
print("| file | writer | rows | MB (uncompressed) | median ms | rows/s | MB/s |")
print("|------|--------|-----:|------------------:|----------:|-------:|-----:|")
for threads in [1, os.cpu_count()]:
    for codec in ["snappy", "zstd"]:
        run("iceberg duckdb", threads, codec, unpartitioned)
        run("iceberg duckdb, by l_shipmode", threads, codec, partitioned)

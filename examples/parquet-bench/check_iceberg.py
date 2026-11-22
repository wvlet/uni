# Checks the Iceberg tables the benchmark's `iceberg` cases write against the source lineitem rows,
# with readers other than Uni:
#
#   examples/parquet-bench/run.sh -- --filter iceberg   # or the JVM bench alone
#   uv run --with duckdb --with pyiceberg --with pyarrow --with fastavro \
#     python examples/parquet-bench/check_iceberg.py [data dir]
#
# For each table it checks that
# - DuckDB's iceberg_scan and pyiceberg return exactly the source rows (as a multiset),
# - each manifest entry's record count, partition value, value and null counts, and lower and upper
#   bounds match the data file they describe,
# - pyiceberg prunes a partition filter to that partition's files.
import glob
import json
import os
import struct
import sys

import duckdb
import fastavro
from pyiceberg.table import StaticTable

data = sys.argv[1] if len(sys.argv) > 1 else "examples/parquet-bench/out/data"
tables_dir = os.path.join(data, "..", "write", "iceberg")
source = os.path.join(data, "lineitem.uncompressed.parquet")

con = duckdb.connect()
con.execute("INSTALL iceberg; LOAD iceberg;")

# The case classes write DATE columns as ISO strings and use camelCase names, in source order
cols = [r[0] for r in con.execute(f"DESCRIBE SELECT * FROM '{source}'").fetchall()]
src_exprs = ", ".join(f"CAST({c} AS VARCHAR)" for c in cols)


def row_hashes(relation_sql, names):
    exprs = ", ".join(f"CAST({n} AS VARCHAR)" for n in names)
    return f"SELECT hash({exprs}) AS h, count(*) AS n FROM ({relation_sql}) GROUP BY ALL"


def same_rows(table_sql, names):
    """True when both relations hold the same rows, duplicates included"""
    q = f"""
      WITH a AS ({row_hashes(f"SELECT * FROM '{source}'", cols)}),
           b AS ({row_hashes(table_sql, names)})
      SELECT count(*) FROM a FULL OUTER JOIN b USING (h) WHERE a.n IS DISTINCT FROM b.n
    """
    return con.execute(q).fetchall()[0][0] == 0


def newest_metadata(table):
    files = glob.glob(f"{table}/metadata/v*.metadata.json")
    return max(files, key=lambda p: int(p.split("/v")[-1].split(".")[0]))


def local(path):
    return path[len("file://"):] if path.startswith("file://") else path


def decode_bound(b, tpe):
    if tpe in ("long",):
        return struct.unpack("<q", b)[0]
    if tpe in ("int", "date"):
        return struct.unpack("<i", b)[0]
    if tpe == "string":
        return b.decode("utf-8")
    raise ValueError(tpe)


failures = []


def check(cond, message):
    if not cond:
        failures.append(message)
        print("  FAIL", message)


for name in ["rows", "rows-by", "batch", "batch-by", "batch-zstd", "batch-by-zstd"]:
    table = os.path.join(tables_dir, name)
    if not os.path.isdir(table):
        print(f"{name}: not written, skipped")
        continue
    print(f"{name}:")
    meta_path = newest_metadata(table)
    meta = json.load(open(meta_path))
    schema = next(s for s in meta["schemas"] if s["schema-id"] == meta["current-schema-id"])
    names = [f["name"] for f in schema["fields"]]
    types = {f["id"]: (f["name"], f["type"]) for f in schema["fields"]}

    # Rows, through DuckDB and pyiceberg
    check(same_rows(f"SELECT * FROM iceberg_scan('{table}')", names), f"{name}: DuckDB rows differ")
    t = StaticTable.from_metadata(meta_path)
    arrow = t.scan().to_arrow()
    con.register("py_rows", arrow)
    check(same_rows("SELECT * FROM py_rows", names), f"{name}: pyiceberg rows differ")
    con.unregister("py_rows")

    # Manifest entries against their data files
    snapshot = next(s for s in meta["snapshots"] if s["snapshot-id"] == meta["current-snapshot-id"])
    with open(local(snapshot["manifest-list"]), "rb") as f:
        manifests = list(fastavro.reader(f))
    spec = next(s for s in meta["partition-specs"] if s["spec-id"] == meta["default-spec-id"])
    entries = 0
    for m in manifests:
        with open(local(m["manifest_path"]), "rb") as f:
            for e in fastavro.reader(f):
                entries += 1
                df = e["data_file"]
                path = local(df["file_path"])
                check(os.path.exists(path), f"{name}: {path} is missing")
                n = con.execute(f"SELECT count(*) FROM '{path}'").fetchall()[0][0]
                check(n == df["record_count"], f"{name}: {path} has {n} rows, manifest {df['record_count']}")
                check(os.path.getsize(path) == df["file_size_in_bytes"], f"{name}: {path} size")
                for pf in spec["fields"]:
                    col = types[pf["source-id"]][0]
                    v = df["partition"][pf["name"]]
                    actual = con.execute(f"SELECT DISTINCT {col} FROM '{path}'").fetchall()
                    check(actual == [(v,)], f"{name}: {path} partition {pf['name']}={v}, data {actual}")
                counts = dict((c["key"], c["value"]) for c in df["value_counts"] or [])
                nulls = dict((c["key"], c["value"]) for c in df["null_value_counts"] or [])
                lowers = dict((c["key"], c["value"]) for c in df["lower_bounds"] or [])
                uppers = dict((c["key"], c["value"]) for c in df["upper_bounds"] or [])
                for fid, (col, tpe) in types.items():
                    lo, hi, nn = con.execute(
                        f"SELECT min({col}), max({col}), count(*) - count({col}) FROM '{path}'"
                    ).fetchall()[0]
                    check(counts.get(fid) == n, f"{name}: {col} value count {counts.get(fid)} != {n}")
                    check(nulls.get(fid) == nn, f"{name}: {col} null count {nulls.get(fid)} != {nn}")
                    if fid in lowers:
                        check(decode_bound(lowers[fid], tpe) == lo, f"{name}: {col} lower bound")
                        check(decode_bound(uppers[fid], tpe) == hi, f"{name}: {col} upper bound")
                    elif tpe in ("long", "int", "string", "date"):
                        check(False, f"{name}: {col} has no bounds")
    print(f"  {arrow.num_rows} rows, {len(manifests)} manifest(s), {entries} data file(s)")

    # Partition pruning
    if spec["fields"]:
        col = types[spec["fields"][0]["source-id"]][0]
        planned = list(t.scan(row_filter=f"{col} = 'AIR'").plan_files())
        check(
            len(planned) > 0 and all("=AIR/" in p.file.file_path for p in planned),
            f"{name}: {col} = 'AIR' planned {[p.file.file_path for p in planned]}",
        )
        print(f"  {col} = 'AIR' plans {len(planned)} file(s)")

if failures:
    print(f"{len(failures)} check(s) failed")
    sys.exit(1)
print("All checks passed")

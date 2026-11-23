# Reads the Iceberg tables that IcebergInteropTables writes, with pyiceberg and DuckDB:
#   UNI_ICEBERG_INTEROP_DIR=<dir> ./sbt "uniJVM/testOnly *IcebergInteropTables"
#   uv run --with pyiceberg --with pyarrow --with fastavro --with duckdb python check_uni_tables.py <dir>
import sys, glob, json
import fastavro
from pyiceberg.table import StaticTable
import duckdb

d = sys.argv[1]

def newest(table):
    files = sorted(glob.glob(f"{d}/{table}/metadata/v*.metadata.json"), key=lambda p: int(p.split("/v")[-1].split(".")[0]))
    return files[-1]

for table in ["sales", "nested"]:
    meta = newest(table)
    t = StaticTable.from_metadata(meta)
    rows = t.scan().to_arrow().sort_by("id").to_pylist()
    print(table, "pyiceberg:", rows)
    print(table, "snapshots:", [s.snapshot_id for s in t.snapshots()], "schema:", t.schema())
    if table == "sales":
        pruned = t.scan(row_filter="dt = '2026-11-21'")
        print(table, "pruned files:", [f.file.file_path.split("/")[-2] for f in pruned.plan_files()])
        first = t.snapshots()[0].snapshot_id
        print(table, "time travel:", t.scan(snapshot_id=first).to_arrow().sort_by("id").to_pylist())
    # Avro files parse with fastavro's own reader too
    for p in glob.glob(f"{d}/{table}/metadata/*.avro"):
        with open(p, "rb") as f:
            list(fastavro.reader(f))
    con = duckdb.connect()
    con.execute("INSTALL iceberg; LOAD iceberg;")
    print(table, "duckdb:", con.execute(f"SELECT * FROM iceberg_scan('{d}/{table}') ORDER BY id").fetchall())

# Format version 3 (pyiceberg can't read row lineage yet): DuckDB reads the rows and row IDs
con = duckdb.connect()
con.execute("INSTALL iceberg; LOAD iceberg;")
meta = newest("sales_v3")
print("sales_v3", "format-version:", json.load(open(meta))["format-version"], "next-row-id:", json.load(open(meta))["next-row-id"])
print("sales_v3", "duckdb:", con.execute(f"SELECT * FROM iceberg_scan('{meta}') ORDER BY id").fetchall())

# Merge-on-read deletes: rows 2, 3, and 6 are deleted; DuckDB reads Uni's deletion vectors (v3)
# and position-delete files (v2), and pyiceberg the position deletes
for table in ["v3_dv", "v2_posdel"]:
    meta = newest(table)
    print(table, "duckdb:", con.execute(f"SELECT id FROM iceberg_scan('{meta}') ORDER BY id").fetchall())
print("v2_posdel", "pyiceberg:", [r["id"] for r in StaticTable.from_metadata(newest("v2_posdel")).scan().to_arrow().sort_by("id").to_pylist()])
for p in glob.glob(f"{d}/v*_*/metadata/*.avro"):
    with open(p, "rb") as f:
        list(fastavro.reader(f))

# Merge-on-read updates: rows 2 and 3 read ITEM2 and ITEM3, and row 4 is in dt 2026-11-30, once
# each; DuckDB reads Uni's updated v3 table with the row IDs the rows had before
for table in ["v3_update", "v2_update"]:
    meta = newest(table)
    print(table, "duckdb:", con.execute(f"SELECT id, item, dt FROM iceberg_scan('{meta}') ORDER BY id").fetchall())
print("v3_update", "duckdb row ids:", con.execute(f"SELECT id, _row_id FROM iceberg_scan('{newest('v3_update')}') ORDER BY id").fetchall())
print("v2_update", "pyiceberg:", [(r["id"], r["item"]) for r in StaticTable.from_metadata(newest("v2_update")).scan().to_arrow().sort_by("id").to_pylist()])
# Compaction: the same rows as v3_dv and v2_posdel, with no delete files left; DuckDB reads the
# v3 table's row IDs, which compaction keeps
for table in ["v3_compacted", "v2_compacted"]:
    meta = newest(table)
    print(table, "duckdb:", con.execute(f"SELECT id FROM iceberg_scan('{meta}') ORDER BY id").fetchall())
print("v3_compacted", "row ids:", con.execute(f"SELECT id, _row_id FROM iceberg_scan('{newest('v3_compacted')}') ORDER BY id").fetchall())
print("v3_dv", "row ids:", con.execute(f"SELECT id, _row_id FROM iceberg_scan('{newest('v3_dv')}') ORDER BY id").fetchall())
print("v2_compacted", "pyiceberg:", [r["id"] for r in StaticTable.from_metadata(newest("v2_compacted")).scan().to_arrow().sort_by("id").to_pylist()])

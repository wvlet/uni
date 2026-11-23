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

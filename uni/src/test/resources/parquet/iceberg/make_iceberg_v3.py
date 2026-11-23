# Writes the format version 3 Iceberg fixtures with DuckDB, which writes Iceberg only through a REST
# catalog. pyiceberg can't write v3 yet. Start Iceberg's REST catalog fixture on port 8181 with its
# warehouse at /tmp/uni-iceberg-v3 (so the recorded locations don't depend on the checkout), on
# JDK 21:
#   CATALOG_WAREHOUSE=file:///tmp/uni-iceberg-v3 CATALOG_URI=jdbc:sqlite:file:/tmp/uni-iceberg-v3.db \
#     REST_PORT=8181 java -cp <iceberg-open-api 1.12.0 test-fixtures, iceberg-core, sqlite-jdbc, jetty 12.1, hadoop> \
#     org.apache.iceberg.rest.RESTCatalogServer
# then run: uv run --with duckdb --with fastavro --with pyarrow python make_iceberg_v3.py <out>
import sys, os, shutil, glob
import duckdb
import fastavro

out = sys.argv[1]
os.makedirs(out, exist_ok=False)
wh = "/tmp/uni-iceberg-v3"
con = duckdb.connect()
con.execute("INSTALL iceberg; LOAD iceberg;")
con.execute("ATTACH '' AS cat (TYPE iceberg, ENDPOINT 'http://localhost:8181', AUTHORIZATION_TYPE 'none')")
con.execute("CREATE SCHEMA IF NOT EXISTS cat.v3")

def table(name, columns, version=3):
    con.execute(f"DROP TABLE IF EXISTS cat.v3.{name}")
    # DuckDB doesn't create a local table's metadata/ folder, and DROP TABLE leaves the files
    shutil.rmtree(os.path.join(wh, "v3", name), ignore_errors=True)
    os.makedirs(os.path.join(wh, "v3", name, "metadata"))
    con.execute(f"CREATE TABLE cat.v3.{name} ({columns}) WITH ('format-version' = '{version}', 'write.parquet.compression-codec' = 'snappy')")

# v3: nanosecond timestamps, columns added with defaults after the first file, then an identity
# partition on country
table("v3", "id BIGINT, ts TIMESTAMP_NS, tz TIMESTAMPTZ, country VARCHAR")
con.execute("INSERT INTO cat.v3.v3 VALUES (1, '2026-11-22 01:02:03.123456789', '2026-11-22 01:02:03+00', 'JP'), (2, NULL, NULL, 'US')")
con.execute("ALTER TABLE cat.v3.v3 ADD COLUMN n INTEGER DEFAULT 7")
con.execute("ALTER TABLE cat.v3.v3 ADD COLUMN s VARCHAR DEFAULT 'x'")
con.execute("ALTER TABLE cat.v3.v3 SET PARTITIONED BY (country)")
con.execute("INSERT INTO cat.v3.v3 VALUES (3, '1969-12-31 23:59:59.999999999', NULL, 'JP', 8, 'y'), (4, NULL, NULL, 'US', NULL, NULL)")

# v3_deletes: a deletion vector (Puffin) over the first data file
table("v3_deletes", "id BIGINT, name VARCHAR")
con.execute("INSERT INTO cat.v3.v3_deletes SELECT i, 'n' || i FROM range(10) t(i)")
con.execute("DELETE FROM cat.v3.v3_deletes WHERE id % 3 = 0")

# v3_dv_large: a deletion vector over a larger file, with bitmap and array containers
# Row i holds a = i / 1000 and b = i % 1000, which compress well; it deletes the even rows below
# 60000 , rows 70000 to 100000, and rows 130000 and 139999
table("v3_dv_large", "a SMALLINT, b SMALLINT")
con.execute("INSERT INTO cat.v3.v3_dv_large SELECT i // 1000, i % 1000 FROM range(140000) t(i)")
con.execute("DELETE FROM cat.v3.v3_dv_large WHERE (a < 60 AND b % 2 = 0) OR (a BETWEEN 70 AND 99) OR (a = 100 AND b = 0) OR (a = 130 AND b = 0) OR (a = 139 AND b = 999)")

# v2_deletes: Parquet position-delete files, from two deletes
table("v2_deletes", "id BIGINT, name VARCHAR", version=2)
con.execute("INSERT INTO cat.v3.v2_deletes SELECT i, 'n' || i FROM range(10) t(i)")
con.execute("INSERT INTO cat.v3.v2_deletes SELECT i, 'n' || i FROM range(10, 20) t(i)")
con.execute("DELETE FROM cat.v3.v2_deletes WHERE id % 4 = 1")
con.execute("DELETE FROM cat.v3.v2_deletes WHERE id = 12")

# v3_variant: a variant column, shredded (write.parquet.shred-variants) into an object with typed
# fields, a field kept in the binary value, and rows that aren't objects
table("v3_variant", "id BIGINT, v VARIANT")
con.execute("ALTER TABLE cat.v3.v3_variant SET ('write.parquet.shred-variants' = 'true')")
con.execute("""INSERT INTO cat.v3.v3_variant VALUES
  (1, {'n': 1, 's': 'a'}::VARIANT),
  (2, {'n': 2, 's': 'b', 'tags': ['x', 'y']}::VARIANT),
  (3, 'plain'::VARIANT),
  (4, NULL)""")

# v3_geo: a geometry column, which holds WKB. DuckDB has no GEOGRAPHY type to write.
table("v3_geo", "id BIGINT, g GEOMETRY")
con.execute("INSERT INTO cat.v3.v3_geo VALUES (1, 'POINT(1 2)'::GEOMETRY), (2, NULL), (3, 'LINESTRING(0 0, 1 1)'::GEOMETRY)")

# equality_deletes: a v2 table partitioned by country (spec 1; spec 0 is unpartitioned), with two
# data commits and a position delete from DuckDB. DuckDB never writes equality deletes, so a
# snapshot with them is added below
table("equality_deletes", "id BIGINT, name VARCHAR, country VARCHAR", version=2)
con.execute("ALTER TABLE cat.v3.equality_deletes SET PARTITIONED BY (country)")
con.execute("INSERT INTO cat.v3.equality_deletes VALUES (1, 'a', 'JP'), (2, 'b', 'JP'), (3, NULL, 'JP'), (4, 'd', 'US'), (5, 'e', 'US'), (6, NULL, 'US'), (7, 'g', 'US')")
con.execute("INSERT INTO cat.v3.equality_deletes VALUES (4, 'd', 'US'), (8, 'a', 'JP')")
con.execute("DELETE FROM cat.v3.equality_deletes WHERE id = 7")

for name in ["v3", "v3_deletes", "v3_dv_large", "v2_deletes", "v3_variant", "v3_geo", "equality_deletes"]:
    shutil.copytree(os.path.join(wh, "v3", name), os.path.join(out, name), ignore=shutil.ignore_patterns("*.crc"))

# equality_deletes, continued: a snapshot (sequence number 4) with two equality delete files, written
# with pyarrow (Parquet columns carry the Iceberg field IDs) and listed by new delete manifests
import json, uuid
import pyarrow as pa
import pyarrow.parquet as pq

t = os.path.join(out, "equality_deletes")
loc = "file:///tmp/uni-iceberg-v3/v3/equality_deletes"
metas = sorted(glob.glob(os.path.join(t, "metadata", "*.metadata.json")))
meta = json.load(open(metas[-1]))
parent = meta["current-snapshot-id"]
snap = next(s for s in meta["snapshots"] if s["snapshot-id"] == parent)
seq = meta["last-sequence-number"] + 1
snapshot_id = 4000000000000000004

def field(name, type, id):
    return pa.field(name, type, metadata={b"PARQUET:field_id": str(id).encode()})

def delete_file(rel, columns, rows):
    path = os.path.join(t, rel)
    pq.write_table(pa.Table.from_pylist(rows, schema=pa.schema(columns)), path, compression="snappy")
    return f"{loc}/{rel}", len(rows), os.path.getsize(path)

# Partitioned (spec 1, country = JP) on name: deletes 'a' and null names in JP, not the null in US
d1 = delete_file("data/country=JP/eq-name-deletes.parquet", [field("name", pa.string(), 2)], [{"name": "a"}, {"name": None}])
# Unpartitioned (spec 0) on (id, name), with data sequence number 2: deletes (4, 'd') of the first
# commit (sequence number 1) but not the second commit's copy (2, not below 2); (5, 'x') and
# (2, null) match no row
d2 = delete_file("data/eq-id-name-deletes.parquet", [field("id", pa.int64(), 1), field("name", pa.string(), 2)], [{"id": 4, "name": "d"}, {"id": 5, "name": "x"}, {"id": 2, "name": None}])

template = next(p for p in glob.glob(os.path.join(t, "metadata", "*-m0.avro")) if fastavro.reader(open(p, "rb")).metadata.get("content") == "deletes")
r = fastavro.reader(open(template, "rb"))
schema, tmeta = r.writer_schema, {k: v for k, v in r.metadata.items() if not k.startswith("avro.")}

def unpartitioned(schema):
    s = json.loads(json.dumps(schema))
    data_file = next(f for f in s["fields"] if f["name"] == "data_file")
    part = next(f for f in data_file["type"]["fields"] if f["name"] == "partition")
    part["type"]["fields"] = []
    return s

def delete_manifest(name, spec_id, partition, file, equality_ids, data_seq, schema, partitions):
    path, count, size = file
    entry = {
        "status": 1, "snapshot_id": snapshot_id, "sequence_number": data_seq, "file_sequence_number": data_seq,
        "data_file": {
            "content": 2, "file_path": path, "file_format": "parquet", "partition": partition,
            "record_count": count, "file_size_in_bytes": size, "column_sizes": [], "value_counts": [],
            "null_value_counts": [], "nan_value_counts": [], "lower_bounds": [], "upper_bounds": [],
            "split_offsets": None, "equality_ids": equality_ids, "sort_order_id": None, "referenced_data_file": None,
        },
    }
    m = dict(tmeta, **{"partition-spec-id": str(spec_id), "partition-spec": json.dumps(next(s["fields"] for s in meta["partition-specs"] if s["spec-id"] == spec_id))})
    p = os.path.join(t, "metadata", name)
    with open(p, "wb") as f:
        fastavro.writer(f, schema, [entry], codec="deflate", metadata=m)
    return {
        "manifest_path": f"{loc}/metadata/{name}", "manifest_length": os.path.getsize(p), "partition_spec_id": spec_id,
        "added_snapshot_id": snapshot_id, "added_files_count": 1, "existing_files_count": 0, "deleted_files_count": 0,
        "added_rows_count": count, "existing_rows_count": 0, "deleted_rows_count": 0, "partitions": partitions,
        "content": 1, "sequence_number": seq, "min_sequence_number": data_seq or seq,
    }

m1 = delete_manifest("eq-name-m0.avro", 1, {"country": "JP"}, d1, [2], None, schema, [{"contains_null": False, "contains_nan": False, "lower_bound": b"JP", "upper_bound": b"JP"}])
m2 = delete_manifest("eq-id-name-m0.avro", 0, {}, d2, [1, 2], 2, unpartitioned(schema), [])

r = fastavro.reader(open(os.path.join(t, "metadata", snap["manifest-list"].split("/")[-1]), "rb"))
list_name = f"snap-{snapshot_id}-eq.avro"
with open(os.path.join(t, "metadata", list_name), "wb") as f:
    fastavro.writer(f, r.writer_schema, list(r) + [m1, m2], codec="deflate")

ts = snap["timestamp-ms"] + 1
meta["snapshots"].append({
    "sequence-number": seq, "snapshot-id": snapshot_id, "parent-snapshot-id": parent, "timestamp-ms": ts,
    "summary": {"operation": "overwrite", "added-equality-delete-files": "2", "added-equality-deletes": "5"},
    "manifest-list": f"{loc}/metadata/{list_name}", "schema-id": meta["current-schema-id"],
})
meta["metadata-log"] = meta.get("metadata-log", []) + [{"timestamp-ms": meta["last-updated-ms"], "metadata-file": f"{loc}/metadata/{os.path.basename(metas[-1])}"}]
meta.update({"last-sequence-number": seq, "last-updated-ms": ts, "current-snapshot-id": snapshot_id, "refs": {"main": {"snapshot-id": snapshot_id, "type": "branch"}}})
meta["snapshot-log"].append({"timestamp-ms": ts, "snapshot-id": snapshot_id})
with open(os.path.join(t, "metadata", f"{len(metas):05d}-{uuid.uuid4()}.metadata.json"), "w") as f:
    json.dump(meta, f, indent=2)

# Writes the format version 3 Iceberg fixtures with DuckDB, which writes Iceberg only through a REST
# catalog. pyiceberg can't write v3 yet. Start Iceberg's REST catalog fixture on port 8181 with its
# warehouse at /tmp/uni-iceberg-v3 (so the recorded locations don't depend on the checkout), on
# JDK 21:
#   CATALOG_WAREHOUSE=file:///tmp/uni-iceberg-v3 CATALOG_URI=jdbc:sqlite:file:/tmp/uni-iceberg-v3.db \
#     REST_PORT=8181 java -cp <iceberg-open-api 1.12.0 test-fixtures, iceberg-core, sqlite-jdbc, jetty 12.1, hadoop> \
#     org.apache.iceberg.rest.RESTCatalogServer
# then run: uv run --with duckdb --with fastavro python make_iceberg_v3.py <out>
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

# v3_variant: a variant column
table("v3_variant", "id BIGINT, v VARIANT")
con.execute("INSERT INTO cat.v3.v3_variant VALUES (1, 1::VARIANT), (2, 'a'::VARIANT)")

for name in ["v3", "v3_deletes", "v3_dv_large", "v2_deletes", "v3_variant"]:
    shutil.copytree(os.path.join(wh, "v3", name), os.path.join(out, name), ignore=shutil.ignore_patterns("*.crc"))

# equality_deletes: a copy of v2_deletes whose delete files are marked as equality deletes on id,
# which DuckDB doesn't write
shutil.copytree(os.path.join(out, "v2_deletes"), os.path.join(out, "equality_deletes"))
for p in glob.glob(os.path.join(out, "equality_deletes", "metadata", "*-m*.avro")):
    with open(p, "rb") as f:
        r = fastavro.reader(f)
        schema, meta, entries = r.writer_schema, r.metadata, list(r)
    if meta.get("content") != "deletes":
        continue
    for e in entries:
        e["data_file"]["content"] = 2
        e["data_file"]["equality_ids"] = [1]
    with open(p, "wb") as f:
        fastavro.writer(f, schema, entries, codec="deflate", metadata={k: v for k, v in meta.items() if not k.startswith("avro.")})

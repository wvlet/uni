# Writes the format version 3 Iceberg fixtures with DuckDB, which writes Iceberg only through a REST
# catalog. pyiceberg can't write v3 yet. Start Iceberg's REST catalog fixture on port 8181 with its
# warehouse at /tmp/uni-iceberg-v3 (so the recorded locations don't depend on the checkout), on
# JDK 21:
#   CATALOG_WAREHOUSE=file:///tmp/uni-iceberg-v3 CATALOG_URI=jdbc:sqlite:file:/tmp/uni-iceberg-v3.db \
#     REST_PORT=8181 java -cp <iceberg-open-api 1.12.0 test-fixtures, iceberg-core, sqlite-jdbc, jetty 12.1, hadoop> \
#     org.apache.iceberg.rest.RESTCatalogServer
# then run: uv run --with duckdb python make_iceberg_v3.py <out>
import sys, os, shutil
import duckdb

out = sys.argv[1]
os.makedirs(out, exist_ok=False)
wh = "/tmp/uni-iceberg-v3"
con = duckdb.connect()
con.execute("INSTALL iceberg; LOAD iceberg;")
con.execute("ATTACH '' AS cat (TYPE iceberg, ENDPOINT 'http://localhost:8181', AUTHORIZATION_TYPE 'none')")
con.execute("CREATE SCHEMA IF NOT EXISTS cat.v3")

def table(name, columns):
    con.execute(f"DROP TABLE IF EXISTS cat.v3.{name}")
    # DuckDB doesn't create a local table's metadata/ folder, and DROP TABLE leaves the files
    shutil.rmtree(os.path.join(wh, "v3", name), ignore_errors=True)
    os.makedirs(os.path.join(wh, "v3", name, "metadata"))
    con.execute(f"CREATE TABLE cat.v3.{name} ({columns}) WITH ('format-version' = '3', 'write.parquet.compression-codec' = 'snappy')")

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

# v3_variant: a variant column
table("v3_variant", "id BIGINT, v VARIANT")
con.execute("INSERT INTO cat.v3.v3_variant VALUES (1, 1::VARIANT), (2, 'a'::VARIANT)")

for name in ["v3", "v3_deletes", "v3_variant"]:
    shutil.copytree(os.path.join(wh, "v3", name), os.path.join(out, name), ignore=shutil.ignore_patterns("*.crc"))

# Writes the Iceberg fixtures with gzip-compressed metadata files. Start Iceberg's REST catalog
# fixture as make_iceberg_v3.py describes, then run:
#   uv run --with duckdb python make_iceberg_gzip.py <out>
#
# gzip_catalog: DuckDB commits through the catalog with write.metadata.compression-codec = gzip, so
# iceberg-java names the metadata files NNNNN-<uuid>.gz.metadata.json.
# gzip_hadoop: the same table as a Hadoop table, with vN.gz.metadata.json files, a legacy
# vN.metadata.json.gz file for the newest version, and a version hint naming an older version.
import sys, os, shutil, glob, gzip, json
import duckdb

out = sys.argv[1]
os.makedirs(out, exist_ok=False)
wh = "/tmp/uni-iceberg-v3"
con = duckdb.connect()
con.execute("INSTALL iceberg; LOAD iceberg;")
con.execute("ATTACH '' AS cat (TYPE iceberg, ENDPOINT 'http://localhost:8181', AUTHORIZATION_TYPE 'none')")
con.execute("CREATE SCHEMA IF NOT EXISTS cat.gz")

name = "gzip_catalog"
con.execute(f"DROP TABLE IF EXISTS cat.gz.{name}")
shutil.rmtree(os.path.join(wh, "gz", name), ignore_errors=True)
os.makedirs(os.path.join(wh, "gz", name, "metadata"))
con.execute(f"CREATE TABLE cat.gz.{name} (id BIGINT, name VARCHAR) WITH ('format-version' = '2', 'write.parquet.compression-codec' = 'snappy', 'write.metadata.compression-codec' = 'gzip')")
con.execute(f"INSERT INTO cat.gz.{name} VALUES (1, 'a'), (2, 'b')")
con.execute(f"INSERT INTO cat.gz.{name} VALUES (3, 'c')")
shutil.copytree(os.path.join(wh, "gz", name), os.path.join(out, name), ignore=shutil.ignore_patterns("*.crc"))

catalog = os.path.join(out, name)
hadoop = os.path.join(out, "gzip_hadoop")
shutil.copytree(catalog, hadoop)
metas = sorted(glob.glob(os.path.join(hadoop, "metadata", "*.gz.metadata.json")))
for v, path in enumerate(metas, start=1):
    meta = json.load(gzip.open(path))
    meta["metadata-log"] = []
    newest = v == len(metas)
    target = f"v{v}.metadata.json.gz" if newest else f"v{v}.gz.metadata.json"
    with gzip.open(os.path.join(hadoop, "metadata", target), "wt") as f:
        json.dump(meta, f)
    os.remove(path)
# A writer updates the hint after it commits, so readers roll forward to the newest version
with open(os.path.join(hadoop, "metadata", "version-hint.text"), "w") as f:
    f.write(str(len(metas) - 1))

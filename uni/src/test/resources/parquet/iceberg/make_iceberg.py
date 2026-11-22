# Writes the Iceberg fixtures: uv run --with pyiceberg --with pyarrow --with sqlalchemy --with fastavro --with cramjam --with backports.zstd python make_iceberg.py <out>
# Tables are written under /tmp/uni-iceberg-warehouse, so the recorded locations don't depend on the
# checkout, then copied to <out>; reading them there exercises the moved-table rule.
import sys, os, shutil, datetime
import pyarrow as pa
import fastavro
from pyiceberg.catalog.sql import SqlCatalog
from pyiceberg.schema import Schema
from pyiceberg.types import NestedField, LongType, IntegerType, StringType, DateType, ListType, MapType, StructType, DoubleType
from pyiceberg.partitioning import PartitionSpec, PartitionField
from pyiceberg.transforms import IdentityTransform

out = sys.argv[1]
os.makedirs(out, exist_ok=False)
wh = "/tmp/uni-iceberg-warehouse"
shutil.rmtree(wh, ignore_errors=True)
os.makedirs(wh)
cat = SqlCatalog("fixtures", uri=f"sqlite:///{wh}/catalog.db", warehouse=f"file://{wh}")
cat.create_namespace("db")

# events: identity partitions on a date and a string, two appends, then a copy-on-write delete
schema = Schema(
    NestedField(1, "id", LongType(), required=False),
    NestedField(2, "name", StringType(), required=False),
    NestedField(3, "dt", DateType(), required=False),
    NestedField(4, "country", StringType(), required=False),
)
spec = PartitionSpec(PartitionField(3, 1000, IdentityTransform(), "dt"), PartitionField(4, 1001, IdentityTransform(), "country"))
arrow = pa.schema([("id", pa.int64()), ("name", pa.string()), ("dt", pa.date32()), ("country", pa.string())])
def batch(ids, dt, country):
    return pa.table({"id": ids, "name": [f"n{i}" for i in ids], "dt": [dt] * len(ids), "country": [country] * len(ids)}, schema=arrow)
t = cat.create_table("db.events", schema=schema, partition_spec=spec)
t.append(batch([1, 2, 3], datetime.date(2026, 11, 19), "JP"))
t.append(batch([4, 5], datetime.date(2026, 11, 20), "US"))
t.append(batch([6], None, "JP"))
t.delete("id = 2")

# evolved: an int promoted to long, a column added, a column dropped
t = cat.create_table("db.evolved", schema=Schema(
    NestedField(1, "id", LongType(), required=False),
    NestedField(2, "n", IntegerType(), required=False),
    NestedField(3, "gone", StringType(), required=False),
))
t.append(pa.table({"id": pa.array([1], pa.int64()), "n": pa.array([10], pa.int32()), "gone": ["x"]}))
with t.update_schema() as u:
    u.update_column("n", LongType())
    u.add_column("added", StringType())
    u.delete_column("gone")
t.append(pa.table({"id": pa.array([2], pa.int64()), "n": pa.array([20], pa.int64()), "added": ["y"]}))

# renamed: a rename, which the dataset refuses
t = cat.create_table("db.renamed", schema=Schema(NestedField(1, "id", LongType(), required=False), NestedField(2, "old", StringType(), required=False)))
t.append(pa.table({"id": pa.array([1], pa.int64()), "old": ["a"]}))
with t.update_schema() as u:
    u.rename_column("old", "new")

# respec: country was an identity partition, then the spec dropped it
t = cat.create_table("db.respec", schema=Schema(NestedField(1, "id", LongType(), required=False), NestedField(2, "country", StringType(), required=False)),
                     partition_spec=PartitionSpec(PartitionField(2, 1000, IdentityTransform(), "country")))
t.append(pa.table({"id": pa.array([1], pa.int64()), "country": ["JP"]}))
with t.update_spec() as u:
    u.remove_field("country")
t.append(pa.table({"id": pa.array([2], pa.int64()), "country": ["US"]}))

# v1: format version 1, partitioned
t = cat.create_table("db.v1", schema=Schema(NestedField(1, "id", LongType(), required=False), NestedField(2, "country", StringType(), required=False)),
                     partition_spec=PartitionSpec(PartitionField(2, 1000, IdentityTransform(), "country")), properties={"format-version": "1"})
t.append(pa.table({"id": pa.array([1, 2], pa.int64()), "country": ["JP", "US"]}))

# empty: nested types and no snapshot
cat.create_table("db.empty", schema=Schema(
    NestedField(1, "id", LongType(), required=True),
    NestedField(2, "tags", ListType(5, StringType(), element_required=False), required=False),
    NestedField(3, "attrs", MapType(6, StringType(), 7, IntegerType(), value_required=False), required=False),
    NestedField(4, "point", StructType(NestedField(8, "x", DoubleType(), required=False)), required=False),
))

# leading: the identity partition column comes first in the schema
t = cat.create_table("db.leading", schema=Schema(NestedField(1, "dt", DateType(), required=False), NestedField(2, "id", LongType(), required=False)),
                     partition_spec=PartitionSpec(PartitionField(1, 1000, IdentityTransform(), "dt")))
t.append(pa.table({"dt": pa.array([datetime.date(2026, 11, 21)], pa.date32()), "id": pa.array([1], pa.int64())}))

for name in ["events", "evolved", "renamed", "respec", "v1", "empty", "leading"]:
    shutil.copytree(os.path.join(wh, "db", name), os.path.join(out, name))

# deletes and orc: copies of v1-style one-file tables whose current snapshot is edited by hand,
# adding a live delete manifest to the manifest list, or marking the data file as ORC
def edited(name, edit_list, edit_manifest):
    t = cat.create_table(f"db.{name}", schema=Schema(NestedField(1, "id", LongType(), required=False)))
    t.append(pa.table({"id": pa.array([1], pa.int64())}))
    t = cat.load_table(f"db.{name}")
    list_path = t.current_snapshot().manifest_list.removeprefix("file://")
    with open(list_path, "rb") as f:
        r = fastavro.reader(f)
        schema, meta, entries = r.writer_schema, r.metadata, list(r)
    for e in entries:
        with open(e["manifest_path"].removeprefix("file://"), "rb") as f:
            r = fastavro.reader(f)
            m_schema, m_meta, m_entries = r.writer_schema, r.metadata, list(r)
        edit_manifest(m_entries)
        with open(e["manifest_path"].removeprefix("file://"), "wb") as f:
            fastavro.writer(f, m_schema, m_entries, codec="deflate", metadata={k: v for k, v in m_meta.items() if not k.startswith("avro.")})
    edit_list(entries)
    with open(list_path, "wb") as f:
        fastavro.writer(f, schema, entries, codec="deflate", metadata={k: v for k, v in meta.items() if not k.startswith("avro.")})
    shutil.copytree(os.path.join(wh, "db", name), os.path.join(out, name))
def add_delete_manifest(entries):
    entries.append(dict(entries[0], content=1))
def to_orc(entries):
    for e in entries:
        e["data_file"]["file_format"] = "ORC"
edited("deletes", add_delete_manifest, lambda es: None)
edited("orc", lambda es: None, to_orc)

# Avro container files in each codec, for the Avro reader
avro_schema = {"type": "record", "name": "r", "fields": [
    {"name": "s", "type": "string"}, {"name": "l", "type": ["null", "long"]},
    {"name": "a", "type": {"type": "array", "items": "int"}}, {"name": "m", "type": {"type": "map", "values": "double"}},
    {"name": "e", "type": {"type": "enum", "name": "color", "symbols": ["RED", "GREEN"]}},
    {"name": "f", "type": {"type": "fixed", "name": "f2", "size": 2}}, {"name": "b", "type": "boolean"},
    {"name": "x", "type": "float"}, {"name": "y", "type": "bytes"},
    {"name": "next", "type": ["null", "r"]},
]}
records = [{"s": f"row{i}" * (i % 7 + 1), "l": None if i % 3 == 0 else -i * 1000003, "a": list(range(i % 5)), "m": {"k": i / 2},
            "e": "GREEN" if i % 2 else "RED", "f": bytes([i % 256, 7]), "b": i % 2 == 0, "x": 1.5, "y": b"\x00\xff",
            "next": {"s": "inner", "l": 1, "a": [], "m": {}, "e": "RED", "f": b"ab", "b": True, "x": 0.0, "y": b"", "next": None} if i == 1 else None}
           for i in range(2000)]
os.makedirs(os.path.join(out, "avro"))
for codec in ["null", "deflate", "snappy", "zstandard"]:
    with open(os.path.join(out, "avro", f"{codec}.avro"), "wb") as f:
        fastavro.writer(f, avro_schema, records, codec=codec, sync_interval=16000)

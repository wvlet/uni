import sys, os, datetime
import pyarrow as pa
from deltalake import write_deltalake, DeltaTable
out = sys.argv[1]
os.makedirs(out, exist_ok=False)

# partitioned: date + string partitions, append, append, delete
p = os.path.join(out, "partitioned")
schema = pa.schema([("id", pa.int64()), ("name", pa.string()), ("dt", pa.date32()), ("country", pa.string())])
def batch(ids, dt, country):
    return pa.table({"id": pa.array(ids, pa.int64()), "name": [f"n{i}" for i in ids],
                     "dt": pa.array([dt]*len(ids), pa.date32()), "country": [country]*len(ids)}, schema=schema)
write_deltalake(p, batch([1,2,3], datetime.date(2026,11,19), "JP"), partition_by=["dt","country"])
write_deltalake(p, batch([4,5], datetime.date(2026,11,20), "US"), partition_by=["dt","country"], mode="append")
write_deltalake(p, pa.table({"id": pa.array([6], pa.int64()), "name": ["n6"], "dt": pa.array([None], pa.date32()), "country": ["JP"]}, schema=schema), partition_by=["dt","country"], mode="append")
DeltaTable(p).delete("id = 2")

# checkpoint: 3 appends, checkpoint at v2, then one more append
c = os.path.join(out, "checkpoint")
cs = pa.schema([("id", pa.int32()), ("v", pa.float64())])
for k in range(3):
    write_deltalake(c, pa.table({"id": pa.array([k], pa.int32()), "v": [k*1.5]}, schema=cs), mode="append")
DeltaTable(c).create_checkpoint()
write_deltalake(c, pa.table({"id": pa.array([3], pa.int32()), "v": [4.5]}, schema=cs), mode="append")
write_deltalake(c, pa.table({"id": pa.array([10], pa.int32()), "v": [0.0]}, schema=cs), mode="overwrite")

# empty
e = os.path.join(out, "empty")
DeltaTable.create(e, schema=pa.schema([("id", pa.int64()), ("tags", pa.list_(pa.string())), ("attrs", pa.map_(pa.string(), pa.int32())), ("pt", pa.int32())]), partition_by=["pt"])

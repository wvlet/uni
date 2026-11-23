# Reads the Arrow samples that WriteArrowSamples writes with pyarrow, and checks every value
# against the rows uni's test reader decoded (the .json file next to each sample):
#   ./sbt "uniJVM/Test/runMain wvlet.uni.arrow.WriteArrowSamples target/arrow-samples"
#   uv run --with pyarrow python uni/src/test/resources/arrow/check_with_pyarrow.py target/arrow-samples
import decimal
import json
import math
import sys

import pyarrow as pa
import pyarrow.ipc as ipc

d = sys.argv[1]


def norm(v, t):
    """A pyarrow value in the JSON form WriteArrowSamples writes"""
    if v is None:
        return None
    if isinstance(t, pa.BaseExtensionType):
        t = t.storage_type
        if hasattr(v, "bytes"):  # uuid.UUID
            v = v.bytes
    if pa.types.is_list(t):
        return [norm(x, t.value_type) for x in v]
    if pa.types.is_map(t):
        return {str(k): norm(x, t.item_type) for k, x in v}
    if pa.types.is_struct(t):
        return {f.name: norm(v[f.name], f.type) for f in t}
    if pa.types.is_boolean(t):
        return v
    if pa.types.is_integer(t):
        return str(v)
    if pa.types.is_floating(t):
        return float(v)
    if pa.types.is_string(t):
        return v
    if pa.types.is_binary(t) or pa.types.is_fixed_size_binary(t):
        return "0x" + bytes(v).hex().upper()
    if pa.types.is_decimal(t):
        return str(int(v.scaleb(t.scale, decimal.Context(prec=100))))
    if pa.types.is_interval(t):
        return {"months": v.months, "days": v.days, "nanoseconds": v.nanoseconds}
    raise TypeError(f"no normal form for {t}")


def storage(col):
    """Temporal columns as their integer storage, so values compare exactly"""
    t = col.type
    if pa.types.is_date32(t) or pa.types.is_time32(t):
        return col.cast(pa.int32())
    if pa.types.is_time64(t) or pa.types.is_timestamp(t):
        return col.cast(pa.int64())
    return col


def same(a, b):
    if isinstance(a, float) and isinstance(b, float):
        return a == b or (math.isnan(a) and math.isnan(b))
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(same(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b


def check(name, table):
    expected = json.load(open(f"{d}/{name}.json"))
    cols = {f.name: storage(table.column(f.name)) for f in table.schema}
    rows = [
        {k: norm(c[i].as_py(), c.type) for k, c in cols.items()}
        for i in range(table.num_rows)
    ]
    assert len(rows) == len(expected), f"{name}: {len(rows)} rows, expected {len(expected)}"
    for i, (a, e) in enumerate(zip(rows, expected)):
        for k in e:
            if not same(a[k], e[k]):
                raise AssertionError(f"{name} row {i} {k}: pyarrow {a[k]!r}, uni {e[k]!r}")
    # Full validation of offsets, bitmaps, and buffer sizes
    for batch in table.to_batches():
        batch.validate(full=True)
    print(f"{name}: {table.num_rows} rows match")


def types(table):
    return {f.name: str(f.type) for f in table.schema}


for name in ["rows.arrows", "rows-zstd.arrows", "empty.arrows", "types.arrows"]:
    with ipc.open_stream(open(f"{d}/{name}", "rb")) as r:
        check(name, r.read_all())
for name in ["rows.arrow", "rows-lz4.arrow"]:
    with ipc.open_file(open(f"{d}/{name}", "rb")) as r:
        assert r.num_record_batches == 3, r.num_record_batches
        check(name, r.read_all())

rows = ipc.open_stream(open(f"{d}/rows.arrows", "rb")).read_all()
t = types(rows)
assert t["ts"] == "timestamp[us, tz=UTC]", t["ts"]
assert t["day"] == "date32[day]", t["day"]
assert t["t"] == "time64[us]", t["t"]
assert t["price"] == "decimal128(38, 18)", t["price"]
assert t["small"] == "int16" and t["tiny"] == "int8", t
assert t["attrs"] == "map<string, int32>", t["attrs"]
assert rows.schema.field("uid").type.extension_name == "arrow.uuid"
assert not rows.schema.field("id").nullable and rows.schema.field("name").nullable
t = types(ipc.open_stream(open(f"{d}/types.arrows", "rb")).read_all())
assert t["u8"] == "uint8" and t["u64"] == "uint64", t
assert t["dec40"] == "decimal256(40, 0)", t["dec40"]
assert t["f16"] == "halffloat", t["f16"]
assert t["legacy"] == "timestamp[ns, tz=UTC]", t["legacy"]
assert t["local_ts"] == "timestamp[ns]", t["local_ts"]
assert t["unknown"] == "null", t["unknown"]
assert t["ival"] == "month_day_nano_interval", t["ival"]
assert t["tm"] == "time32[ms]", t["tm"]

# pandas and polars read the same stream, when installed
try:
    import pandas  # noqa: F401

    df = rows.to_pandas()
    assert len(df) == 300 and str(df["name"].dtype) in ("object", "string", "str"), df.dtypes
    print("pandas:", len(df), "rows")
except ImportError:
    pass
print("All Arrow samples read with pyarrow", pa.__version__)

// Smoke test of the staged @wvlet/uni package, imported by its published name.
// Run with uni-npm/npm-test/run.sh, which stages the package into node_modules first.
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { newDatasetWriter, openDataset, openParquet, writeDataset, writeParquet } from "@wvlet/uni/parquet";

const schema = [
  { name: "id", type: "int64", optional: false },
  { name: "name", type: "string" },
  { name: "price", type: "double" },
  { name: "qty", type: "int32" },
  { name: "ok", type: "boolean" },
  { name: "amount", type: { decimal: { precision: 10, scale: 2 } } },
  { name: "day", type: "date" },
  { name: "at", type: "timestamp-micros" },
  { name: "uid", type: "uuid" },
  { name: "raw", type: "binary" },
  { name: "tags", type: { list: "string" } },
  { name: "attrs", type: { map: { key: "string", value: "int32" } } },
  { name: "point", type: { struct: [{ name: "x", type: "double" }, { name: "y", type: "double" }] } },
];

const rows = [
  {
    id: 1n,
    name: "apple",
    price: 1.5,
    qty: 3,
    ok: true,
    amount: "12.34",
    day: new Date(Date.UTC(2026, 10, 20)),
    at: 1763596800123456n,
    uid: "123e4567-e89b-12d3-a456-426614174000",
    raw: new Uint8Array([1, 2, 3]),
    tags: ["red", "fruit"],
    attrs: { a: 1, b: 2 },
    point: { x: 1.0, y: 2.5 },
  },
  { id: 9007199254740993n, name: null, price: null, qty: null, ok: false, tags: [], point: null },
];

const bytes = writeParquet(rows, schema);

test("writes a Parquet file", () => {
  assert.ok(bytes instanceof Uint8Array);
  assert.deepEqual(Array.from(bytes.subarray(0, 4)), [0x50, 0x41, 0x52, 0x31]); // PAR1
});

test("reads the schema and metadata", async () => {
  const f = await openParquet(bytes);
  assert.equal(f.numRows, 2);
  assert.equal(f.numRowGroups, 1);
  const types = Object.fromEntries(f.schema.map((c) => [c.name, c.type]));
  assert.equal(types.id, "int64");
  assert.equal(types.amount, "decimal(10,2)");
  assert.equal(types.at, "timestamp-micros");
  assert.equal(types.tags, "list");
  assert.equal(f.schema.find((c) => c.name === "id").optional, false);
  assert.equal(f.schema.find((c) => c.name === "point").fields.length, 2);
});

test("reads rows back with the documented value mapping", async () => {
  const [a, b] = await (await openParquet(bytes)).readRows();
  assert.equal(a.id, 1n);
  assert.equal(a.name, "apple");
  assert.equal(a.price, 1.5);
  assert.equal(a.qty, 3);
  assert.equal(a.ok, true);
  assert.equal(a.amount, "12.34");
  assert.equal(a.day.toISOString(), "2026-11-20T00:00:00.000Z");
  assert.equal(a.at.toISOString(), "2025-11-20T00:00:00.123Z");
  assert.equal(a.uid, "123e4567-e89b-12d3-a456-426614174000");
  assert.deepEqual(Array.from(a.raw), [1, 2, 3]);
  assert.deepEqual(a.tags, ["red", "fruit"]);
  assert.deepEqual({ ...a.attrs }, { a: 1, b: 2 });
  assert.deepEqual({ ...a.point }, { x: 1.0, y: 2.5 });
  // Beyond Number.MAX_SAFE_INTEGER, kept exactly as bigint
  assert.equal(b.id, 9007199254740993n);
  assert.equal(b.name, null);
  assert.equal(b.amount, null);
  assert.deepEqual(b.tags, []);
  assert.equal(b.point, null);
});

test("reads timestamps as bigint without loss", async () => {
  const [a] = await (await openParquet(bytes)).readRows({ timestamps: "bigint" });
  assert.equal(a.at, 1763596800123456n);
});

test("reads selected columns", async () => {
  const [a] = await (await openParquet(bytes)).readRows({ columns: ["id", "price"] });
  assert.deepEqual(Object.keys(a), ["id", "price"]);
});

test("reads batches with typed arrays and null masks", async () => {
  const f = await openParquet(bytes);
  const batches = [];
  for await (const batch of f.batches()) batches.push(batch);
  assert.equal(batches.length, 1);
  const [batch] = batches;
  assert.equal(batch.numRows, 2);
  const id = batch.column("id");
  assert.ok(id.values instanceof BigInt64Array);
  assert.deepEqual(Array.from(id.values), [1n, 9007199254740993n]);
  assert.equal(id.nulls, null);
  const price = batch.column("price");
  assert.ok(price.values instanceof Float64Array);
  assert.deepEqual(Array.from(price.nulls), [0, 1]);
  assert.ok(batch.column("qty").values instanceof Int32Array);
  assert.deepEqual(batch.column("name").values, ["apple", null]);
  assert.throws(() => batch.column("missing"), /No column named missing/);
});

test("reads an ArrayBuffer and a Blob", async () => {
  const buf = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length);
  assert.equal((await (await openParquet(buf)).readRows()).length, 2);
  const blob = new Blob([bytes]);
  const rowsFromBlob = await (await openParquet(blob)).readRows({ columns: ["name"] });
  assert.deepEqual(rowsFromBlob.map((r) => r.name), ["apple", null]);
});

test("reads a local file", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "uni-npm-"));
  const file = path.join(dir, "data.parquet");
  fs.writeFileSync(file, bytes);
  const f = await openParquet(file);
  assert.equal((await f.readRows()).length, 2);
  f.close();
  fs.rmSync(dir, { recursive: true });
});

test("reads over HTTP with Range requests", async () => {
  let ranges = 0;
  const server = http.createServer((req, res) => {
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? "");
    if (!m) {
      res.writeHead(200, { "Content-Length": bytes.length });
      res.end(Buffer.from(bytes));
      return;
    }
    ranges++;
    const start = Number(m[1]);
    const end = m[2] ? Number(m[2]) : bytes.length - 1;
    res.writeHead(206, {
      "Content-Range": `bytes ${start}-${end}/${bytes.length}`,
      "Content-Length": end - start + 1,
    });
    res.end(Buffer.from(bytes.subarray(start, end + 1)));
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  try {
    const url = `http://127.0.0.1:${server.address().port}/data.parquet`;
    const f = await openParquet(url);
    assert.deepEqual((await f.readRows({ columns: ["id"] })).map((r) => r.id), [1n, 9007199254740993n]);
    assert.ok(ranges >= 1);
  } finally {
    server.close();
  }
});

test("writes with each compression", async () => {
  for (const compression of ["snappy", "none", "lz4", "zstd", "gzip"]) {
    const b = writeParquet([{ id: 1n, name: "x" }], schema.slice(0, 2), { compression });
    const [r] = await (await openParquet(b)).readRows();
    assert.equal(r.name, "x", compression);
  }
});

test("writes ZSTD at a compression level", async () => {
  for (const compressionLevel of [1, 9, 19]) {
    const b = writeParquet([{ id: 1n, name: "x" }], schema.slice(0, 2), { compression: "zstd", compressionLevel });
    const [r] = await (await openParquet(b)).readRows();
    assert.equal(r.name, "x", String(compressionLevel));
  }
  assert.throws(() => writeParquet([], schema.slice(0, 2), { compression: "zstd", compressionLevel: 23 }), /1 to 22/);
  assert.throws(() => writeParquet([], schema.slice(0, 2), { compression: "zstd", compressionLevel: 1.5 }), /integer/);
  assert.throws(() => writeParquet([], schema.slice(0, 2), { compressionLevel: 3 }), /compression level/);
});

test("reports errors as JS Errors", async () => {
  await assert.rejects(openParquet(new Uint8Array([1, 2, 3])), (e) => e instanceof Error);
  await assert.rejects(openParquet(42), /openParquet takes/);
  assert.throws(() => writeParquet([], [{ name: "a", type: "int128" }]), /Unknown type "int128"/);
  assert.throws(() => writeParquet([{ a: "x" }], [{ name: "a", type: "int64" }]), Error);
  assert.throws(() => writeParquet([{}], [{ name: "a", type: "int64", optional: false }]), Error);
  assert.throws(() => writeParquet([{ a: new Date("oops") }], [{ name: "a", type: "date" }]), /Invalid Date/);
  assert.throws(
    () => writeParquet([{ a: new Date("oops") }], [{ name: "a", type: "timestamp-millis" }]),
    /Invalid Date/,
  );
  assert.throws(
    () => writeParquet([{ a: "ab" }], [{ name: "a", type: { map: { key: "string", value: "string" } } }]),
    /Expected an object or a Map/,
  );
  assert.throws(() => writeParquet([], [{ name: "a", type: { decimal: { scale: 2 } } }]), /integer decimal precision/);
});

// ---- Datasets ----

const eventSchema = [
  { name: "id", type: "int64", optional: false },
  { name: "name", type: "string" },
  { name: "dt", type: "date" },
  { name: "hour", type: "int32" },
];

const events = [
  { id: 1n, name: "a", dt: "2026-11-19", hour: 1 },
  { id: 2n, name: "b", dt: "2026-11-20", hour: 1 },
  { id: 3n, name: "c", dt: "2026-11-20", hour: 2 },
  { id: 4n, name: null, dt: new Date(Date.UTC(2026, 10, 21)), hour: 2 },
];

function tempRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "uni-npm-dataset-"));
}

test("writes a partitioned dataset and reads it back with typed partitions", async () => {
  const root = path.join(tempRoot(), "events");
  const written = await writeDataset(root, events, eventSchema, { partitionBy: ["dt", "hour"] });
  assert.equal(written.length, 4);
  assert.equal(written.reduce((n, f) => n + f.numRows, 0), 4);
  assert.ok(written.every((f) => f.path.includes("dt=") && f.size > 0));
  assert.deepEqual(written.map((f) => f.partitions.hour).sort(), [1, 1, 2, 2]);
  assert.ok(fs.existsSync(path.join(root, "_SUCCESS")));
  assert.ok(fs.existsSync(path.join(root, "_common_metadata")));

  const ds = await openDataset(root);
  assert.deepEqual(ds.partitionColumns, ["dt", "hour"]);
  assert.equal(ds.numFiles, 4);
  const types = Object.fromEntries(ds.schema.map((c) => [c.name, [c.type, c.partition ?? false]]));
  // Types recorded in _common_metadata, with no partitionTypes option
  assert.deepEqual(types, {
    id: ["int64", false],
    name: ["string", false],
    dt: ["date", true],
    hour: ["int32", true],
  });
  const rows = await ds.readRows();
  assert.deepEqual(
    rows.map((r) => r.id),
    [1n, 2n, 3n, 4n],
  );
  assert.ok(rows[0].dt instanceof Date);
  assert.equal(rows[0].dt.toISOString(), "2026-11-19T00:00:00.000Z");
  assert.equal(rows[3].hour, 2);
  ds.close();
});

test("prunes partitions and filters rows", async () => {
  const root = path.join(tempRoot(), "events");
  await writeDataset(root, events, eventSchema, { partitionBy: ["dt"] });
  const ds = await openDataset(root);
  const day = new Date(Date.UTC(2026, 10, 20));
  const files = await ds.files({ filter: ["dt", "=", day] });
  assert.equal(files.length, 1);
  assert.ok(files[0].partitions.dt instanceof Date);
  assert.ok(files[0].size > 0);

  const ids = async (filter) => (await ds.readRows({ filter, columns: ["id"] })).map((r) => r.id);
  assert.deepEqual(await ids(["dt", ">=", "2026-11-20"]), [2n, 3n, 4n]);
  assert.deepEqual(await ids([["dt", "=", "2026-11-20"], ["hour", ">", 1]]), [3n]);
  assert.deepEqual(await ids([[["id", "=", 1n]], [["name", "is null"]]]), [1n, 4n]);
  assert.deepEqual(await ids(["name", "in", ["a", "c"]]), [1n, 3n]);
  assert.deepEqual(await ids(["name", "!=", "a"]), [2n, 3n]);
  assert.deepEqual(await ids(["name", "not in", ["a", "b"]]), [3n]);
  await assert.rejects(ds.readRows({ filter: ["id", "~", 1] }), /Unknown filter operator/);
  await assert.rejects(ds.readRows({ filter: ["id", "=", null] }), /is null/);
  await assert.rejects(ds.readRows({ filter: ["nope", "=", 1] }), /No column named nope/);
  ds.close();
});

test("reads a dataset in batches, stopping early", async () => {
  const root = path.join(tempRoot(), "events");
  await writeDataset(root, events, eventSchema, { partitionBy: ["dt"] });
  const ds = await openDataset(root);
  let rows = 0;
  for await (const b of ds.batches({ filter: ["dt", "<=", "2026-11-20"] })) {
    assert.ok(b.column("dt").values.length === b.numRows);
    rows += b.numRows;
  }
  assert.equal(rows, 3);
  for await (const b of ds.batches()) {
    assert.ok(b.numRows > 0);
    break;
  }
  ds.close();
});

test("reads a glob and a list of files", async () => {
  const root = path.join(tempRoot(), "events");
  const written = await writeDataset(root, events, eventSchema, { partitionBy: ["dt"] });
  const glob = await openDataset(`${root}/dt=2026-11-2*`);
  assert.equal(glob.numFiles, 2);
  assert.deepEqual((await glob.readRows({ columns: ["id"] })).map((r) => r.id), [2n, 3n, 4n]);
  glob.close();

  const paths = written.map((f) => f.path).sort();
  // A list has no root, so partitions are strings unless declared
  const list = await openDataset(paths);
  assert.equal((await list.readRows())[0].dt, "2026-11-19");
  list.close();
  const typed = await openDataset(paths, { partitionTypes: { dt: "date" } });
  assert.ok((await typed.readRows())[0].dt instanceof Date);
  assert.ok((await typed.files())[0].size > 0);
  typed.close();
});

test("streams rows with a dataset writer and appends", async () => {
  const root = path.join(tempRoot(), "events");
  const w = await newDatasetWriter(root, eventSchema, { partitionBy: ["dt"], maxRowsPerFile: 1 });
  for (const e of events) w.write(e);
  await w.flush();
  assert.equal(w.numRows, 4);
  const files = await w.close();
  assert.equal(files.length, 4);
  await assert.rejects(writeDataset(root, events, eventSchema, { partitionBy: ["dt"] }), Error);
  await writeDataset(root, events.slice(0, 1), eventSchema, { partitionBy: ["dt"], mode: "append" });
  const ds = await openDataset(root);
  assert.equal((await ds.readRows()).length, 5);
  ds.close();
});

test("reports dataset option errors", async () => {
  await assert.rejects(openDataset(path.join(tempRoot(), "missing")), Error);
  await assert.rejects(openDataset("https://example.com/x/"), /openParquet/);
  await assert.rejects(openDataset([]), /empty/);
  await assert.rejects(openDataset("/tmp", { partitionTypes: { dt: "timestamp" } }), /Unknown partition type/);
  await assert.rejects(writeDataset(path.join(tempRoot(), "x"), [], eventSchema, { mode: "replace" }), /Unknown mode/);
  await assert.rejects(
    openDataset("s3://bucket/x/", { storage: { s3: { credentials: "none" } } }),
    /"anonymous"/,
  );
  await assert.rejects(
    openDataset("gs://bucket/x/", { storage: { gcs: { accessToken: "t", anonymous: true } } }),
    /one of/,
  );
});

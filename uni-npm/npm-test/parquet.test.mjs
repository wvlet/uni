// Smoke test of the staged @wvlet/uni package, imported by its published name.
// Run with uni-npm/npm-test/run.sh, which stages the package into node_modules first.
import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { openParquet, writeParquet } from "@wvlet/uni/parquet";

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
  for (const compression of ["snappy", "none", "lz4", "gzip"]) {
    const b = writeParquet([{ id: 1n, name: "x" }], schema.slice(0, 2), { compression });
    const [r] = await (await openParquet(b)).readRows();
    assert.equal(r.name, "x", compression);
  }
});

test("reports errors as JS Errors", async () => {
  await assert.rejects(openParquet(new Uint8Array([1, 2, 3])), (e) => e instanceof Error);
  await assert.rejects(openParquet(42), /openParquet takes/);
  assert.throws(() => writeParquet([], [{ name: "a", type: "int128" }]), /Unknown type "int128"/);
  assert.throws(() => writeParquet([{ a: "x" }], [{ name: "a", type: "int64" }]), Error);
  assert.throws(() => writeParquet([{}], [{ name: "a", type: "int64", optional: false }]), Error);
});

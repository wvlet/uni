// Compiled with tsc --noEmit to check parquet.d.ts from a user's point of view
import {
  openParquet,
  writeParquet,
  type ColumnSpec,
  type ParquetFile,
  type ParquetRow,
} from "@wvlet/uni/parquet";

const schema: ColumnSpec[] = [
  { name: "id", type: "int64", optional: false },
  { name: "at", type: "timestamp-micros" },
  { name: "amount", type: { decimal: { precision: 10, scale: 2 } } },
  { name: "tags", type: { list: "string" } },
  { name: "point", type: { struct: [{ name: "x", type: "double" }] } },
];

const bytes: Uint8Array = writeParquet([{ id: 1n, at: new Date() }], schema, {
  compression: "lz4",
});

async function main(): Promise<void> {
  const file: ParquetFile = await openParquet(bytes);
  const n: number = file.numRows;
  const rows: ParquetRow[] = await file.readRows({ columns: ["id"], timestamps: "bigint" });
  for await (const batch of file.batches()) {
    const col = batch.column("id");
    if (col.values instanceof BigInt64Array) {
      const first: bigint = col.values[0];
      console.log(first);
    }
  }
  console.log(n, rows, file.schema[0].type);
  file.close();
}

void main();

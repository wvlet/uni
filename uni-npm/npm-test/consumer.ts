// Compiled with tsc --noEmit to check parquet.d.ts from a user's point of view
import {
  newDatasetWriter,
  openDataset,
  openParquet,
  writeDataset,
  writeParquet,
  type ColumnSpec,
  type DatasetFileInfo,
  type Filter,
  type ParquetDataset,
  type WrittenFile,
  type ParquetFile,
  type ParquetRow,
} from "@wvlet/uni/cloud/parquet";

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

  const eventSchema: ColumnSpec[] = [
    { name: "id", type: "int64" },
    { name: "dt", type: "date" },
  ];
  const written: WrittenFile[] = await writeDataset("/tmp/events", [{ id: 1n, dt: "2026-11-20" }], eventSchema, {
    partitionBy: ["dt"],
    mode: "overwrite",
  });
  const w = await newDatasetWriter("s3://bucket/events/", eventSchema, {
    partitionBy: ["dt"],
    storage: { s3: { region: "us-east-1", credentials: { accessKeyId: "a", secretAccessKey: "b" } } },
  });
  w.write({ id: 2n, dt: new Date() });
  await w.flush();
  const more: WrittenFile[] = await w.close();

  const filter: Filter = [
    [["dt", ">=", new Date(Date.UTC(2026, 10, 20))], ["id", "in", [1n, 2n]]],
    [["id", "is null"]],
  ];
  const ds: ParquetDataset = await openDataset(["gs://b/x/dt=2026-11-20/a.parquet"], {
    partitionTypes: { dt: "date" },
    storage: { gcs: { accessToken: "t" } },
  });
  const files: DatasetFileInfo[] = await ds.files({ filter });
  const size: number | null = files[0].size;
  const dsRows: ParquetRow[] = await ds.readRows({ filter: ["dt", "=", "2026-11-20"], columns: ["id"] });
  for await (const b of ds.batches({ filter })) console.log(b.numRows);
  console.log(written, more, size, dsRows, ds.schema[0].partition, ds.partitionColumns);
  ds.close();
}

void main();

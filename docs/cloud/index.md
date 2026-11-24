---
description: Uni's cloud data modules in pure Scala — S3, R2, MinIO, Google Cloud Storage, and Azure Blob objects, Apache Parquet files and datasets, Apache Iceberg and Delta Lake tables, and Arrow IPC output — with no Hadoop, iceberg-java, or cloud SDK, on the JVM, Scala.js, and Scala Native.
---

# Cloud Data

The `wvlet.uni.cloud` packages read and write data where it usually lives today: in object
storage, as Parquet files, organized as Iceberg or Delta Lake tables. They are part of the `uni`
artifact and written in Scala. They don't pull in Hadoop, iceberg-java, Avro, or the AWS, Google,
or Azure SDKs, so a service, a CLI, a Scala Native binary, or a browser page can read a data lake
with the same code.

| Page | Package | What it does |
|------|---------|--------------|
| [Object Storage](./storage) | `wvlet.uni.cloud.storage` | Read, write, list, and presign objects in S3, R2, MinIO, Google Cloud Storage, and Azure Blob, with their credential chains |
| [Parquet](./parquet) | `wvlet.uni.cloud.parquet` | Read and write Parquet files and partitioned datasets, from and to case classes, maps, JSON, or column batches, with filters that skip files and row groups |
| [Iceberg Tables](./iceberg) | `wvlet.uni.cloud.iceberg` | Read, create, append to, delete and update rows in, compact, and alter Apache Iceberg tables (format versions 1 to 3) |
| [Delta Lake Tables](./delta) | `wvlet.uni.cloud.parquet` | Read Delta Lake tables, with time travel and typed partitions |
| [Arrow IPC](./arrow) | `wvlet.uni.cloud.arrow` | Send column batches to pyarrow, pandas, polars, Arrow JS, and ADBC clients as Arrow streams or files |

## A Table in S3, End to End

Write case classes as an Iceberg table, append to it, and read back only the rows a filter
matches. The first write creates the table; Spark, Trino, DuckDB, and pyiceberg can read it too.

```scala
import wvlet.uni.cloud.parquet.*
import wvlet.uni.cloud.parquet.ParquetFilter.col
import wvlet.uni.weaver.Weaver
import java.time.LocalDate

case class Order(id: Long, customer: String, dt: LocalDate, amount: Double) derives Weaver

val root    = "s3://my-bucket/warehouse/orders/"
val day     = LocalDate.parse("2026-11-20")
val asTable = ParquetDatasetWriteOptions
  .default
  .withTableFormat(ParquetTableFormat.Iceberg)
  .withPartitionBy("dt")

Parquet.writeDataset(root, Seq(Order(1L, "aina", day, 12.5)), asTable)
Parquet.writeDataset(
  root,
  Seq(Order(2L, "leo", day, 40.0)),
  asTable.withMode(ParquetWriteMode.Append) // one more snapshot
)

val table = Parquet.dataset(root)
val large =
  try table.readAs[Order](ParquetReadOptions.default.withFilter(col("amount") >= 20.0)).toSeq
  finally table.close()
// Seq(Order(2, leo, 2026-11-20, 40.0))
```

`ObjectStore.s3()` finds AWS credentials as the AWS SDK does: environment variables, `~/.aws`
profiles and SSO, and the EC2, ECS, and EKS metadata services. `gs://` and `az://` roots work the
same way with Google and Azure credentials (see [Object Storage](./storage)).

## Platforms

| | JVM | Scala Native | Node.js | Browsers |
|---|---|---|---|---|
| Object storage | ✓ | ✓ | ✓ | ✓ (`...Async` calls, CORS) |
| Parquet files and datasets | ✓ | ✓ | ✓ | ✓ (async reads and writes) |
| Iceberg reads, creates, appends, overwrites | ✓ | ✓ | ✓ | ✓ (async) |
| Iceberg deletes, updates, compaction, schema changes | ✓ | ✓ | ✓ | — |
| Delta Lake reads | ✓ | ✓ | ✓ | ✓ (async) |
| Arrow IPC writes | ✓ | ✓ | ✓ | ✓ |

From TypeScript or JavaScript, the npm package `@wvlet/uni` reads and writes Parquet files and
datasets (see [Parquet from TypeScript](./parquet#from-typescript)).

## Uni and Other Engines

Spark, Trino, Flink, DuckDB, pyiceberg, and delta-rs are the engines most data lakes run on, and
Uni reads and writes the same files and tables, so they work side by side. Those engines fit
cluster-scale jobs, SQL over many tables, and catalog-owned tables. Uni fits the code around them:
an API server that reads a table, a job that appends results, a CLI or Scala Native binary that
needs no JVM, or a browser page that shows a Parquet file.

Know the current limits before you choose:

- **Iceberg commits** go to Hadoop tables (`metadata/vN.metadata.json`, no catalog). Commit to
  tables a Glue, REST, Hive, or SQL catalog owns through the catalog; Uni can read them from the
  catalog's metadata file.
- **Iceberg partitions** that Uni writes are identity partitions on string, int, long, and date
  columns. Tables with `day`, `bucket`, or `truncate` partitions read, take deletes, and compact,
  but don't take appends yet.
- **Delta Lake** is read-only, and tables with deletion vectors or column mapping fail by name.
- **Encrypted** Iceberg tables and Parquet files aren't read, since they need a key management
  service.
- **Arrow** is written, not read.

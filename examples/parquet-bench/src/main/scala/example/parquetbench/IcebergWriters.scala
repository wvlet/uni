package example.parquetbench

import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.cloud.parquet.Parquet
import wvlet.uni.cloud.parquet.ParquetDatasetWriteOptions
import wvlet.uni.cloud.parquet.ParquetTableFormat

/**
  * Uni's Iceberg writer: each case creates a new table (snapshot 1) from the in-memory lineitem
  * rows, as `duckdb_iceberg.py` does with DuckDB. Each returns (rows, data files).
  */
object IcebergWriters:
  private def options(partitionBy: Seq[String], codec: String): ParquetDatasetWriteOptions =
    ParquetDatasetWriteOptions
      .default
      .withTableFormat(ParquetTableFormat.Iceberg)
      .withPartitionBy(partitionBy*)
      .withWriteOptions(UniWriters.options(codec))

  private def prepare(root: String): Unit =
    val p = IOPath.parse(root)
    if FileSystem.exists(p) then
      FileSystem.deleteRecursively(p)

  def lineItems(rows: Seq[LineItem], root: String, partitionBy: Seq[String]): (Long, Long) =
    prepare(root)
    val files = Parquet.writeDataset[LineItem](root, rows, options(partitionBy, "snappy"))
    (files.map(_.numRows).sum, files.size)

  def batches(
      data: BenchBatches,
      root: String,
      partitionBy: Seq[String],
      codec: String
  ): (Long, Long) =
    prepare(root)
    val w = Parquet.newDatasetBatchWriter(root, data.schema, options(partitionBy, codec))
    try w.writeAll(data.batches)
    finally w.close()
    (w.numRows, w.writtenFiles.size)

  /**
    * The Iceberg write cases over lineitem, unpartitioned and partitioned by ship mode (7 values):
    * case classes with Snappy, and batches with Snappy and ZSTD. Reader names start with "iceberg"
    * so `--filter iceberg` selects them.
    */
  def cases(dir: String): Seq[BenchCase] =
    val out  = s"${BenchRunner.writeDir(dir)}/iceberg"
    val path = s"${dir}/lineitem.snappy.parquet"
    val by   = Seq("lShipmode")
    Seq(
      BenchCase(
        "iceberg uni case class, snappy",
        path,
        () => lineItems(BenchData.lineItems(dir), s"${out}/rows", Nil)
      ),
      BenchCase(
        "iceberg uni case class, by lShipmode, snappy",
        path,
        () => lineItems(BenchData.lineItems(dir), s"${out}/rows-by", by)
      )
    ) ++
      Seq("snappy", "zstd").flatMap { codec =>
        val suffix =
          if codec == "snappy" then
            ""
          else
            s"-${codec}"
        Seq(
          BenchCase(
            s"iceberg uni batch, ${codec}",
            path,
            () => batches(BenchBatches.lineItems(dir).rebuilt, s"${out}/batch${suffix}", Nil, codec)
          ),
          BenchCase(
            s"iceberg uni batch, by lShipmode, ${codec}",
            path,
            () =>
              batches(BenchBatches.lineItems(dir).rebuilt, s"${out}/batch-by${suffix}", by, codec)
          )
        )
      }

end IcebergWriters

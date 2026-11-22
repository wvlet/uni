package wvlet.uni.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.parquet.format.CompressionCodec

/**
  * Writes sample files for checking the writer against another reader. Run by
  * `scripts/check-writer-with-duckdb.sh`, which compares every value with what DuckDB reads:
  *
  * {{{
  * ./sbt "uniJVM/Test/runMain wvlet.uni.parquet.WriteSampleFiles target/parquet-samples"
  * }}}
  */
object WriteSampleFiles:
  def main(args: Array[String]): Unit =
    FileSystemInit.init()
    val dir    = IOPath.parse(args.headOption.getOrElse("target/parquet-samples"))
    val orders = (0 until 300).map(Order.sample)
    wvlet.uni.io.FileSystem.createDirectory(dir)
    def out(name: String) = (dir / name).toString

    Parquet.write(out("orders.parquet"), orders)
    for codec <- Seq(
        CompressionCodec.UNCOMPRESSED,
        CompressionCodec.LZ4_RAW,
        CompressionCodec.ZSTD,
        CompressionCodec.GZIP
      )
    do
      Parquet.write(
        out(s"orders-${codec.toString.toLowerCase}.parquet"),
        orders,
        ParquetWriteOptions.default.withCompression(codec)
      )
    Parquet.write(
      out("orders-plain.parquet"),
      orders,
      ParquetWriteOptions.default.noDictionary().withPageSize(512).withRowGroupSize(8 * 1024)
    )
    // Many distinct strings: the name column falls back from dictionary to PLAIN
    Parquet.write(
      out("fallback.parquet"),
      (0 until 20000).map(i => Simple(i, Some(s"distinct-value-${i}"))),
      ParquetWriteOptions.default.withDictionaryPageSize(4096).withRowGroupSize(64 * 1024)
    )

    val schema = ParquetSchema
      .builder("record")
      .required("id", ParquetType.INT64)
      .optional("name", ParquetType.STRING)
      .optional("ts", ParquetType.TIMESTAMP_MILLIS)
      .optional("day", ParquetType.DATE)
      .optional("price", ParquetType.decimal(9, 2))
      .optional("doc", ParquetType.JSON)
      .build()
    val w = Parquet.newRecordWriter(out("records.parquet"), schema)
    try
      w.write(
        Map(
          "id"    -> 1,
          "name"  -> "aina",
          "ts"    -> "2026-02-03T04:05:06.789Z",
          "day"   -> "2026-01-31",
          "price" -> "12.34",
          "doc"   -> Map("a" -> 1)
        )
      )
      w.write("""{"id": 2, "name": "leo", "price": -0.5}""")
      w.write(Seq(3, null, null, null, null, null))
      w.write("""[4, "x", 0, "1970-01-02", 1, "[1,2]"]""")
    finally
      w.close()
    println(s"Wrote sample files to ${dir}")

  end main

end WriteSampleFiles

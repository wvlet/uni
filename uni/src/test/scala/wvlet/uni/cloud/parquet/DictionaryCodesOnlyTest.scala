package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class CodeRow(id: Long, k: Int, flag: Option[String], name: String) derives Weaver

/**
  * `dictionaryCodesOnly` reads: dictionary-encoded rows keep only their entry, and the accessors
  * return the same values, nulls, and entries as a full read
  */
class DictionaryCodesOnlyTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 5000).map { i =>
    CodeRow(
      i.toLong,
      (i * 7919) % 1000,
      Option.when(i % 7 != 0)(Seq("A", "N", "R")(i % 3)),
      // Distinct enough that the dictionary overflows and later pages are PLAIN
      s"name-${i}"
    )
  }

  private val fallback = Parquet.writeBytes(
    rows,
    ParquetWriteOptions.default.withDictionaryPageSize(4096).withPageSize(256)
  )

  private val plainFile = Parquet.writeBytes(rows)

  private def read(file: Array[Byte], options: ParquetReadOptions): Seq[ColumnBatch] =
    val in = Parquet.open(file)
    try in.readBatches(options).toList
    finally in.close()

  private def values(bs: Seq[ColumnBatch], name: String): Seq[(Any, String, Int, Seq[Byte])] = bs
    .flatMap { b =>
      val c = b.column(name).asInstanceOf[BinaryColumn]
      (0 until c.size).map { i =>
        if c.isNull(i) then
          (null, null, c.dictionaryIndex(i), Seq.empty)
        else
          (c.getValue(i), c.getString(i), c.dictionaryIndex(i), c.getBytes(i).toSeq)
      }
    }

  private def check(file: Array[Byte], options: ParquetReadOptions): Unit =
    val full  = read(file, options)
    val codes = read(file, options.withDictionaryCodesOnly("flag", "name"))
    for name <- Seq("flag", "name") do
      values(codes, name) shouldBe values(full, name)
    codes.map(_.numRows) shouldBe full.map(_.numRows)

  test("should read the same values from dictionary entries only") {
    check(plainFile, ParquetReadOptions.default)
    val b    = read(plainFile, ParquetReadOptions.default.withDictionaryCodesOnly("flag")).head
    val flag = b.column("flag").asInstanceOf[BinaryColumn]
    flag.dictionarySize shouldBe 3
    // The rows keep no bytes of their own
    flag.offsets(flag.size) shouldBe 0
    flag.length(1) shouldBe 1
    flag.getString(1) shouldBeTheSameInstanceAs flag.getString(4)
  }

  test("should read values after the dictionary falls back to PLAIN") {
    check(fallback, ParquetReadOptions.default)
    check(fallback, ParquetReadOptions.default.withBatchSize(7))
    check(fallback, ParquetReadOptions.default.withBatchSize(1000))
  }

  test("should read the same values with a filter on other columns") {
    for file <- Seq(plainFile, fallback) do
      check(file, ParquetReadOptions.default.withFilter(col("k") < 300))
      check(file, ParquetReadOptions.default.withFilter(col("k") < 300).withBatchSize(333))
  }

  test("should read a filtered column as usual") {
    val opts = ParquetReadOptions.default.withFilter(col("flag") === "N")
    check(plainFile, opts)
    val b = read(plainFile, opts.withDictionaryCodesOnly("flag")).head
    val c = b.column("flag").asInstanceOf[BinaryColumn]
    (c.offsets(c.size) > 0) shouldBe true
  }

end DictionaryCodesOnlyTest

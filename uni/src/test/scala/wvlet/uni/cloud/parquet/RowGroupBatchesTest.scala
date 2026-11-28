package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class SliceRow(id: Int, name: Option[String], store: String, score: Double) derives Weaver

case class OnePageRow(
    id: Long,
    qty: Option[Int],
    price: Double,
    flag: Option[Boolean],
    // Few distinct values: dictionary-encoded
    store: Option[String],
    code: String,
    // Distinct values: PLAIN once the dictionary overflows
    note: Option[String]
) derives Weaver

/**
  * [[ParquetFile.readRowGroupBatches]] reads one row group in batches of the batch size, with the
  * same rows as reading the row group whole
  */
class RowGroupBatchesTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 5000).map { i =>
    SliceRow(
      id = i,
      name = Option.when(i % 3 != 0)(s"name ${i}"),
      // Few distinct values, so the column is dictionary-encoded
      store = s"store-${i % 7}",
      score = i * 0.25
    )
  }

  private val bytes = Parquet.writeBytes(
    rows,
    ParquetWriteOptions.default.withRowGroupSize(32 * 1024).withPageSize(4 * 1024)
  )

  private def withFile[A](body: ParquetFile => A): A =
    val f = Parquet.open(bytes)
    try body(f)
    finally f.close()

  private def rowsOf(b: ColumnBatch): Seq[Map[String, Any]] = b.rows.toSeq

  test("read several row groups") {
    withFile(f => (f.numRowGroups > 2) shouldBe true)
  }

  test("read a row group in batches of at most the batch size, with the rows read whole") {
    withFile { f =>
      (0 until f.numRowGroups).foreach { g =>
        val whole = rowsOf(f.readRowGroup(g))
        Seq(1, 100, 333, 2048).foreach { size =>
          val batches =
            f.readRowGroupBatches(g, ParquetReadOptions.default.withBatchSize(size)).toSeq
          batches.forall(_.numRows <= size) shouldBe true
          batches.flatMap(rowsOf) shouldBe whole
          if whole.size > size then
            (batches.size > 1) shouldBe true
        }
      }
    }
  }

  test("read a row group as one batch without a batch size") {
    withFile { f =>
      val batches = f.readRowGroupBatches(0).toSeq
      batches.size shouldBe 1
      batches.flatMap(rowsOf) shouldBe rowsOf(f.readRowGroup(0))
    }
  }

  test("apply the row filter and the columns to each batch") {
    withFile { f =>
      val options = ParquetReadOptions
        .default
        .withColumns("id", "store")
        .withFilter(col("store") === "store-3" || col("id") < 10)
        .withBatchSize(256)
      val ids = (0 until f.numRowGroups).flatMap { g =>
        f.readRowGroupBatches(g, options).flatMap(_.rows).map(_("id"))
      }
      ids shouldBe rows.filter(r => r.store == "store-3" || r.id < 10).map(_.id)
      f.readRowGroupBatches(0, options).next().columnNames shouldBe Seq("id", "store")
    }
  }

  // One page per column chunk, as DuckDB writes them, with and without dictionaries: the cursor
  // decodes a page's values in steps of the batch size
  private val onePageRows = (0 until 6000).map { i =>
    OnePageRow(
      id = i.toLong * 31,
      qty = Option.when((i * 7) % 11 != 3)(i % 50),
      price = i * 0.5,
      flag = Option.when(i % 4 != 0)(i % 3 == 0),
      store = Option.when(i % 9 != 5)(s"s${i % 13}"),
      code = s"c${i % 5}",
      note = Option.when(i % 6 != 1 && i / 1000 != 2)(s"note ${i} ${"x" * (i % 17)}")
    )
  }

  private def onePageFile(dictionary: Boolean): Array[Byte] = Parquet.writeBytes(
    onePageRows,
    ParquetWriteOptions
      .default
      .withRowGroupSize(64 * 1024 * 1024)
      .withPageSize(64 * 1024 * 1024)
      .withDictionaryPageSize(64 * 1024 * 1024)
      .withDictionaryEnabled(dictionary)
  )

  Seq(true, false).foreach { dictionary =>
    test(s"read one-page chunks in batches (dictionary: ${dictionary})") {
      val f = Parquet.open(onePageFile(dictionary))
      try
        f.numRowGroups shouldBe 1
        val whole = rowsOf(f.readRowGroup(0))
        whole.size shouldBe onePageRows.size
        Seq(1, 7, 100, 1000, 4096, 5999).foreach { size =>
          val batches =
            f.readRowGroupBatches(0, ParquetReadOptions.default.withBatchSize(size)).toSeq
          batches.map(_.numRows).sum shouldBe onePageRows.size
          batches.forall(_.numRows <= size) shouldBe true
          batches.flatMap(rowsOf) shouldBe whole
        }
        f.readAs[OnePageRow](ParquetReadOptions.default.withBatchSize(333)).toSeq shouldBe
          onePageRows
      finally
        f.close()
    }

    test(s"read one-page chunks in batches with a row filter (dictionary: ${dictionary})") {
      val f = Parquet.open(onePageFile(dictionary))
      try
        val options = ParquetReadOptions.default.withFilter(col("code") === "c2").withBatchSize(250)
        val ids     = f.readRowGroupBatches(0, options).flatMap(_.rows).map(_("id")).toSeq
        ids shouldBe onePageRows.filter(_.code == "c2").map(_.id)
      finally
        f.close()
    }

    test(s"read dictionary codes of one-page chunks in batches (dictionary: ${dictionary})") {
      val f = Parquet.open(onePageFile(dictionary))
      try
        val options = ParquetReadOptions.default.withDictionaryCodesOnly("store", "code")
        val whole   = f.readRowGroup(0, options)
        val sliced  = f.readRowGroupBatches(0, options.withBatchSize(512)).toSeq
        sliced.flatMap(rowsOf) shouldBe rowsOf(whole)
      finally
        f.close()
    }
  }

  test("read a nullable one-page chunk in as many steps as a page is cut into") {
    // 64,000 rows in batches of 2,000: 32 steps, each handing over the batch and keeping the rest
    val rows = (0 until 64000).map(i =>
      SliceRow(
        id = i,
        name = Option.when((i * 13) % 7 > 2)(s"n${i % 300}"),
        store = s"s${i % 3}",
        score = i.toDouble
      )
    )
    val f = Parquet.open(
      Parquet.writeBytes(
        rows,
        ParquetWriteOptions
          .default
          .withRowGroupSize(64 * 1024 * 1024)
          .withPageSize(64 * 1024 * 1024)
          .withDictionaryPageSize(64 * 1024 * 1024)
      )
    )
    try
      f.numRowGroups shouldBe 1
      val batches = f.readRowGroupBatches(0, ParquetReadOptions.default.withBatchSize(2000)).toSeq
      batches.size shouldBe 32
      batches.flatMap(rowsOf) shouldBe rowsOf(f.readRowGroup(0))
    finally
      f.close()
  }

end RowGroupBatchesTest

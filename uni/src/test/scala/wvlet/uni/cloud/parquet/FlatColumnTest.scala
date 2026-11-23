package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class FlatInner(a: Option[Int], b: Long) derives Weaver

case class FlatRow(
    id: Long,
    qty: Int,
    price: Option[Long],
    always: Option[Int],
    never: Option[Double],
    ratio: Option[Float],
    flag: Option[Boolean],
    // Few distinct values, so the column is dictionary-encoded
    store: Option[String],
    // Distinct values, so the dictionary overflows into PLAIN pages
    note: Option[String],
    inner: Option[FlatInner]
) derives Weaver

/**
  * Flat columns (no repetition) take their own path through column assembly: the decoded values
  * become the vector, or are spread out to their rows by the definition levels. The vectors must be
  * the same for every batch size: values in row order, nulls flagged, and null rows holding the
  * default value.
  */
class FlatColumnTest extends UniTest:
  FileSystemInit.init()

  private val rows: Seq[FlatRow] = (0 until 3000).map { i =>
    FlatRow(
      id = i.toLong,
      qty = i % 100,
      price = Option.when(i % 7 != 3)(i * 1000L),
      always = Some(i * 3),
      never = None,
      ratio = Option.when(i % 2 == 0)(i / 4f),
      flag = Option.when(i % 5 != 0)(i % 3 == 0),
      store = Option.when(i % 4 != 1)(s"store-${i % 9}"),
      note = Option.when(i % 6 != 2)(s"note ${i}"),
      inner = Option.when(i % 10 != 0)(FlatInner(Option.when(i % 3 != 0)(i), -i.toLong))
    )
  }

  // Small pages and row groups, so chunks have many pages and batches end inside them
  private val bytes: Array[Byte] = Parquet.writeBytes(
    rows,
    ParquetWriteOptions
      .default
      .withPageSize(512)
      .withRowGroupSize(32 * 1024)
      .withDictionaryPageSize(1024)
  )

  private def readBatches(options: ParquetReadOptions): Seq[ColumnBatch] =
    val f = Parquet.open(bytes)
    try f.readBatches(options).toSeq
    finally f.close()

  private val batchOptions: Seq[(String, ParquetReadOptions)] = Seq(
    "whole row groups" -> ParquetReadOptions.default,
    "1 row"            -> ParquetReadOptions.default.withBatchSize(1),
    "7 rows"           -> ParquetReadOptions.default.withBatchSize(7),
    "500 rows"         -> ParquetReadOptions.default.withBatchSize(500)
  )

  test("write several row groups with several pages per column") {
    val f = Parquet.open(bytes)
    try
      (f.numRowGroups > 1) shouldBe true
      f.schema.toString shouldContain "optional int64 price"
    finally
      f.close()
  }

  /** Every column of every row of the batches, as the expected values */
  private def checkVectors(batches: Seq[ColumnBatch]): Unit =
    var row = 0
    batches.foreach { b =>
      val expected                        = rows.slice(row, row + b.numRows)
      def col(name: String): ColumnVector = b.column(name)

      col("id") match
        case c: LongColumn =>
          c.values.toSeq shouldBe expected.map(_.id)
          c.nullFlags shouldBe null
        case other =>
          unexpected(other)
      col("qty") match
        case c: IntColumn =>
          c.values.toSeq shouldBe expected.map(_.qty)
          c.nullFlags shouldBe null
        case other =>
          unexpected(other)
      col("price") match
        case c: LongColumn =>
          c.values.toSeq shouldBe expected.map(_.price.getOrElse(0L))
          nulls(c) shouldBe expected.map(_.price.isEmpty)
        case other =>
          unexpected(other)
      col("always") match
        case c: IntColumn =>
          c.values.toSeq shouldBe expected.map(_.always.get)
          c.nullFlags shouldBe null
        case other =>
          unexpected(other)
      col("never") match
        case c: DoubleColumn =>
          c.values.toSeq shouldBe Seq.fill(b.numRows)(0.0)
          c.nullFlags.toSeq shouldBe Seq.fill(b.numRows)(true)
        case other =>
          unexpected(other)
      col("ratio") match
        case c: FloatColumn =>
          c.values.toSeq shouldBe expected.map(_.ratio.getOrElse(0f))
          nulls(c) shouldBe expected.map(_.ratio.isEmpty)
        case other =>
          unexpected(other)
      col("flag") match
        case c: BooleanColumn =>
          c.values.toSeq shouldBe expected.map(_.flag.getOrElse(false))
          nulls(c) shouldBe expected.map(_.flag.isEmpty)
        case other =>
          unexpected(other)
      Seq("store" -> expected.map(_.store), "note" -> expected.map(_.note)).foreach {
        case (name, values) =>
          col(name) match
            case c: BinaryColumn =>
              c.size shouldBe b.numRows
              // Null rows are empty
              (0 until c.size).map(i => String(c.getBytes(i), "UTF-8")) shouldBe
                values.map(_.getOrElse(""))
              nulls(c) shouldBe values.map(_.isEmpty)
              if c.dictIndices != null then
                // Only non-null rows point into the dictionary
                (0 until c.size).foreach { i =>
                  if values(i).isEmpty then
                    c.dictIndices(i) shouldBe -1
                  else if c.dictIndices(i) >= 0 then
                    String(c.dictionary.bytes(c.dictIndices(i)), "UTF-8") shouldBe values(i).get
                }
            case other =>
              unexpected(other)
      }
      col("inner") match
        case s: StructColumn =>
          nulls(s) shouldBe expected.map(_.inner.isEmpty)
          s.children(0) match
            case c: IntColumn =>
              c.values.toSeq shouldBe expected.map(_.inner.flatMap(_.a).getOrElse(0))
              nulls(c) shouldBe expected.map(_.inner.flatMap(_.a).isEmpty)
            case other =>
              unexpected(other)
          s.children(1) match
            case c: LongColumn =>
              c.values.toSeq shouldBe expected.map(_.inner.map(_.b).getOrElse(0L))
              nulls(c) shouldBe expected.map(_.inner.isEmpty)
            case other =>
              unexpected(other)
        case other =>
          unexpected(other)
      row += b.numRows
    }
    row shouldBe rows.size

  end checkVectors

  private def nulls(c: ColumnVector): Seq[Boolean] = (0 until c.size).map(c.isNull)

  // `shouldMatch` checks only that the pattern matches, so the vectors are matched here and every
  // other kind of vector fails
  private def unexpected(c: ColumnVector): Nothing = fail(
    s"unexpected vector ${c.getClass} for ${c.name}"
  )

  batchOptions.foreach { case (label, options) =>
    test(s"read flat columns in batches of ${label}") {
      val batches = readBatches(options)
      checkVectors(batches)
      batches.flatMap(b => ParquetFile.rows(b, Weaver.of[FlatRow], options)) shouldBe rows
    }
  }

  test("read flat columns when a byte limit cuts batches") {
    val f = Parquet.open(bytes)
    try
      f.batchSoftLimit = 700
      val batches = f.readBatches().toSeq
      (batches.size > f.numRowGroups) shouldBe true
      checkVectors(batches)
    finally
      f.close()
  }

  test("read a file whose row group has one page per column") {
    // One large page per chunk: the last page sizes the buffers exactly
    val data = Parquet.writeBytes(rows, ParquetWriteOptions.default.withPageSize(16 * 1024 * 1024))
    val f    = Parquet.open(data)
    try
      f.numRowGroups shouldBe 1
      checkVectors(f.readBatches().toSeq)
    finally
      f.close()
  }

end FlatColumnTest

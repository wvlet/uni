package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class PriceInner(amount: Option[BigDecimal], tag: String) derives Weaver

case class LargePageRow(
    id: Long,
    // Few distinct values with NULLs: dictionary-encoded pages far longer than one index block
    store: Option[String],
    qty: Option[Long],
    // INT32 decimals, read as Long: one dictionary-encoded and nullable, one distinct and required
    discount: Option[BigDecimal],
    price: BigDecimal,
    // An INT32 decimal below a struct stays on the Int path
    inner: Option[PriceInner]
) derives Weaver

/**
  * Pages that hold many more values than one block of dictionary indices, read whole and in
  * batches. The values must come out the same however the pages are cut.
  */
class LargePageTest extends UniTest:
  private val n = 10000

  private val rows: Seq[LargePageRow] = (0 until n).map { i =>
    LargePageRow(
      id = i.toLong,
      store = Option.when(i % 5 != 2)(s"store-${i % 37}"),
      qty = Option.when(i % 7 != 0)((i % 50).toLong),
      discount = Option.when(i % 3 != 0)(BigDecimal(i % 100) / BigDecimal(4)),
      price = BigDecimal(i) * BigDecimal("1.25") - BigDecimal(100),
      inner =
        Option.when(i % 11 != 0)(
          PriceInner(Option.when(i % 2 == 0)(BigDecimal(i % 1000) / BigDecimal(100)), s"t${i % 3}")
        )
    )
  }

  // One page per column chunk and one row group, so a dictionary page spans many index blocks
  private val bytes = Parquet.writeBytes(
    rows,
    ParquetWriteOptions
      .default
      .withDecimal(7, 2)
      .withPageSize(16 * 1024 * 1024)
      .withRowGroupSize(64 * 1024 * 1024)
  )

  private val asLong = ParquetReadOptions.default.withInt32DecimalAsLong(true)

  test("write one row group with a single page per column") {
    val f = Parquet.open(bytes)
    try
      f.numRowGroups shouldBe 1
      f.schema.toString shouldContain "int32 discount (Decimal(2,7))"
    finally
      f.close()
  }

  test("read rows back from pages longer than a block of dictionary indices") {
    val f = Parquet.open(bytes)
    try
      f.readAs[LargePageRow]().toSeq shouldBe rows
      f.readAs[LargePageRow](asLong).toSeq shouldBe rows
      f.readAs[LargePageRow](asLong.withBatchSize(777)).toSeq shouldBe rows
    finally
      f.close()
  }

  test("decode top-level INT32 decimals into Long vectors of exactly the row count") {
    val f = Parquet.open(bytes)
    try
      val batch = f.readRowGroup(0, asLong)
      batch.numRows shouldBe n
      batch.column("discount") shouldMatch { case c: LongColumn =>
        c.values.length shouldBe n
        (0 until n).foreach { i =>
          if i % 3 == 0 then
            c.isNull(i) shouldBe true
          else
            c.getValue(i) shouldBe (BigDecimal(i % 100) / BigDecimal(4)).setScale(2)
        }
      }
      batch.column("price") shouldMatch { case c: LongColumn =>
        c.values.length shouldBe n
        c.getLong(9) shouldBe -8875L
      }
      batch.column("store") shouldMatch { case c: BinaryColumn =>
        c.isNull(2) shouldBe true
        c.getString(1) shouldBe "store-1"
        c.getString(n - 1) shouldBe s"store-${(n - 1) % 37}"
      }
    finally
      f.close()
  }

  test("read the same vectors in batches as in one row group") {
    val f = Parquet.open(bytes)
    try
      val whole   = f.readRowGroup(0, asLong)
      val batches = f.readBatches(asLong.withBatchSize(1500)).toSeq
      batches.map(_.numRows).sum shouldBe n
      for col <- Seq("id", "store", "qty", "discount", "price") do
        val values = batches.flatMap(b => (0 until b.numRows).map(b.column(col).getValue))
        values shouldBe (0 until n).map(whole.column(col).getValue)
    finally
      f.close()
  }

end LargePageTest

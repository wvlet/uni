package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class SmallPrice(id: Int, price: BigDecimal, discount: Option[BigDecimal]) derives Weaver

class Int32DecimalAsLongTest extends UniTest:
  private val rows = (0 until 5000).map { i =>
    SmallPrice(
      i,
      BigDecimal(i) * BigDecimal("1.25") - BigDecimal(100),
      if i % 3 == 0 then
        None
      else
        Some(BigDecimal(i % 100) / BigDecimal(4))
    )
  }

  private val bytes = Parquet.writeBytes(rows, ParquetWriteOptions.default.withDecimal(7, 2))

  test("store small decimals as INT32") {
    val f = Parquet.open(bytes)
    try f.schema.toString shouldContain "required int32 price (Decimal(2,7))"
    finally f.close()
  }

  test("read INT32 decimals as unscaled Long values") {
    val f = Parquet.open(bytes)
    try
      val opts  = ParquetReadOptions.default.withInt32DecimalAsLong(true)
      val batch = f.readBatches(opts).toSeq.head
      // A required column (the decoded values as is) and an optional one (with NULLs)
      batch.column("price") shouldMatch { case c: LongColumn =>
        c.getLong(0) shouldBe -10000L
        c.getLong(9) shouldBe -8875L
        c.getValue(9) shouldBe BigDecimal("-88.75")
      }
      batch.column("discount") shouldMatch { case c: LongColumn =>
        c.isNull(0) shouldBe true
        c.getLong(1) shouldBe 25L
        c.getValue(2) shouldBe BigDecimal("0.50")
      }
      batch.column("id") shouldMatch { case _: IntColumn =>
      }
      // Rows read back the same either way
      f.readAs[SmallPrice](opts).toSeq shouldBe rows
    finally
      f.close()
  }

  test("keep INT32 decimals as IntColumns by default") {
    val f = Parquet.open(bytes)
    try
      f.readBatches().toSeq.head.column("price") shouldMatch { case _: IntColumn =>
      }
    finally f.close()
  }

end Int32DecimalAsLongTest

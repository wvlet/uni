package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.parquet.ParquetFilter.*
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class DecRow(id: Int, qty: BigDecimal, disc: Option[BigDecimal], note: String) derives Weaver

/** Row filters and statistics on DECIMAL columns stored as INT32 and INT64 */
class DecimalFilterTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 4000).map { i =>
    DecRow(
      id = i,
      // 1.00 to 50.00, rising with the row number, so row groups have distinct ranges
      qty = BigDecimal(i / 80 + 1),
      disc = Option.when(i % 9 != 0)(BigDecimal(i % 11) / 100),
      note = s"n${i}"
    )
  }

  // qty is DECIMAL(15, 2) (INT64) and disc DECIMAL(9, 2) (INT32)
  private val bytes = Parquet.writeBytes(
    rows,
    ParquetWriteOptions
      .default
      .withRowGroupSize(16 * 1024)
      .withPageSize(2 * 1024)
      .withDecimal("qty", 15, 2)
      .withDecimal("disc", 9, 2)
  )

  private def ids(f: ParquetFilter, late: Boolean = true, int32AsLong: Boolean = false): Seq[Int] =
    val file = Parquet.open(bytes)
    try
      file.lateMaterialization = late
      file
        .readAs[DecRow](
          ParquetReadOptions.default.withFilter(f).withInt32DecimalAsLong(int32AsLong)
        )
        .map(_.id)
        .toSeq
    finally
      file.close()

  private def expected(p: DecRow => Boolean): Seq[Int] = rows.filter(p).map(_.id)

  test("store the test columns as INT64 and INT32 decimals") {
    val file = Parquet.open(bytes)
    try
      val types = file.schema.columns.map(c => c.name -> c.physicalType).toMap
      types("qty") shouldBe PhysicalType.INT64
      types("disc") shouldBe PhysicalType.INT32
      (file.numRowGroups > 3) shouldBe true
    finally
      file.close()
  }

  test("compare decimal columns with exact literals") {
    val filters: Seq[(ParquetFilter, DecRow => Boolean)] = Seq(
      (col("qty") < 24, _.qty < 24),
      (col("qty") === BigDecimal("7"), _.qty == 7),
      (col("qty") >= "49.5", _.qty >= BigDecimal("49.5")),
      (
        col("disc") >= BigDecimal("0.05") && col("disc") <= BigDecimal("0.07"),
        r => r.disc.exists(d => d >= BigDecimal("0.05") && d <= BigDecimal("0.07"))
      ),
      (col("disc") === java.math.BigDecimal("0.03"), _.disc.contains(BigDecimal("0.03"))),
      (col("qty") < 10L && col("disc") > 0.toShort, r => r.qty < 10 && r.disc.exists(_ > 0)),
      (col("disc").isNull || col("disc") === 0, r => r.disc.forall(_ == 0))
    )
    for
      (f, p)      <- filters
      late        <- Seq(true, false)
      int32AsLong <- Seq(true, false)
    do
      ids(f, late, int32AsLong) shouldBe expected(p)
  }

  test("compare literals finer than the column's scale between two values") {
    // 0.055 lies between 0.05 and 0.06
    ids(col("disc") < BigDecimal("0.055")) shouldBe expected(_.disc.exists(_ <= BigDecimal("0.05")))
    ids(col("disc") > BigDecimal("0.055")) shouldBe expected(_.disc.exists(_ >= BigDecimal("0.06")))
    ids(col("disc") === BigDecimal("0.055")) shouldBe Seq.empty
    ids(col("disc") <= BigDecimal("-0.001")) shouldBe Seq.empty
    ids(col("disc") >= BigDecimal("-0.001")) shouldBe expected(_.disc.isDefined)
  }

  test("compare literals beyond every stored value") {
    val huge = BigDecimal("1e30")
    ids(col("qty") < huge) shouldBe rows.map(_.id)
    ids(col("qty") > huge) shouldBe Seq.empty
    ids(col("qty") > -huge) shouldBe rows.map(_.id)
    ids(col("qty") === -huge) shouldBe Seq.empty
    // Decided without building the literal's digits
    ids(col("qty") < "1e999999999") shouldBe rows.map(_.id)
    ids(col("qty") < "-1e999999999") shouldBe Seq.empty
    ids(col("disc") > "1e-999999999") shouldBe expected(_.disc.exists(_ > 0))
    ids(col("disc") > "-1e-999999999") shouldBe expected(_.disc.isDefined)
    ids(col("disc") === "0E-7") shouldBe expected(_.disc.contains(BigDecimal(0)))
  }

  test("skip row groups by decimal statistics") {
    val file = Parquet.open(bytes)
    try
      val all  = file.selectRowGroups()
      val some = file.selectRowGroups(
        ParquetReadOptions.default.withRowGroupFilter(col("qty") <= BigDecimal("3.5"))
      )
      (some.size < all.size) shouldBe true
      some.head shouldBe 0
      file.columnStatistics("qty").min shouldBe Some(BigDecimal("1.00"))
      file.columnStatistics("qty").max shouldBe Some(BigDecimal("50.00"))
    finally
      file.close()
  }

  test("reject literals that don't convert without loss") {
    val e = intercept[IllegalArgumentException](ids(col("qty") < 1.5))
    e.getMessage shouldContain "BigDecimal"
    intercept[IllegalArgumentException](ids(col("qty") === "1.5x"))
    // DECIMAL stored as a fixed-length byte array still isn't compared
    val big = Parquet.open(Parquet.writeBytes(Seq(FilterPrice(1, BigDecimal("1.5")))))
    try
      intercept[IllegalArgumentException](
        big.readRows(ParquetReadOptions.default.withFilter(col("price") > 1)).toSeq
      ).getMessage shouldContain "withRowGroupFilter"
    finally big.close()
  }

end DecimalFilterTest

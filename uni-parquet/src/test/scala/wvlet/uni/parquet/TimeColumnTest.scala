package wvlet.uni.parquet

import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.TimeUnit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

import java.time.LocalTime

case class Shift(id: Long, start: LocalTime, end: Option[LocalTime]) derives Weaver

class TimeColumnTest extends UniTest:
  private val shifts = Seq(
    Shift(1L, LocalTime.of(9, 30), Some(LocalTime.of(17, 45, 30))),
    Shift(2L, LocalTime.of(0, 0), None),
    Shift(3L, LocalTime.of(23, 59, 59, 999999000), Some(LocalTime.of(12, 0, 0, 250000000)))
  )

  private def readShifts(bytes: Array[Byte]): Seq[Shift] =
    val f = Parquet.open(bytes)
    try f.readAs[Shift]().toSeq
    finally f.close()

  private def readRows(bytes: Array[Byte]): Seq[Map[String, Any]] =
    val f = Parquet.open(bytes)
    try f.readRows().toSeq
    finally f.close()

  test("map LocalTime fields to TIME(MICROS) with no zone") {
    ParquetSchema.of[Shift].field("start") shouldMatch {
      case Some(f: ParquetField.Primitive)
          if f.physicalType == PhysicalType.INT64 &&
            f.logicalType.contains(LogicalType.Time(false, TimeUnit.MICROS)) =>
    }
  }

  test("write and read back LocalTime fields") {
    val bytes = Parquet.writeBytes(shifts)
    readShifts(bytes) shouldBe shifts
    readRows(bytes).map(_("start")) shouldBe shifts.map(_.start)
  }

  test("keep the TIME logical type in the file footer") {
    val f = Parquet.open(Parquet.writeBytes(shifts))
    try
      f.schema.field("end") shouldMatch {
        case Some(f: ParquetField.Primitive)
            if f.logicalType.contains(LogicalType.Time(false, TimeUnit.MICROS)) =>
      }
    finally f.close()
  }

  test("drop nanoseconds below the column's unit") {
    val s = Shift(1L, LocalTime.of(8, 0, 0, 123456789), None)
    readShifts(Parquet.writeBytes(Seq(s))) shouldBe
      Seq(s.copy(start = LocalTime.of(8, 0, 0, 123456000)))
  }

  test("write TIME records in every unit") {
    val t      = LocalTime.of(10, 20, 30, 456000000)
    val schema = ParquetSchema
      .builder("record")
      .optional("ms", ParquetType.TIME_MILLIS)
      .optional("us", ParquetType.TIME_MICROS)
      .optional("ns", ParquetType.TIME_NANOS)
      .build()
    schema.field("ms") shouldMatch {
      case Some(f: ParquetField.Primitive) if f.physicalType == PhysicalType.INT32 =>
    }
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(out, schema)
    try
      w.write(Map("ms" -> t, "us" -> t, "ns" -> t))
      w.write("""{"ms": "10:20:30.456", "us": "10:20:30.456", "ns": "10:20:30.456"}""")
      // An integer is nanoseconds of the day, as the LocalTime Weaver reads it
      val n = t.toNanoOfDay
      w.write(s"""{"ms": ${n}, "us": ${n}, "ns": ${n}}""")
    finally
      w.close()
    val rows = readRows(out.toByteArray)
    rows.size shouldBe 3
    rows.foreach { r =>
      r("ms") shouldBe t
      r("us") shouldBe t
      r("ns") shouldBe t
    }
  }

  test("reject values that are not a time of day") {
    val schema = ParquetSchema.builder("record").optional("t", ParquetType.TIME_MICROS).build()
    val w      = Parquet.newRecordWriter(java.io.ByteArrayOutputStream(), schema)
    try
      intercept[ParquetException] {
        w.write(Map("t" -> "25:00"))
      }.getMessage shouldContain "t"
      intercept[ParquetException] {
        w.write(Map("t" -> -1L))
      }
      intercept[ParquetException] {
        w.write(Map("t" -> 86400L * 1000000000L))
      }
    finally
      w.close()
  }

end TimeColumnTest

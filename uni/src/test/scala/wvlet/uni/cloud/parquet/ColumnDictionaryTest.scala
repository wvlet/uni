package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class FlagRow(id: Long, flag: String, note: Option[String]) derives Weaver

/** Public dictionary and null information of read vectors */
class ColumnDictionaryTest extends UniTest:
  FileSystemInit.init()

  private val rows = (0 until 1000).map { i =>
    FlagRow(i.toLong, Seq("A", "N", "R")(i % 3), Option.when(i % 5 != 0)(s"n${i}"))
  }

  private val file = Parquet.writeBytes(rows)

  private def batch: ColumnBatch =
    val in = Parquet.open(file)
    try in.readBatches().next()
    finally in.close()

  test("should share one String per dictionary entry") {
    val b    = batch
    val flag = b.column("flag").asInstanceOf[BinaryColumn]
    flag.dictionarySize shouldBe 3
    (0 until flag.size).map(flag.getString) shouldBe rows.map(_.flag)
    // Rows 0 and 3 hold the same entry, so they share the String
    flag.dictionaryIndex(0) shouldBe flag.dictionaryIndex(3)
    flag.getString(0) shouldBeTheSameInstanceAs flag.getString(3)
  }

  test("should report no dictionary entry for null rows") {
    val note = batch.column("note").asInstanceOf[BinaryColumn]
    note.isNull(0) shouldBe true
    note.dictionaryIndex(0) shouldBe -1
    note.getString(1) shouldBe "n1"
  }

  test("should expose null flags without copying") {
    val b    = batch
    val note = b.column("note")
    val ns   = note.nullFlags
    ns shouldNotBe null
    (0 until note.size).map(ns(_)) shouldBe rows.map(_.note.isEmpty)
    b.column("id").nullFlags shouldBe null
  }

end ColumnDictionaryTest

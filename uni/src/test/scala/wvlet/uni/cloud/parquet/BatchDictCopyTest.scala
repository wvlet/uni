package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystemInit
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class DictRow(
    id: Long,
    flag: String,
    city: Option[String],
    tags: Seq[String],
    attrs: Map[String, String]
) derives Weaver

/**
  * The batch writer reuses the dictionary entries that read vectors carry. Every copy here must be
  * byte for byte the file written from the same batches without that information.
  */
class BatchDictCopyTest extends UniTest:
  FileSystemInit.init()

  private def rows(n: Int, flags: Seq[String]): Seq[DictRow] = (0 until n).map { i =>
    DictRow(
      i.toLong,
      flags(i % flags.size),
      Option.when(i % 4 != 0)(s"city-${i % 5}"),
      (0 until i % 3).map(j => s"tag-${(i + j) % 6}"),
      (0 until i % 4).map(j => s"k${j}" -> s"v${(i * j) % 7}").toMap
    )
  }

  // Several row groups, so each has its own dictionaries
  private val sourceOptions = ParquetWriteOptions.default.withRowGroupSize(2 * 1024)
  private val fileA         = Parquet.writeBytes(rows(2000, Seq("A", "N", "R")), sourceOptions)
  private val fileB         = Parquet.writeBytes(rows(700, Seq("R", "Z", "N", "Q")), sourceOptions)

  private def batches(file: Array[Byte]): Seq[ColumnBatch] =
    val in = Parquet.open(file)
    try in.readBatches().toIndexedSeq
    finally in.close()

  /** Drops the reader's dictionary from every binary vector */
  private def strip(v: ColumnVector): Unit =
    v match
      case b: BinaryColumn =>
        b.dictionary = null
        b.dictIndices = null
      case l: ListColumn =>
        strip(l.elements)
      case m: MapColumn =>
        strip(m.keys)
        strip(m.values)
      case s: StructColumn =>
        s.children.foreach(strip)
      case _ =>

  private def withDict(v: ColumnVector): Int =
    v match
      case b: BinaryColumn if b.dictIndices != null =>
        b.dictIndices.count(_ >= 0)
      case l: ListColumn =>
        withDict(l.elements)
      case m: MapColumn =>
        withDict(m.keys) + withDict(m.values)
      case s: StructColumn =>
        s.children.map(withDict).sum
      case _ =>
        0

  private def write(bs: Seq[ColumnBatch], options: ParquetWriteOptions): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newBatchWriter(out, Parquet.open(fileA).schema, options)
    try w.writeAll(bs)
    finally w.close()
    out.toByteArray

  /** Copies the batches with and without the reader's dictionaries, and compares the files */
  private def check(
      files: Seq[Array[Byte]],
      options: ParquetWriteOptions,
      edit: Seq[ColumnBatch] => Unit = _ => ()
  ): Array[Byte] =
    val fast = files.flatMap(batches)
    edit(fast)
    fast.flatMap(_.columns).map(withDict).sum > 0 shouldBe true
    val plain = files.flatMap(batches)
    edit(plain)
    plain.flatMap(_.columns).foreach(strip)
    val copied = write(fast, options)
    copied.toSeq shouldBe write(plain, options).toSeq
    copied

  test("copy across the reader's row groups into larger ones") {
    val copied = check(Seq(fileA), ParquetWriteOptions.default)
    Parquet.readAs[DictRow](copied) shouldBe Parquet.readAs[DictRow](fileA)
    Parquet.open(copied).rowGroups.size shouldBe 1
  }

  test("copy into row groups smaller than the reader's") {
    val copied = check(Seq(fileA), ParquetWriteOptions.default.withRowGroupSize(512))
    (Parquet.open(copied).rowGroups.size > Parquet.open(fileA).rowGroups.size) shouldBe true
    Parquet.readAs[DictRow](copied) shouldBe Parquet.readAs[DictRow](fileA)
  }

  test("fall back to PLAIN inside a row group") {
    val copied = check(Seq(fileA), ParquetWriteOptions.default.withDictionaryPageSize(16))
    Parquet.readAs[DictRow](copied) shouldBe Parquet.readAs[DictRow](fileA)
  }

  test("switch between dictionaries A, B, and A again") {
    val copied = check(Seq(fileA, fileB, fileA), ParquetWriteOptions.default)
    Parquet.readAs[DictRow](copied) shouldBe
      Parquet.readAs[DictRow](fileA) ++
      Parquet.readAs[DictRow](fileB) ++ Parquet.readAs[DictRow](fileA)
  }

  test("write edited data, not the reader's entry") {
    // Rows of one entry share their bytes in the reader's dictionary; edit the copy in the batch
    def edit(bs: Seq[ColumnBatch]): Unit =
      val flag =
        bs.head
          .columns
          .collectFirst {
            case b: BinaryColumn if b.name == "flag" =>
              b
          }
          .get
      flag.data(flag.offsets(7)) = 'X'.toByte
    val copied = check(Seq(fileA), ParquetWriteOptions.default, edit)
    val flags  = Parquet.readAs[DictRow](copied).map(_.flag)
    flags.take(8) shouldBe Seq("A", "N", "R", "A", "N", "R", "A", "X")
    flags.count(_ == "X") shouldBe 1
  }

end BatchDictCopyTest

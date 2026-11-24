package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.codec.Compressors
import wvlet.uni.cloud.parquet.column.PageBuffers
import wvlet.uni.cloud.parquet.format.CompressionCodec
import wvlet.uni.io.FileSystemInit
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class PageRow(
    id: Long,
    qty: Int,
    price: Option[Long],
    ratio: Option[Float],
    score: Option[Double],
    flag: Option[Boolean],
    // Few distinct values, so the column is dictionary-encoded
    store: Option[String],
    // Distinct values, so the dictionary overflows into PLAIN pages
    note: Option[String],
    tags: Seq[String]
) derives Weaver

/**
  * Compressed pages decode into buffers that [[PageBuffers]] hands from one column chunk to the
  * next, and uncompressed pages are read in place. A buffer reused too early would show up as wrong
  * values, so these read many pages of many chunks, of several files at once.
  */
class PageBufferTest extends UniTest:
  FileSystemInit.init()

  private val rows: Seq[PageRow] = (0 until 4000).map { i =>
    PageRow(
      id = i.toLong * 1000003L,
      qty = i % 100 - 50,
      price = Option.when(i % 7 != 3)(i * 1000L),
      ratio = Option.when(i % 2 == 0)(i / 4f),
      score = Option.when(i % 3 != 0)(i * 0.5),
      flag = Option.when(i % 5 != 0)(i % 3 == 0),
      store = Option.when(i % 4 != 1)(s"store-${i % 9}"),
      note = Option.when(i % 6 != 2)(s"note ${i} ${"x" * (i % 13)}"),
      tags = (0 until i % 4).map(k => s"t${(i + k) % 11}")
    )
  }

  private val codecs = Seq(
    CompressionCodec.UNCOMPRESSED,
    CompressionCodec.SNAPPY,
    CompressionCodec.LZ4_RAW,
    CompressionCodec.ZSTD,
    CompressionCodec.GZIP
  ).filter(Compressors.isSupported)

  // Small pages and row groups, so every chunk has several pages and the file several row groups
  private def write(codec: CompressionCodec): Array[Byte] = Parquet.writeBytes(
    rows,
    ParquetWriteOptions
      .default
      .withCompression(codec)
      .withPageSize(1024)
      .withRowGroupSize(32 * 1024)
      .withDictionaryPageSize(1024)
  )

  private val files: Map[CompressionCodec, Array[Byte]] = codecs.map(c => c -> write(c)).toMap

  private def read(bytes: Array[Byte], options: ParquetReadOptions): Seq[PageRow] =
    val f = Parquet.open(bytes)
    try f.readAs[PageRow](options).toSeq
    finally f.close()

  test("write several row groups with several pages per column") {
    val f = Parquet.open(files(CompressionCodec.SNAPPY))
    try (f.numRowGroups > 2) shouldBe true
    finally f.close()
  }

  codecs.foreach { codec =>
    test(s"read ${codec} pages whole and in batches") {
      Seq(None, Some(1), Some(7), Some(333)).foreach { size =>
        val options =
          size.fold(ParquetReadOptions.default)(ParquetReadOptions.default.withBatchSize)
        read(files(codec), options) shouldBe rows
      }
    }
  }

  test("read files of every codec at once, a batch of each in turn") {
    // Every cursor holds a buffer at once, and chunks of one file end while others are read
    val open = codecs.map(c => Parquet.open(files(c)))
    try
      val its = open.map(_.readAs[PageRow](ParquetReadOptions.default.withBatchSize(17)))
      val out = codecs.map(_ => Seq.newBuilder[PageRow])
      while its.exists(_.hasNext) do
        its
          .zip(out)
          .foreach { (it, b) =>
            var k = 0
            while k < 50 && it.hasNext do
              b += it.next()
              k += 1
          }
      out.foreach(b => b.result() shouldBe rows)
    finally
      open.foreach(_.close())
  }

  test("read data page v2 files alongside v1 files") {
    val v2       = TestFiles.bytes("apache/datapage_v2.snappy.parquet")
    val expected = Parquet.readRows(v2)
    (0 until 3).foreach { _ =>
      Parquet.readRows(v2) shouldBe expected
      read(files(CompressionCodec.ZSTD), ParquetReadOptions.default) shouldBe rows
    }
  }

  test("reuse a released buffer of the same size") {
    val b = PageBuffers.borrow(777_777)
    b.length shouldBe 777_777
    PageBuffers.release(b)
    (PageBuffers.borrow(777_777) eq b) shouldBe true
  }

  test("hand out a borrowed buffer only once") {
    val a = PageBuffers.borrow(654_321)
    PageBuffers.release(a)
    val b = PageBuffers.borrow(654_321)
    val c = PageBuffers.borrow(654_321)
    (b eq c) shouldBe false
    PageBuffers.release(b)
    PageBuffers.release(c)
  }

  test("don't keep buffers larger than the cap") {
    val big = PageBuffers.borrow(PageBuffers.MaxBufferBytes + 1)
    PageBuffers.release(big)
    (PageBuffers.borrow(PageBuffers.MaxBufferBytes + 1) eq big) shouldBe false
  }

  test("keep the pooled bytes under the cap") {
    val size = PageBuffers.MaxBufferBytes
    val bufs = (0 until 8).map(_ => PageBuffers.borrow(size))
    bufs.foreach(PageBuffers.release)
    // At most MaxPooledBytes / size of them come back
    val again = (0 until 8).map(_ => PageBuffers.borrow(size))
    val kept  = again.count(a => bufs.exists(_ eq a))
    (kept <= (PageBuffers.MaxPooledBytes / size).toInt) shouldBe true
    (kept >= 1) shouldBe true
  }

end PageBufferTest

package wvlet.uni.cloud.parquet

import wvlet.uni.cloud.parquet.codec.Compressors
import wvlet.uni.cloud.parquet.column.FetchBuffers
import wvlet.uni.cloud.parquet.format.CompressionCodec
import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.test.UniTest

/**
  * Synchronous reads of local files fetch column chunks into buffers that [[FetchBuffers]] hands
  * from one row group to the next. Uncompressed pages decode in place, so a buffer handed out
  * before its row group's batches end would show up as wrong values.
  */
class FetchBufferTest extends UniTest:
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
    CompressionCodec.ZSTD
  ).filter(Compressors.isSupported)

  private val dir: IOPath = FileSystem.createTempDirectory("uni-fetch-buffers")

  private val files: Map[CompressionCodec, IOPath] =
    codecs
      .map { c =>
        val path  = dir / s"rows-${c}.parquet"
        val bytes = Parquet.writeBytes(
          rows,
          ParquetWriteOptions
            .default
            .withCompression(c)
            .withPageSize(1024)
            .withRowGroupSize(32 * 1024)
            .withDictionaryPageSize(1024)
        )
        FileSystem.writeBytes(path, bytes)
        c -> path
      }
      .toMap

  override def afterAll: Unit = FileSystem.deleteRecursively(dir)

  codecs.foreach { codec =>
    test(s"read ${codec} files whole, by row group, and in batches") {
      val f = Parquet.open(files(codec))
      try
        (f.numRowGroups > 2) shouldBe true
        (0 until 3).foreach { _ =>
          f.readAs[PageRow]().toSeq shouldBe rows
          f.readAs[PageRow](ParquetReadOptions.default.withBatchSize(17)).toSeq shouldBe rows
          val byGroup = (0 until f.numRowGroups).flatMap(g => f.readRowGroup(g).rows)
          byGroup shouldBe f.readRows().toSeq
        }
      finally
        f.close()
    }
  }

  test("read row groups of every codec at once, a batch of each in turn") {
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

  test("give the fetched buffer back once a row group is read") {
    val f = Parquet.open(files(CompressionCodec.SNAPPY))
    try
      f.readRowGroup(0)
      // The pool now holds the row group's buffer, so a small borrow reuses an array
      val b = FetchBuffers.borrow(1)
      (b.length > 1) shouldBe true
      FetchBuffers.release(b)
    finally
      f.close()
  }

end FetchBufferTest

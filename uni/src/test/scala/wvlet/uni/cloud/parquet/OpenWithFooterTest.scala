package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class FooterRow(id: Long, name: String) derives Weaver

class OpenWithFooterTest extends UniTest:
  FileSystemInit.init()

  private var dirs: List[IOPath] = Nil

  override protected def afterAll: Unit = dirs.foreach(FileSystem.deleteRecursively)

  private val rows  = (0 until 100).map(i => FooterRow(i.toLong, s"name-${i}"))
  private val bytes = Parquet.writeBytes(rows)

  /** A source that records whether it was closed */
  private class TrackedSource(underlying: ByteSource) extends ByteSource:
    var closed                                       = false
    def name: String                                 = underlying.name
    def size: Long                                   = underlying.size
    def read(offset: Long, length: Int): Array[Byte] = underlying.read(offset, length)
    override def close(): Unit                       = closed = true

  test("reuse a parsed footer for a new source") {
    val first  = Parquet.open(bytes)
    val footer =
      try first.metadata
      finally first.close()

    val src  = TrackedSource(ByteSource.ofBytes(bytes))
    val file = ParquetFile.open(src, footer)
    try
      (file.metadata eq footer) shouldBe true
      file.numRows shouldBe 100L
      file.schema.toString shouldBe first.schema.toString
      file.readAs[FooterRow]().toSeq shouldBe rows
    finally
      file.close()
    src.closed shouldBe true
  }

  test("read asynchronously with a reused footer") {
    val first = Parquet.open(bytes)
    first.close()
    val file = ParquetFile.open(ByteSource.ofBytes(bytes), first.metadata)
    file
      .readAllAsync[FooterRow]()
      .map { read =>
        file.close()
        read shouldBe rows
      }
  }

  test("open a local file with a reused footer") {
    if FileSystem.isBrowser then
      skip("browsers have no local files")
    val dir = FileSystem.createTempDirectory("uni-parquet-open-footer")
    dirs = dir :: dirs
    val path = dir / "rows.parquet"
    FileSystem.writeBytes(path, bytes)

    val first = Parquet.open(path)
    first.close()
    val file = Parquet.open(path.toString, first.metadata)
    try file.readAs[FooterRow]().toSeq shouldBe rows
    finally file.close()
  }

  test("a stale footer pointing past the end of the file fails") {
    val big    = Parquet.open(Parquet.writeBytes((0 until 5000).map(i => FooterRow(i, s"n${i}"))))
    val footer = big.metadata
    big.close()
    val file = ParquetFile.open(ByteSource.ofBytes(bytes), footer)
    try intercept[ParquetException] {
        file.readAs[FooterRow]().toSeq
      }
    finally file.close()
  }

end OpenWithFooterTest

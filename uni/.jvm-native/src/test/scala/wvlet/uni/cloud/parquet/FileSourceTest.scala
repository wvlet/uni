package wvlet.uni.cloud.parquet

import wvlet.uni.io.FileSystem
import wvlet.uni.io.FileSystemInit
import wvlet.uni.io.IOPath
import wvlet.uni.test.UniTest

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
  * Local files are read with positional reads, so threads that scan row groups of one file read at
  * the same time. Each thread must still get the bytes of its own range
  */
class FileSourceTest extends UniTest:
  FileSystemInit.init()

  private val dir: IOPath       = FileSystem.createTempDirectory("uni-file-source")
  private val path: IOPath      = dir / "bytes.bin"
  private val data: Array[Byte] = Array.tabulate(1 << 20)(i => ((i * 31) ^ (i >>> 8)).toByte)
  FileSystem.writeBytes(path, data)

  override def afterAll: Unit = FileSystem.deleteRecursively(dir)

  private def expected(offset: Int, length: Int): Seq[Byte] =
    data.slice(offset, offset + length).toSeq

  test("should read the requested range of a local file") {
    val src = ByteSource.ofFile(path)
    try
      src.size shouldBe data.length.toLong
      src.read(12345, 1000).toSeq shouldBe expected(12345, 1000)
      src.read(data.length - 10, 10).toSeq shouldBe expected(data.length - 10, 10)
      src.read(0, 0).length shouldBe 0
    finally
      src.close()
  }

  test("should give each thread its own range when many threads read one file") {
    val src  = ByteSource.ofFile(path)
    val pool = Executors.newFixedThreadPool(8)
    try
      val tasks = (0 until 64).map { t =>
        pool.submit(
          new Callable[Boolean]:
            def call(): Boolean = (0 until 50).forall { k =>
              val offset = ((t * 7919 + k * 104729) % (data.length - 4096)).abs
              val length = 1 + (t * 13 + k * 17)    % 4096
              val buf    = new Array[Byte](length)
              src.readInto(offset, length, buf)
              buf.toSeq == expected(offset, length)
            }
        )
      }
      tasks.forall(_.get()) shouldBe true
    finally
      pool.shutdown()
      src.close()
  }

  test("should reject a range outside the file") {
    val src = ByteSource.ofFile(path)
    try
      intercept[ParquetException] {
        src.read(data.length - 5, 10)
      }
      intercept[ParquetException] {
        src.read(-1, 10)
      }
    finally
      src.close()
  }

end FileSourceTest

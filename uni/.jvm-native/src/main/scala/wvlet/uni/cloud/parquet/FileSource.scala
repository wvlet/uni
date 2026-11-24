package wvlet.uni.cloud.parquet

import wvlet.uni.io.IOPath

import java.io.RandomAccessFile

private[parquet] object FileSource:
  def open(path: IOPath): ByteSource = RandomAccessFileSource(path)

private[parquet] final class RandomAccessFileSource(path: IOPath) extends ByteSource:
  private val file = RandomAccessFile(path.path, "r")

  def name: String = path.path
  def size: Long   = file.length()

  def read(offset: Long, length: Int): Array[Byte] =
    checkRange(offset, length)
    val buf = new Array[Byte](length)
    readInto(offset, length, buf)
    buf

  override private[parquet] def readsInto: Boolean = true

  override private[parquet] def readInto(offset: Long, length: Int, buf: Array[Byte]): Unit =
    synchronized {
      checkRange(offset, length)
      file.seek(offset)
      file.readFully(buf, 0, length)
    }

  private def checkRange(offset: Long, length: Int): Unit =
    if offset < 0 || length < 0 || offset + length > size then
      throw ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${name}")

  override def close(): Unit = file.close()

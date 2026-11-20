package wvlet.uni.parquet

import wvlet.uni.io.IOPath

import java.io.RandomAccessFile

private[parquet] object FileSource:
  def open(path: IOPath): ByteSource = RandomAccessFileSource(path)

private[parquet] final class RandomAccessFileSource(path: IOPath) extends ByteSource:
  private val file = RandomAccessFile(path.path, "r")

  def name: String = path.path
  def size: Long   = file.length()

  def read(offset: Long, length: Int): Array[Byte] = synchronized {
    if offset < 0 || length < 0 || offset + length > size then
      throw ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${name}")
    val buf = new Array[Byte](length)
    file.seek(offset)
    file.readFully(buf)
    buf
  }

  override def close(): Unit = file.close()

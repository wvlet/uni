package wvlet.uni.cloud.parquet

import wvlet.uni.io.IOPath

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private[parquet] object FileSource:
  def open(path: IOPath): ByteSource = FileChannelSource(path)

/**
  * A local file read with positional reads (`pread`), so that threads scanning row groups of the
  * same file read at once instead of taking turns on one file position. The size is read once, at
  * open
  */
private[parquet] final class FileChannelSource(path: IOPath) extends ByteSource:
  private val channel = FileChannel.open(Paths.get(path.path), StandardOpenOption.READ)
  val size: Long      = channel.size()

  def name: String = path.path

  def read(offset: Long, length: Int): Array[Byte] =
    checkRange(offset, length)
    val buf = new Array[Byte](length)
    readInto(offset, length, buf)
    buf

  override private[parquet] def readsInto: Boolean = true

  override private[parquet] def readInto(offset: Long, length: Int, buf: Array[Byte]): Unit =
    checkRange(offset, length)
    val dst = ByteBuffer.wrap(buf, 0, length)
    var pos = offset
    while dst.hasRemaining do
      val n = channel.read(dst, pos)
      if n < 0 then
        throw ParquetException.corrupt(s"${name} ended at ${pos}, before ${offset + length}")
      pos += n

  private def checkRange(offset: Long, length: Int): Unit =
    if offset < 0 || length < 0 || offset + length > size then
      throw ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${name}")

  override def close(): Unit = channel.close()

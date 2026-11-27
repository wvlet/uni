package wvlet.uni.cloud.parquet

import wvlet.uni.io.IOPath

import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private[parquet] object FileSource:
  def open(path: IOPath): ByteSource = FileChannelSource(path)

/**
  * A local file read with positional reads (`pread`), so that threads scanning row groups of the
  * same file read at once instead of taking turns on one file position. The size is read once, at
  * open.
  *
  * On the JVM, interrupting a thread inside a read closes the channel for every thread: the
  * interrupted read fails, and the other threads reopen the file and retry. Each read call is at
  * most [[FileChannelSource.MaxReadBytes]], since the JDK reads a heap buffer through a temporary
  * direct buffer of the requested size that it keeps per thread
  */
private[parquet] final class FileChannelSource(path: IOPath) extends ByteSource:
  @volatile
  private var channel: FileChannel = openChannel()

  @volatile
  private var closed: Boolean = false

  val size: Long = channel.size()

  private def openChannel(): FileChannel = FileChannel.open(
    Paths.get(path.path),
    StandardOpenOption.READ
  )

  /** The open channel in place of stale, which an interrupted read on another thread closed */
  private def reopen(stale: FileChannel): FileChannel = synchronized {
    if closed then
      throw ClosedChannelException()
    if channel eq stale then
      channel = openChannel()
    channel
  }

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
    var ch  = channel
    while dst.position() < length do
      dst.limit(math.min(length, dst.position() + FileChannelSource.MaxReadBytes))
      val n =
        try
          ch.read(dst, pos)
        catch
          case e: ClosedByInterruptException =>
            // This read was interrupted; the threads still reading reopen the file
            throw e
          case _: ClosedChannelException if !closed =>
            ch = reopen(ch)
            0
      if n < 0 then
        throw ParquetException.corrupt(s"${name} ended at ${pos}, before ${offset + length}")
      pos += n

  private def checkRange(offset: Long, length: Int): Unit =
    if offset < 0 || length < 0 || offset + length > size then
      throw ParquetException.corrupt(s"range [${offset}, ${offset + length}) is outside ${name}")

  override def close(): Unit = synchronized {
    closed = true
    channel.close()
  }

end FileChannelSource

private[parquet] object FileChannelSource:
  /** The most bytes one read call asks for */
  val MaxReadBytes: Int = 1 << 20

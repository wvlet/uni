package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ParquetException

/**
  * The bytes of one column chunk, which may be larger than an array. A page is always read as one
  * contiguous range, since a page's size is an i32 in the format.
  */
private[parquet] trait ChunkInput:
  def length: Long

  /**
    * Bytes [pos, pos + len) of the chunk as one contiguous range: an array and the offset of `pos`
    * in it. The caller keeps the range inside the chunk. The array may be reused by later calls.
    */
  def bytes(pos: Long, len: Int): (Array[Byte], Int)

private[parquet] object ChunkInput:

  /** A chunk held in a fetched range of the file */
  final class OfArray(buf: Array[Byte], offset: Int, val length: Long) extends ChunkInput:
    def bytes(pos: Long, len: Int): (Array[Byte], Int) = (buf, offset + pos.toInt)

  /**
    * A chunk fetched in consecutive segments of `segmentSize` bytes (the last may be shorter). A
    * range that crosses segments is copied into a new array.
    */
  final class OfSegments(segments: IndexedSeq[Array[Byte]], segmentSize: Int, val length: Long)
      extends ChunkInput:
    def bytes(pos: Long, len: Int): (Array[Byte], Int) =
      val i   = (pos / segmentSize).toInt
      val off = (pos % segmentSize).toInt
      if off + len <= segments(i).length then
        (segments(i), off)
      else
        val out    = new Array[Byte](len)
        var copied = 0
        var seg    = i
        var from   = off
        while copied < len do
          val s = segments(seg)
          val n = math.min(len - copied, s.length - from)
          System.arraycopy(s, from, out, copied, n)
          copied += n
          seg += 1
          from = 0
        (out, 0)

  /**
    * A chunk read on demand, one window of at least `windowSize` bytes at a time, so only the
    * window being decoded is in memory
    */
  final class Windowed(
      read: (Long, Int) => Array[Byte],
      start: Long,
      val length: Long,
      windowSize: Int
  ) extends ChunkInput:
    private var windowStart                            = 0L
    private var window                                 = Array.emptyByteArray
    def bytes(pos: Long, len: Int): (Array[Byte], Int) =
      if pos >= windowStart && pos + len <= windowStart + window.length then
        (window, (pos - windowStart).toInt)
      else
        val n = math.min(length - pos, math.max(len.toLong, windowSize.toLong)).toInt
        // Drop the old window before reading the next one
        window = Array.emptyByteArray
        window = read(start + pos, n)
        windowStart = pos
        if window.length < len then
          throw ParquetException.corrupt(
            s"the file ended before offset ${start + pos + len} of a column chunk"
          )
        (window, 0)

end ChunkInput

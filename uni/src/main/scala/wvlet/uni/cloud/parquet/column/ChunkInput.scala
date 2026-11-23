package wvlet.uni.cloud.parquet.column

import wvlet.uni.cloud.parquet.ParquetException

/**
  * The bytes of one column chunk, which may be larger than an array. A page is always read as one
  * contiguous range, since a page's size is an i32 in the format.
  */
private[parquet] trait ChunkInput:
  def length: Long

  /**
    * Bytes [pos, pos + len) of the chunk as one contiguous range: an array that holds `pos` at
    * [[offset]]. The caller keeps the range inside the chunk. The array may be reused by later
    * calls. Returning the offset separately keeps the page-read path from boxing a tuple.
    */
  def bytes(pos: Long, len: Int): Array[Byte]

  /** The offset of `pos` in the array the last [[bytes]] call returned */
  def offset: Int

private[parquet] object ChunkInput:

  /** A chunk held in a fetched range of the file */
  final class OfArray(buf: Array[Byte], start: Int, val length: Long) extends ChunkInput:
    var offset: Int                             = 0
    def bytes(pos: Long, len: Int): Array[Byte] =
      offset = start + pos.toInt
      buf

  /**
    * A chunk fetched in consecutive segments of `segmentSize` bytes (the last may be shorter). A
    * range that crosses segments is copied into a new array.
    */
  final class OfSegments(segments: IndexedSeq[Array[Byte]], segmentSize: Int, val length: Long)
      extends ChunkInput:
    var offset: Int                             = 0
    def bytes(pos: Long, len: Int): Array[Byte] =
      val i   = (pos / segmentSize).toInt
      val off = (pos % segmentSize).toInt
      if off + len <= segments(i).length then
        offset = off
        segments(i)
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
        offset = 0
        out

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
    private var windowStart                     = 0L
    private var window                          = Array.emptyByteArray
    var offset: Int                             = 0
    def bytes(pos: Long, len: Int): Array[Byte] =
      if pos >= windowStart && pos + len <= windowStart + window.length then
        offset = (pos - windowStart).toInt
        window
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
        offset = 0
        window

end ChunkInput

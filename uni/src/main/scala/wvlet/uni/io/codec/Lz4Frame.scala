package wvlet.uni.io.codec

/**
  * The LZ4 frame format (magic `0x184D2204`), as Arrow IPC's LZ4_FRAME compression uses: a frame
  * header, independent blocks compressed with [[Lz4Compressor]] (or stored raw when they do not
  * shrink), and an end mark. Frames carry the content size and no checksums.
  */
private[uni] object Lz4Frame:
  private final val Magic        = 0x184d2204
  private final val MaxBlockSize = 4 << 20
  // Version 01, independent blocks, content size present
  private final val Flags = 0x68
  // Block maximum size 4 MiB
  private final val BlockDescriptor = 0x70

  private def fail(msg: String): Nothing = throw CodecException.corrupt(s"LZ4 frame: ${msg}")

  def compress(src: Array[Byte], offset: Int, len: Int): Array[Byte] =
    val out = ByteBuf(len / 2 + 32)
    out.writeIntLE(Magic)
    val descStart = out.size
    out.writeByte(Flags)
    out.writeByte(BlockDescriptor)
    out.writeLongLE(len.toLong)
    val hc = (XxHash32.hash(out.array, descStart, out.size - descStart, 0) >>> 8) & 0xff
    out.writeByte(hc)
    var pos = 0
    while pos < len do
      val n     = math.min(MaxBlockSize, len - pos)
      val chunk =
        if offset + pos == 0 && n == src.length then
          src
        else
          java.util.Arrays.copyOfRange(src, offset + pos, offset + pos + n)
      val compressed = Lz4Compressor.compress(chunk, n)
      if compressed.length < n then
        out.writeIntLE(compressed.length)
        out.write(compressed)
      else
        // The high bit marks a block stored raw
        out.writeIntLE(n | 0x80000000)
        out.write(chunk, 0, n)
      pos += n
    out.writeIntLE(0)
    out.toByteArray

  /** Decodes one or more LZ4 frames into `expectedSize` bytes, or any size when it is negative */
  def decompress(src: Array[Byte], offset: Int, length: Int, expectedSize: Int): Array[Byte] =
    val end                 = offset + length
    val out                 = ByteBuf(math.max(expectedSize, 64))
    var pos                 = offset
    def int32(at: Int): Int =
      if at + 4 > end then
        fail("truncated")
      (src(at) & 0xff) |
        ((src(at + 1) & 0xff) << 8) |
        ((src(at + 2) & 0xff) << 16) |
        ((src(at + 3) & 0xff) << 24)
    while pos < end do
      val magic = int32(pos)
      pos += 4
      if (magic & 0xfffffff0) == 0x184d2a50 then
        // A skippable frame
        val n = int32(pos)
        pos += 4 + n
      else
        if magic != Magic then
          fail(f"bad magic number 0x${magic}%08x")
        if pos + 2 > end then
          fail("truncated header")
        val flg = src(pos) & 0xff
        val bd  = src(pos + 1) & 0xff
        pos += 2
        if (flg >>> 6) != 1 then
          fail(s"unsupported version ${flg >>> 6}")
        if (flg & 0x01) != 0 then
          fail("dictionaries are not supported")
        val blockChecksum   = (flg & 0x10) != 0
        val contentChecksum = (flg & 0x04) != 0
        if (flg & 0x08) != 0 then
          pos += 8
        pos += 1 // header checksum
        val blockMax = 1 << (8 + 2 * ((bd >>> 4) & 0x7))
        if blockMax < (64 << 10) then
          fail(s"bad block maximum size ${(bd >>> 4) & 0x7}")
        var block: Array[Byte] = null
        var done               = false
        while !done do
          val size = int32(pos)
          pos += 4
          if size == 0 then
            done = true
          else
            val n = size & 0x7fffffff
            if n > end - pos then
              fail("truncated block")
            if (size & 0x80000000) != 0 then
              out.write(src, pos, n)
            else
              if block == null then
                block = new Array[Byte](blockMax)
              out.write(block, 0, Lz4.decompressInto(src, pos, n, block, 0, blockMax))
            pos += n
            if blockChecksum then
              pos += 4
        if contentChecksum then
          pos += 4
      end if
    end while
    if expectedSize >= 0 && out.size != expectedSize then
      fail(s"decoded ${out.size} bytes, but ${expectedSize} were expected")
    out.toByteArray

  end decompress

end Lz4Frame

/** xxHash32, which the LZ4 frame header checksum uses */
private[uni] object XxHash32:
  private final val P1 = 0x9e3779b1
  private final val P2 = 0x85ebca77
  private final val P3 = 0xc2b2ae3d
  private final val P4 = 0x27d4eb2f
  private final val P5 = 0x165667b1

  private inline def le32(b: Array[Byte], i: Int): Int =
    (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8) | ((b(i + 2) & 0xff) << 16) | ((b(i + 3) & 0xff) << 24)

  private inline def round(acc: Int, v: Int): Int = Integer.rotateLeft(acc + v * P2, 13) * P1

  def hash(b: Array[Byte], offset: Int, len: Int, seed: Int): Int =
    val end = offset + len
    var i   = offset
    var h   =
      if len >= 16 then
        var v1 = seed + P1 + P2
        var v2 = seed + P2
        var v3 = seed
        var v4 = seed - P1
        while i <= end - 16 do
          v1 = round(v1, le32(b, i))
          v2 = round(v2, le32(b, i + 4))
          v3 = round(v3, le32(b, i + 8))
          v4 = round(v4, le32(b, i + 12))
          i += 16
        Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7) + Integer.rotateLeft(v3, 12) +
          Integer.rotateLeft(v4, 18)
      else
        seed + P5
    h += len
    while i <= end - 4 do
      h = Integer.rotateLeft(h + le32(b, i) * P3, 17) * P4
      i += 4
    while i < end do
      h = Integer.rotateLeft(h + (b(i) & 0xff) * P5, 11) * P1
      i += 1
    h ^= h >>> 15
    h *= P2
    h ^= h >>> 13
    h *= P3
    h ^= h >>> 16
    h

  end hash

end XxHash32

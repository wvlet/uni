package wvlet.uni.io.codec

/**
  * Literal and match copies for the Snappy and ZSTD decoders, done as two 8-byte moves when the
  * copy is at most 16 bytes, the common case in columnar data, or four for a literal of up to 32.
  * `System.arraycopy` has a fixed setup cost that dominates such short copies.
  *
  * A wide copy always moves 16 or 32 bytes, so it may write up to 31 bytes past the end of the
  * copy. That is safe because the decoders write their output front to back: bytes below the write
  * position are final, and the next copy starts at the end of this one and overwrites the
  * overshoot. The wide path runs only when all the bytes it moves stay inside the source array and
  * before `limit`, the end of the bytes the caller lets it write: `out.length`, or the output size
  * when the caller's buffer holds bytes past the output that must stay as they were.
  */
private[uni] object WideCopy:

  /** Copies `len` bytes from `src(sp)` to `out(op)`; the ranges don't overlap */
  def literal(src: Array[Byte], sp: Int, out: Array[Byte], op: Int, len: Int, limit: Int): Unit =
    if len <= 16 && sp <= src.length - 16 && op <= limit - 16 then
      Word8.copy(src, sp, out, op)
      Word8.copy(src, sp + 8, out, op + 8)
    else if len <= 32 && sp <= src.length - 32 && op <= limit - 32 then
      // Strings such as TPC-H comments, 17 to 32 bytes long
      Word8.copy(src, sp, out, op)
      Word8.copy(src, sp + 8, out, op + 8)
      Word8.copy(src, sp + 16, out, op + 16)
      Word8.copy(src, sp + 24, out, op + 24)
    else
      System.arraycopy(src, sp, out, op, len)

  /**
    * Copies `len` bytes from `op - offset` to `op`, as [[Lz77.copyMatch]] does. With an offset of 8
    * or more, each 8-byte move reads only bytes that are already final, even when the ranges
    * overlap.
    */
  def matchCopy(out: Array[Byte], op: Int, offset: Int, len: Int, limit: Int): Unit =
    if offset >= 8 && len <= 16 && op <= limit - 16 then
      val from = op - offset
      Word8.copy(out, from, out, op)
      Word8.copy(out, from + 8, out, op + 8)
    else
      Lz77.copyMatch(out, op, offset, len)

end WideCopy

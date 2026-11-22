package wvlet.uni.io.codec

/**
  * Literal and match copies for the Snappy and ZSTD decoders, done as two 8-byte moves when the
  * copy is at most 16 bytes, the common case in columnar data. `System.arraycopy` has a fixed setup
  * cost that dominates such short copies.
  *
  * A wide copy always moves 16 bytes, so it may write up to 15 bytes past the end of the copy. That
  * is safe because the decoders write their output front to back: bytes below the write position
  * are final, and the next copy starts at the end of this one and overwrites the overshoot. The
  * wide path runs only when all 16 bytes stay inside both arrays, so it never needs slack in the
  * output.
  */
private[uni] object WideCopy:

  /** Copies `len` bytes from `src(sp)` to `out(op)`; the ranges don't overlap */
  def literal(src: Array[Byte], sp: Int, out: Array[Byte], op: Int, len: Int): Unit =
    if len <= 16 && sp <= src.length - 16 && op <= out.length - 16 then
      Word8.copy(src, sp, out, op)
      Word8.copy(src, sp + 8, out, op + 8)
    else
      System.arraycopy(src, sp, out, op, len)

  /**
    * Copies `len` bytes from `op - offset` to `op`, as [[Lz77.copyMatch]] does. With an offset of 8
    * or more, each 8-byte move reads only bytes that are already final, even when the ranges
    * overlap.
    */
  def matchCopy(out: Array[Byte], op: Int, offset: Int, len: Int): Unit =
    if offset >= 8 && len <= 16 && op <= out.length - 16 then
      val from = op - offset
      Word8.copy(out, from, out, op)
      Word8.copy(out, from + 8, out, op + 8)
    else
      Lz77.copyMatch(out, op, offset, len)

end WideCopy

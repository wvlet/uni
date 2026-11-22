package wvlet.uni.io.codec

/**
  * Literal and match copies for the Snappy and ZSTD decoders. Scala.js has no fast unaligned 8-byte
  * access to a byte array, so this keeps `System.arraycopy` and [[Lz77.copyMatch]]. The JVM and
  * Scala Native versions copy short runs in 8-byte words.
  */
private[uni] object WideCopy:

  /** Copies `len` bytes from `src(sp)` to `out(op)`; the ranges don't overlap */
  inline def literal(src: Array[Byte], sp: Int, out: Array[Byte], op: Int, len: Int): Unit = System
    .arraycopy(src, sp, out, op, len)

  /** Copies `len` bytes from `op - offset` to `op`, as [[Lz77.copyMatch]] does */
  inline def matchCopy(out: Array[Byte], op: Int, offset: Int, len: Int): Unit = Lz77.copyMatch(
    out,
    op,
    offset,
    len
  )

end WideCopy

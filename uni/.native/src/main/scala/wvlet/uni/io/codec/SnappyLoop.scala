package wvlet.uni.io.codec

/**
  * The JVM has a fast loop for [[Snappy]] that depends on how C2 compiles it. Here the shared
  * decoder's own loop runs instead.
  */
private[uni] object SnappyLoop:
  inline val enabled = false

  /** Decodes nothing: returns `ip` in the high 32 bits and `op` in the low 32 */
  def run(src: Array[Byte], ip: Int, end: Int, out: Array[Byte], op: Int, limit: Int): Long =
    (ip.toLong << 32) | op

end SnappyLoop

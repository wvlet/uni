package wvlet.uni.io.codec

/** snappy-java is a JVM library, so [[Snappy]] always runs its own decoder here */
private[uni] object SnappyJava:
  inline val available = false

  def decompressInto(src: Array[Byte], offset: Int, length: Int, out: Array[Byte]): Int =
    throw UnsupportedOperationException("snappy-java runs only on the JVM")

end SnappyJava

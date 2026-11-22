package wvlet.uni.io.codec

/** Thrown when compressed data is malformed or uses a feature the codec does not support */
private[uni] class CodecException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

private[uni] object CodecException:
  def corrupt(message: String): CodecException = CodecException(
    s"Corrupt compressed data: ${message}"
  )

  def unsupported(feature: String): CodecException = CodecException(
    s"Unsupported compression feature: ${feature}"
  )

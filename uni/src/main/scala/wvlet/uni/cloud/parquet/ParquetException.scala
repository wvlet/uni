package wvlet.uni.cloud.parquet

/**
  * Thrown when a Parquet file is malformed, truncated, or uses a feature Uni does not support (for
  * example, encrypted columns or the LZO codec), and when a value written does not fit its column.
  */
class ParquetException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object ParquetException:
  /** Runs a codec call, turning its errors into ParquetExceptions with the same message */
  private[cloud] inline def fromCodec[A](inline body: A): A =
    try
      body
    catch
      case e: wvlet.uni.io.codec.CodecException =>
        throw ParquetException(e.getMessage, e)

  private[parquet] def unsupported(feature: String): ParquetException = ParquetException(
    s"Unsupported Parquet feature: ${feature}"
  )

  private[parquet] def corrupt(message: String): ParquetException = ParquetException(
    s"Corrupt Parquet data: ${message}"
  )

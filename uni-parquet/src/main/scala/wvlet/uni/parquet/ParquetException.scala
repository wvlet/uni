package wvlet.uni.parquet

/**
  * Thrown when a Parquet file is malformed, truncated, or uses a feature uni-parquet does not
  * support (for example, encrypted columns or the LZO codec), and when a value written does not fit
  * its column.
  */
class ParquetException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

object ParquetException:
  private[parquet] def unsupported(feature: String): ParquetException = ParquetException(
    s"Unsupported Parquet feature: ${feature}"
  )

  private[parquet] def corrupt(message: String): ParquetException = ParquetException(
    s"Corrupt Parquet data: ${message}"
  )

package wvlet.uni.parquet

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.io.IOPath
import wvlet.uni.rx.Rx
import wvlet.uni.weaver.Weaver

/**
  * Entry points for reading Apache Parquet files.
  *
  * {{{
  * import wvlet.uni.parquet.Parquet
  *
  * case class Event(id: Long, user: String)
  * val events: Seq[Event] = Parquet.readAs[Event]("events.parquet")
  * }}}
  */
object Parquet:

  /** Opens a local file (JVM, Scala Native, and Node.js) */
  def open(path: IOPath): ParquetFile = ParquetFile.open(ByteSource.ofFile(path))
  def open(path: String): ParquetFile = open(IOPath.parse(path))

  /** Opens a file held in memory */
  def open(bytes: Array[Byte]): ParquetFile = ParquetFile.open(ByteSource.ofBytes(bytes))

  def open(source: ByteSource): ParquetFile = ParquetFile.open(source)

  /** Opens a file over HTTP with Range requests (JVM, Scala Native, and Node.js) */
  def openUrl(url: String, config: HttpClientConfig = Http.client): ParquetFile = ParquetFile.open(
    ByteSource.ofUrl(url, config)
  )

  /** Opens a file asynchronously, for example over HTTP in a browser */
  def openAsync(source: AsyncByteSource): Rx[ParquetFile] = ParquetFile.openAsync(source)

  def openUrlAsync(url: String, config: HttpClientConfig = Http.client): Rx[ParquetFile] =
    ParquetFile.openAsync(ByteSource.ofUrlAsync(url, config))

  /** Reads every row of a local file into `A` */
  def readAs[A](path: String, options: ParquetReadOptions = ParquetReadOptions.default)(using
      weaver: Weaver[A]
  ): Seq[A] =
    val f = open(path)
    try f.readAs[A](options).toSeq
    finally f.close()

end Parquet

package wvlet.uni.parquet

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.parquet.writer.FileWriter
import wvlet.uni.surface.Surface
import wvlet.uni.rx.Rx
import wvlet.uni.storage.GcsConfig
import wvlet.uni.storage.S3Config
import wvlet.uni.weaver.Weaver

/**
  * Entry points for reading and writing Apache Parquet files.
  *
  * {{{
  * import wvlet.uni.parquet.Parquet
  *
  * case class Event(id: Long, user: String) derives Weaver
  * Parquet.write("events.parquet", Seq(Event(1, "aina")))
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

  /**
    * Opens an S3 object such as `s3://bucket/data.parquet` (JVM, Scala Native, and Node.js). Pass
    * [[S3Config.r2]], [[S3Config.gcs]], or [[S3Config.minio]] for S3-compatible stores.
    */
  def openS3(uri: String, config: S3Config = S3Config.default): ParquetFile = ParquetFile.open(
    ByteSource.ofS3(uri, config)
  )

  def openS3Async(uri: String, config: S3Config = S3Config.default): Rx[ParquetFile] = ParquetFile
    .openAsync(ByteSource.ofS3Async(uri, config))

  /**
    * Opens a Google Cloud Storage object such as `gs://bucket/data.parquet` (JVM, Scala Native, and
    * Node.js), with Application Default Credentials unless `config` sets others
    */
  def openGcs(uri: String, config: GcsConfig = GcsConfig.default): ParquetFile = ParquetFile.open(
    ByteSource.ofGcs(uri, config)
  )

  def openGcsAsync(uri: String, config: GcsConfig = GcsConfig.default): Rx[ParquetFile] =
    ParquetFile.openAsync(ByteSource.ofGcsAsync(uri, config))

  /** Opens a file asynchronously, for example over HTTP in a browser */
  def openAsync(source: AsyncByteSource): Rx[ParquetFile] = ParquetFile.openAsync(source)

  def openUrlAsync(url: String, config: HttpClientConfig = Http.client): Rx[ParquetFile] =
    ParquetFile.openAsync(ByteSource.ofUrlAsync(url, config))

  /** Reads every row of a local file into `A` */
  def readAs[A](path: String, options: ParquetReadOptions = ParquetReadOptions.default)(using
      weaver: Weaver[A]
  ): Seq[A] = withFile(open(path))(_.readAs[A](options))

  /** Reads every row of a file held in memory into `A` */
  def readAs[A](bytes: Array[Byte], options: ParquetReadOptions)(using weaver: Weaver[A]): Seq[A] =
    withFile(open(bytes))(_.readAs[A](options))

  def readAs[A](bytes: Array[Byte])(using weaver: Weaver[A]): Seq[A] = readAs[A](
    bytes,
    ParquetReadOptions.default
  )

  /**
    * Reads every row of a local file as a map from column name to value, in schema column order.
    * Values have their logical type applied, as [[ColumnVector.getValue]] returns them.
    */
  def readRows(
      path: String,
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Seq[Map[String, Any]] = withFile(open(path))(_.readRows(options))

  /** Reads every row of a file held in memory as a map from column name to value */
  def readRows(bytes: Array[Byte], options: ParquetReadOptions): Seq[Map[String, Any]] =
    withFile(open(bytes))(_.readRows(options))

  def readRows(bytes: Array[Byte]): Seq[Map[String, Any]] = readRows(
    bytes,
    ParquetReadOptions.default
  )

  /** Reads every row of a local file as a JSON object */
  def readJson(
      path: String,
      options: ParquetReadOptions = ParquetReadOptions.default
  ): Seq[JSONObject] = withFile(open(path))(_.readJson(options))

  /** Reads every row of a file held in memory as a JSON object */
  def readJson(bytes: Array[Byte], options: ParquetReadOptions): Seq[JSONObject] =
    withFile(open(bytes))(_.readJson(options))

  def readJson(bytes: Array[Byte]): Seq[JSONObject] = readJson(bytes, ParquetReadOptions.default)

  private def withFile[A](f: ParquetFile)(read: ParquetFile => Iterator[A]): Seq[A] =
    try read(f).toSeq
    finally f.close()

  // Writing

  /**
    * Writes case classes to a local file (JVM, Scala Native, and Node.js; in browsers, the
    * in-memory file system). The schema is derived from `A`; see [[ParquetSchema.of]].
    */
  inline def newWriter[A](path: String, options: ParquetWriteOptions = ParquetWriteOptions.default)(
      using weaver: Weaver[A]
  ): ParquetWriter[A] = newWriter[A](IOPath.parse(path), options)

  inline def newWriter[A](path: IOPath)(using weaver: Weaver[A]): ParquetWriter[A] = newWriter[A](
    path,
    ParquetWriteOptions.default
  )

  inline def newWriter[A](path: IOPath, options: ParquetWriteOptions)(using
      weaver: Weaver[A]
  ): ParquetWriter[A] = ParquetWriter.create(Surface.of[A], () => openFile(path), options, weaver)

  /** Writes case classes to an output stream, such as a `ByteArrayOutputStream` */
  inline def newWriter[A](out: java.io.OutputStream)(using weaver: Weaver[A]): ParquetWriter[A] =
    newWriter[A](out, ParquetWriteOptions.default)

  inline def newWriter[A](out: java.io.OutputStream, options: ParquetWriteOptions)(using
      weaver: Weaver[A]
  ): ParquetWriter[A] = ParquetWriter.create(Surface.of[A], () => out, options, weaver)

  /** Writes all rows to a local file */
  inline def write[A](
      path: String,
      rows: Iterable[A],
      options: ParquetWriteOptions = ParquetWriteOptions.default
  )(using weaver: Weaver[A]): Unit =
    val w = newWriter[A](IOPath.parse(path), options)
    try w.writeAll(rows)
    finally w.close()

  /** Writes all rows to a Parquet file in memory, which works on every platform */
  inline def writeBytes[A](
      rows: Iterable[A],
      options: ParquetWriteOptions = ParquetWriteOptions.default
  )(using weaver: Weaver[A]): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val w   = newWriter[A](out, options)
    try w.writeAll(rows)
    finally w.close()
    out.toByteArray

  /** Writes records (maps, JSON, or value sequences) with an explicit schema to a local file */
  def newRecordWriter(
      path: String,
      schema: ParquetSchema,
      options: ParquetWriteOptions = ParquetWriteOptions.default
  ): ParquetRecordWriter = newRecordWriter(IOPath.parse(path), schema, options)

  def newRecordWriter(path: IOPath, schema: ParquetSchema): ParquetRecordWriter = newRecordWriter(
    path,
    schema,
    ParquetWriteOptions.default
  )

  def newRecordWriter(
      path: IOPath,
      schema: ParquetSchema,
      options: ParquetWriteOptions
  ): ParquetRecordWriter = ParquetRecordWriter(FileWriter(schema, options, () => openFile(path)))

  def newRecordWriter(out: java.io.OutputStream, schema: ParquetSchema): ParquetRecordWriter =
    newRecordWriter(out, schema, ParquetWriteOptions.default)

  def newRecordWriter(
      out: java.io.OutputStream,
      schema: ParquetSchema,
      options: ParquetWriteOptions
  ): ParquetRecordWriter = ParquetRecordWriter(FileWriter(schema, options, () => out))

  private[parquet] def openFile(path: IOPath): java.io.OutputStream = FileSystem.writeStream(path)

end Parquet

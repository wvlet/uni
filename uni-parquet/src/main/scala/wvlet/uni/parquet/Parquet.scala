package wvlet.uni.parquet

import wvlet.uni.http.Http
import wvlet.uni.http.HttpClientConfig
import wvlet.uni.io.FileSystem
import wvlet.uni.io.IOPath
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.parquet.writer.FileWriter
import wvlet.uni.surface.Surface
import wvlet.uni.rx.Rx
import wvlet.uni.storage.AzureConfig
import wvlet.uni.storage.GcsConfig
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri
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

  /**
    * Opens an Azure Blob such as `az://container/data.parquet` (JVM, Scala Native, and Node.js).
    * The storage account and credentials come from `config`; by default from
    * `AZURE_STORAGE_CONNECTION_STRING`, or `AZURE_STORAGE_ACCOUNT` and the Azure credential chain.
    */
  def openAzure(uri: String, config: AzureConfig = AzureConfig.default): ParquetFile = ParquetFile
    .open(ByteSource.ofAzure(uri, config))

  def openAzureAsync(uri: String, config: AzureConfig = AzureConfig.default): Rx[ParquetFile] =
    ParquetFile.openAsync(ByteSource.ofAzureAsync(uri, config))

  /** Opens a file asynchronously, for example over HTTP in a browser */
  def openAsync(source: AsyncByteSource): Rx[ParquetFile] = ParquetFile.openAsync(source)

  def openUrlAsync(url: String, config: HttpClientConfig = Http.client): Rx[ParquetFile] =
    ParquetFile.openAsync(ByteSource.ofUrlAsync(url, config))

  // Datasets

  /**
    * Lists the Parquet files under a local directory or an object store prefix (`s3://`, `gs://`,
    * `az://`, or `azure://`) and reads them as one table, with `key=value` folders as partition
    * columns. The object store is created with its default config and closed with the dataset; pass
    * a store to [[dataset(store:wvlet\.uni\.storage\.ObjectStore,root:String)*]] for other configs
    * or S3-compatible stores. See [[ParquetDataset]].
    *
    * {{{
    * import wvlet.uni.parquet.RowGroupFilter.col
    * val ds     = Parquet.dataset("s3://bucket/events/")
    * val events = ds.readAs[Event](
    *   ParquetReadOptions.default.withRowGroupFilter(col("dt") >= "2026-11-15")
    * ).toSeq
    * ds.close()
    * }}}
    */
  def dataset(root: String): ParquetDataset = dataset(root, ParquetDatasetOptions.default)

  def dataset(root: String, options: ParquetDatasetOptions): ParquetDataset =
    if ParquetDataset.isObjectUri(root) then
      val store = storeFor(root)
      try
        dataset(store, root, options, ownsStore = true)
      catch
        case e: Throwable =>
          store.close()
          throw e
    else
      val path = IOPath.parse(root)
      ParquetDataset.build(root, ParquetDataset.listLocal(path), options, None, ownsStore = false)

  /** A dataset under a prefix of `store`, which the dataset leaves open when it closes */
  def dataset(store: ObjectStore, root: String): ParquetDataset = dataset(
    store,
    root,
    ParquetDatasetOptions.default
  )

  def dataset(store: ObjectStore, root: String, options: ParquetDatasetOptions): ParquetDataset =
    dataset(store, root, options, ownsStore = false)

  private def dataset(
      store: ObjectStore,
      root: String,
      options: ParquetDatasetOptions,
      ownsStore: Boolean
  ): ParquetDataset =
    val uri = ParquetDataset.objectRoot(root)
    ParquetDataset.build(
      uri.toString,
      ParquetDataset.listObjects(store, uri),
      options,
      Some(store),
      ownsStore
    )

  /** Lists the dataset asynchronously, as [[dataset(root:String)*]] does */
  def datasetAsync(root: String): Rx[ParquetDataset] = datasetAsync(
    root,
    ParquetDatasetOptions.default
  )

  def datasetAsync(root: String, options: ParquetDatasetOptions): Rx[ParquetDataset] =
    if ParquetDataset.isObjectUri(root) then
      Rx.single(storeFor(root))
        .flatMap { store =>
          datasetAsync(store, root, options, ownsStore = true).tapOnFailure(_ => store.close())
        }
    else
      Rx.single(dataset(root, options))

  def datasetAsync(store: ObjectStore, root: String): Rx[ParquetDataset] = datasetAsync(
    store,
    root,
    ParquetDatasetOptions.default
  )

  def datasetAsync(
      store: ObjectStore,
      root: String,
      options: ParquetDatasetOptions
  ): Rx[ParquetDataset] = datasetAsync(store, root, options, ownsStore = false)

  private def datasetAsync(
      store: ObjectStore,
      root: String,
      options: ParquetDatasetOptions,
      ownsStore: Boolean
  ): Rx[ParquetDataset] = Rx
    .single(ParquetDataset.objectRoot(root))
    .flatMap(uri =>
      ParquetDataset
        .listObjectsAsync(store, uri)
        .map(listed => ParquetDataset.build(uri.toString, listed, options, Some(store), ownsStore))
    )

  /** The default store for a URI's scheme */
  private def storeFor(root: String): ObjectStore =
    ObjectUri.parse(root).scheme match
      case "s3" =>
        ObjectStore.s3()
      case "gs" =>
        ObjectStore.gcs()
      case "az" | "azure" =>
        ObjectStore.azure()
      case other =>
        throw IllegalArgumentException(
          s"No default object store for ${other}:// (${root}). Pass an ObjectStore to Parquet.dataset."
        )

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

  /**
    * Writes [[ColumnBatch]]es with an explicit schema to a local file, column by column. A batch
    * read from another file can be written unchanged; see [[ParquetBatchWriter]] for the vectors
    * each column takes.
    *
    * {{{
    * val in = Parquet.open("events.parquet")
    * val w  = Parquet.newBatchWriter("copy.parquet", in.schema)
    * try w.writeAll(in.readBatches())
    * finally
    *   w.close()
    *   in.close()
    * }}}
    */
  def newBatchWriter(
      path: String,
      schema: ParquetSchema,
      options: ParquetWriteOptions = ParquetWriteOptions.default
  ): ParquetBatchWriter = newBatchWriter(IOPath.parse(path), schema, options)

  def newBatchWriter(path: IOPath, schema: ParquetSchema): ParquetBatchWriter = newBatchWriter(
    path,
    schema,
    ParquetWriteOptions.default
  )

  def newBatchWriter(
      path: IOPath,
      schema: ParquetSchema,
      options: ParquetWriteOptions
  ): ParquetBatchWriter = ParquetBatchWriter.create(schema, () => openFile(path), options)

  /** Writes [[ColumnBatch]]es to an output stream, such as a `ByteArrayOutputStream` */
  def newBatchWriter(out: java.io.OutputStream, schema: ParquetSchema): ParquetBatchWriter =
    newBatchWriter(out, schema, ParquetWriteOptions.default)

  def newBatchWriter(
      out: java.io.OutputStream,
      schema: ParquetSchema,
      options: ParquetWriteOptions
  ): ParquetBatchWriter = ParquetBatchWriter.create(schema, () => out, options)

  private[parquet] def openFile(path: IOPath): java.io.OutputStream = FileSystem.writeStream(path)

end Parquet

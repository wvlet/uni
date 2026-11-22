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

import scala.util.control.NonFatal

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
    * import wvlet.uni.parquet.ParquetFilter.col
    * val ds     = Parquet.dataset("s3://bucket/events/")
    * val events = ds.readAs[Event](
    *   ParquetReadOptions.default.withFilter(col("dt") >= "2026-11-15")
    * ).toSeq
    * ds.close()
    * }}}
    *
    * The root may be a glob pattern, such as `s3://bucket/events/dt=2026-11-{19,20}/country=JP`:
    * only the part before the first glob segment is listed, and paths below it are kept when they,
    * or one of their folders, match. `*` and `?` match within a segment, `[a-z]` and `[!a-z]` are
    * sets, `{a,b}` alternatives, `**` any number of folders, and `\` escapes a character. On
    * Windows, `\` in a local path separates folders instead; write a glob character in a set, such
    * as `[*]`, to match it literally.
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
      // A Windows path separates folders with `\`, so it can't also escape glob characters there.
      // The `?` of a `\\?\` prefix isn't a glob character either
      val glob =
        if ParquetDataset.isWindows then
          IOPath.stripWin32Prefix(root).replace('\\', '/')
        else
          root
      ParquetDataset.splitGlob(glob) match
        case Some((base, pattern)) =>
          // An absolute root, so listed paths and the root share one form for relativeTo
          val path = absolutePath(
            if base.isEmpty then
              "."
            else
              base
          )
          ParquetDataset.requireFiles(options, root)
          val matches = ParquetDataset.globMatcher(pattern)
          val listed  = ParquetDataset.listLocal(path)
          ParquetDataset.build(
            path.path,
            listed.filter(l => matches(l.segments)),
            options,
            None,
            ownsStore = false,
            source = s"matching ${root}",
            metadata = ParquetDataset.readMetadata(listed, options, None)
          )
        case None if ParquetDataset.isIcebergMetadataFile(root, options) =>
          val step = ParquetDataset.icebergFromMetadataFile(
            absolutePath(root).path,
            options,
            objects = false
          )
          ParquetDataset.icebergDataset(
            ParquetDataset.runIceberg(step, None, options.prefetch),
            options,
            None,
            ownsStore = false
          )
        case None =>
          val path   = absolutePath(root)
          val listed =
            options.tableFormat match
              case ParquetTableFormat.Delta =>
                ParquetDataset.underLog(ParquetDataset.listLocal(path.resolve(DeltaLog.LogDir)))
              case ParquetTableFormat.Iceberg =>
                ParquetDataset.under(
                  IcebergTable.MetadataDir,
                  ParquetDataset.listLocal(path.resolve(IcebergTable.MetadataDir))
                )
              case _ =>
                ParquetDataset.listLocal(path)
          if ParquetDataset.isIceberg(listed, options) && !ParquetDataset.isDelta(listed, options)
          then
            val step = ParquetDataset.icebergFromListing(
              path.path,
              listed,
              options,
              objects = false
            )
            ParquetDataset.icebergDataset(
              ParquetDataset.runIceberg(step, None, options.prefetch),
              options,
              None,
              ownsStore = false
            )
          else if ParquetDataset.isDelta(listed, options) then
            val plan = ParquetDataset.deltaPlan(path.path, listed, options)
            ParquetDataset.deltaDataset(
              path.path,
              plan,
              ParquetDataset.fetchLog(plan, None, options.prefetch),
              options,
              None,
              ownsStore = false
            )
          else
            ParquetDataset.requireFiles(options, root)
            ParquetDataset.build(
              path.path,
              listed,
              options,
              None,
              ownsStore = false,
              metadata = ParquetDataset.readMetadata(listed, options, None)
            )
          end if
      end match

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
    if ParquetDataset.isIcebergMetadataFile(root, options) then
      val step = ParquetDataset.icebergFromMetadataFile(
        ObjectUri.parse(root).toString,
        options,
        objects = true
      )
      return ParquetDataset.icebergDataset(
        ParquetDataset.runIceberg(step, Some(store), options.prefetch),
        options,
        Some(store),
        ownsStore
      )
    val (uri, matches, source) = objectPattern(root)
    val plainRoot              = source.isEmpty
    if !plainRoot then
      ParquetDataset.requireFiles(options, root)
    val listed =
      if !plainRoot then
        ParquetDataset.listObjects(store, uri)
      else
        tableListing(uri, options) match
          case Some(dir) =>
            ParquetDataset.under(dir, ParquetDataset.listObjects(store, subPrefix(uri, dir)))
          case None =>
            ParquetDataset.listObjects(store, uri)
    if plainRoot && ParquetDataset.isIceberg(listed, options) &&
      !ParquetDataset.isDelta(listed, options)
    then
      val step = ParquetDataset.icebergFromListing(uri.toString, listed, options, objects = true)
      ParquetDataset.icebergDataset(
        ParquetDataset.runIceberg(step, Some(store), options.prefetch),
        options,
        Some(store),
        ownsStore
      )
    else if plainRoot && ParquetDataset.isDelta(listed, options) then
      val plan = ParquetDataset.deltaPlan(uri.toString, listed, options)
      ParquetDataset.deltaDataset(
        uri.toString,
        plan,
        ParquetDataset.fetchLog(plan, Some(store), options.prefetch),
        options,
        Some(store),
        ownsStore
      )
    else
      ParquetDataset.requireFiles(options, root)
      ParquetDataset.build(
        uri.toString,
        listed.filter(l => matches(l.segments)),
        options,
        Some(store),
        ownsStore,
        source = source,
        metadata = ParquetDataset.readMetadata(listed, options, Some(store))
      )
    end if

  end dataset

  /** The folder that an explicit table format lists instead of the whole root */
  private def tableListing(uri: ObjectUri, options: ParquetDatasetOptions): Option[String] =
    options.tableFormat match
      case ParquetTableFormat.Delta =>
        Some(DeltaLog.LogDir)
      case ParquetTableFormat.Iceberg =>
        Some(IcebergTable.MetadataDir)
      case _ =>
        None

  private def subPrefix(uri: ObjectUri, dir: String): ObjectUri = uri.copy(key =
    s"${uri.key}${dir}/"
  )

  /**
    * The prefix to list for a root or glob pattern, the filter for the listed paths, and how an
    * empty result names the pattern
    */
  private def objectPattern(root: String): (ObjectUri, Seq[String] => Boolean, String) =
    ParquetDataset.splitGlob(root) match
      case Some((base, pattern)) =>
        (ParquetDataset.objectRoot(base), ParquetDataset.globMatcher(pattern), s"matching ${root}")
      case None =>
        (ParquetDataset.objectRoot(root), _ => true, "")

  /**
    * Reads the listed files as one dataset, as `read_parquet([...])` does in DuckDB. The paths are
    * all local, or all objects in one bucket. Partition columns come from the `key=value` folders
    * below the files' common parent. Files are read even when a name starts with `_` or `.`.
    */
  def dataset(paths: Seq[String]): ParquetDataset = dataset(paths, ParquetDatasetOptions.default)

  def dataset(paths: Seq[String], options: ParquetDatasetOptions): ParquetDataset =
    require(paths.nonEmpty, "The list of files is empty")
    if paths.forall(ParquetDataset.isObjectUri) then
      val store = storeFor(paths.head)
      try
        filesDataset(store, paths, options, ownsStore = true)
      catch
        case e: Throwable =>
          store.close()
          throw e
    else if paths.exists(ParquetDataset.isObjectUri) then
      throw IllegalArgumentException(
        s"A list of files must be all local or all in one bucket: ${paths.mkString(", ")}"
      )
    else
      ParquetDataset.requireFiles(options, paths.mkString(", "))
      val (root, listed) = ParquetDataset.listedLocalFiles(paths.map(absolutePath))
      ParquetDataset.build(root.path, listed, options, None, ownsStore = false, skipHidden = false)

  /** Listed objects of `store` as one dataset; the store is left open */
  def dataset(store: ObjectStore, paths: Seq[String]): ParquetDataset = dataset(
    store,
    paths,
    ParquetDatasetOptions.default
  )

  def dataset(
      store: ObjectStore,
      paths: Seq[String],
      options: ParquetDatasetOptions
  ): ParquetDataset =
    require(paths.nonEmpty, "The list of files is empty")
    filesDataset(store, paths, options, ownsStore = false)

  private[parquet] def filesDataset(
      store: ObjectStore,
      paths: Seq[String],
      options: ParquetDatasetOptions,
      ownsStore: Boolean
  ): ParquetDataset =
    ParquetDataset.requireFiles(options, paths.mkString(", "))
    val (root, listed) = ParquetDataset.listedObjects(paths.map(ObjectUri.parse))
    ParquetDataset.build(root.toString, listed, options, Some(store), ownsStore, skipHidden = false)

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

  private[parquet] def datasetAsync(
      store: ObjectStore,
      root: String,
      options: ParquetDatasetOptions,
      ownsStore: Boolean
  ): Rx[ParquetDataset] =
    if ParquetDataset.isIcebergMetadataFile(root, options) then
      Rx.single(
          ParquetDataset.icebergFromMetadataFile(
            ObjectUri.parse(root).toString,
            options,
            objects = true
          )
        )
        .flatMap(icebergAsync(_, store, options, ownsStore))
    else
      objectDatasetAsync(store, root, options, ownsStore)

  private def icebergAsync(
      step: IcebergTable.Step,
      store: ObjectStore,
      options: ParquetDatasetOptions,
      ownsStore: Boolean
  ): Rx[ParquetDataset] = ParquetDataset
    .runIcebergAsync(step, store, options.prefetch)
    .map(ParquetDataset.icebergDataset(_, options, Some(store), ownsStore))

  private def objectDatasetAsync(
      store: ObjectStore,
      root: String,
      options: ParquetDatasetOptions,
      ownsStore: Boolean
  ): Rx[ParquetDataset] = Rx
    .single(objectPattern(root))
    .flatMap { (uri, matches, source) =>
      val plainRoot = source.isEmpty
      if !plainRoot then
        ParquetDataset.requireFiles(options, root)
      val listing =
        tableListing(uri, options).filter(_ => plainRoot) match
          case Some(dir) =>
            ParquetDataset
              .listObjectsAsync(store, subPrefix(uri, dir))
              .map(ParquetDataset.under(dir, _))
          case None =>
            ParquetDataset.listObjectsAsync(store, uri)
      listing.flatMap { listed =>
        if plainRoot && ParquetDataset.isIceberg(listed, options) &&
          !ParquetDataset.isDelta(listed, options)
        then
          Rx.single(
              ParquetDataset.icebergFromListing(uri.toString, listed, options, objects = true)
            )
            .flatMap(icebergAsync(_, store, options, ownsStore))
        else if plainRoot && ParquetDataset.isDelta(listed, options) then
          val plan = ParquetDataset.deltaPlan(uri.toString, listed, options)
          ParquetDataset
            .fetchLogAsync(plan, store, options.prefetch)
            .map(fetched =>
              ParquetDataset.deltaDataset(
                uri.toString,
                plan,
                fetched,
                options,
                Some(store),
                ownsStore
              )
            )
        else
          ParquetDataset.requireFiles(options, root)
          ParquetDataset
            .readMetadataAsync(listed, options, store)
            .map(metadata =>
              ParquetDataset.build(
                uri.toString,
                listed.filter(l => matches(l.segments)),
                options,
                Some(store),
                ownsStore,
                source = source,
                metadata = metadata
              )
            )
      }
    }

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

  // Writing datasets

  /**
    * Writes case classes to a partitioned dataset under a local directory or an `s3://`, `gs://`,
    * or `az://` prefix, in the `key=value` folders [[dataset]] reads. The partition columns, set
    * with [[ParquetDatasetWriteOptions.withPartitionBy]], are fields of `A` and are left out of the
    * files. Returns the files written.
    *
    * {{{
    * Parquet.writeDataset(
    *   "s3://bucket/events/",
    *   events,
    *   ParquetDatasetWriteOptions.default.withPartitionBy("dt", "country")
    * )
    * }}}
    *
    * If writing fails, every file of the write is discarded and existing data is left as it was.
    */
  inline def writeDataset[A](
      root: String,
      rows: IterableOnce[A],
      options: ParquetDatasetWriteOptions
  )(using weaver: Weaver[A]): Seq[WrittenFile] = writeAllTo(
    newDatasetWriter[A](root, options),
    rows
  )

  inline def writeDataset[A](
      store: ObjectStore,
      root: String,
      rows: IterableOnce[A],
      options: ParquetDatasetWriteOptions
  )(using weaver: Weaver[A]): Seq[WrittenFile] = writeAllTo(
    newDatasetWriter[A](store, root, options),
    rows
  )

  private def writeAllTo[A](w: ParquetDatasetWriter[A], rows: IterableOnce[A]): Seq[WrittenFile] =
    try
      w.writeAll(rows)
      w.close()
      w.writtenFiles
    catch
      case e: Throwable =>
        w.abort()
        throw e

  /**
    * A streaming writer of case classes to a partitioned dataset; see [[writeDataset]]. With a URI
    * root, the writer creates the scheme's default store and closes it when it closes.
    */
  inline def newDatasetWriter[A](root: String, options: ParquetDatasetWriteOptions)(using
      weaver: Weaver[A]
  ): ParquetDatasetWriter[A] =
    // The schema first, so a case class that can't be written creates no store
    val schema = ParquetSchema.fromSurface(Surface.of[A], options.writeOptions)
    ParquetDatasetWriter.create(datasetTarget(root), schema, options, weaver)

  /** A dataset writer under a prefix of `store`, which the writer leaves open when it closes */
  inline def newDatasetWriter[A](
      store: ObjectStore,
      root: String,
      options: ParquetDatasetWriteOptions
  )(using weaver: Weaver[A]): ParquetDatasetWriter[A] =
    // The schema first, so a case class that can't be written creates no store
    val schema = ParquetSchema.fromSurface(Surface.of[A], options.writeOptions)
    ParquetDatasetWriter.create(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      schema,
      options,
      weaver
    )

  /**
    * Writes records (maps, JSON, or value sequences) to a partitioned dataset. The schema includes
    * the partition columns.
    */
  def newDatasetRecordWriter(
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): ParquetDatasetRecordWriter = ParquetDatasetRecordWriter(
    DatasetWriterCore.create(datasetTarget(root), schema, options)
  )

  def newDatasetRecordWriter(
      store: ObjectStore,
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): ParquetDatasetRecordWriter = ParquetDatasetRecordWriter(
    DatasetWriterCore.create(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      schema,
      options
    )
  )

  /**
    * Writes [[ColumnBatch]]es to a partitioned dataset. Each batch has a vector for every column of
    * the schema, including the partition columns; see [[ParquetDatasetBatchWriter]].
    */
  def newDatasetBatchWriter(
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): ParquetDatasetBatchWriter = ParquetDatasetBatchWriter(
    DatasetWriterCore.create(datasetTarget(root), schema, options)
  )

  def newDatasetBatchWriter(
      store: ObjectStore,
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): ParquetDatasetBatchWriter = ParquetDatasetBatchWriter(
    DatasetWriterCore.create(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      schema,
      options
    )
  )

  // ---- Async dataset writers ----

  /**
    * Writes case classes to a partitioned dataset with async I/O, as [[writeDataset]] does with
    * sync I/O. It runs in browsers: each file is built in memory and uploaded with one put, so
    * files are capped at 128 MiB unless `withMaxFileSize` sets another size. A local root uses the
    * sync writer. If writing fails, every file of the write is deleted.
    */
  inline def writeDatasetAsync[A](
      root: String,
      rows: IterableOnce[A],
      options: ParquetDatasetWriteOptions
  )(using weaver: Weaver[A]): Rx[Seq[WrittenFile]] = newDatasetWriterAsync[A](root, options)
    .flatMap(w => writeAllAsyncTo(w, rows))

  inline def writeDatasetAsync[A](
      store: ObjectStore,
      root: String,
      rows: IterableOnce[A],
      options: ParquetDatasetWriteOptions
  )(using weaver: Weaver[A]): Rx[Seq[WrittenFile]] = newDatasetWriterAsync[A](store, root, options)
    .flatMap(w => writeAllAsyncTo(w, rows))

  private def writeAllAsyncTo[A](
      w: ParquetDatasetWriterAsync[A],
      rows: IterableOnce[A]
  ): Rx[Seq[WrittenFile]] = w
    .writeAll(rows)
    .flatMap(_ => w.close())
    .recoverWith { case e: Throwable =>
      w.abort().transformRx(_ => Rx.exception[Seq[WrittenFile]](e))
    }

  /**
    * A dataset writer with async I/O; see [[ParquetDatasetWriterAsync]]. With a URI root, it
    * creates the scheme's default store and closes it when it closes. Creating it lists the root
    * with async calls.
    */
  inline def newDatasetWriterAsync[A](root: String, options: ParquetDatasetWriteOptions)(using
      weaver: Weaver[A]
  ): Rx[ParquetDatasetWriterAsync[A]] =
    val schema = ParquetSchema.fromSurface(Surface.of[A], options.writeOptions)
    asyncDriver(root, schema, options).map(d => ParquetDatasetWriterAsync.create(d, weaver))

  inline def newDatasetWriterAsync[A](
      store: ObjectStore,
      root: String,
      options: ParquetDatasetWriteOptions
  )(using weaver: Weaver[A]): Rx[ParquetDatasetWriterAsync[A]] =
    val schema = ParquetSchema.fromSurface(Surface.of[A], options.writeOptions)
    AsyncDatasetDriver
      .create(store, root, schema, options, ownsStore = false)
      .map(d => ParquetDatasetWriterAsync.create(d, weaver))

  def newDatasetRecordWriterAsync(
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): Rx[ParquetDatasetRecordWriterAsync] = asyncDriver(root, schema, options).map(
    ParquetDatasetRecordWriterAsync(_)
  )

  def newDatasetRecordWriterAsync(
      store: ObjectStore,
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): Rx[ParquetDatasetRecordWriterAsync] = AsyncDatasetDriver
    .create(store, root, schema, options, ownsStore = false)
    .map(ParquetDatasetRecordWriterAsync(_))

  def newDatasetBatchWriterAsync(
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): Rx[ParquetDatasetBatchWriterAsync] = asyncDriver(root, schema, options).map(
    ParquetDatasetBatchWriterAsync(_)
  )

  def newDatasetBatchWriterAsync(
      store: ObjectStore,
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): Rx[ParquetDatasetBatchWriterAsync] = AsyncDatasetDriver
    .create(store, root, schema, options, ownsStore = false)
    .map(ParquetDatasetBatchWriterAsync(_))

  // ---- Iceberg tables ----

  /**
    * Commits schema changes to the Apache Iceberg table at `root` (a Hadoop table, as Uni writes),
    * in order, as one new schema. No data file is rewritten.
    */
  def alterIcebergTable(root: String, changes: IcebergTableChange*): Unit =
    withTarget(datasetTarget(root))(alterTable(_, changes))

  /** Commits schema changes to the Iceberg table at `root` in the store */
  def alterIcebergTable(store: ObjectStore, root: String, changes: IcebergTableChange*): Unit =
    alterTable(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      changes
    )

  /**
    * Expires every snapshot of the Apache Iceberg table at `root` but the newest `retainLast`, the
    * current one, and those a branch or tag references. The commit is a new metadata version; then
    * the files only expired snapshots reference (manifest lists, manifests, data files) and the
    * metadata files older than the oldest kept snapshot are deleted, so time travel to an expired
    * snapshot stops working. Files outside the table folder are never deleted.
    *
    * @return
    *   the number of expired snapshots (0 when there was nothing to expire)
    */
  def expireIcebergSnapshots(root: String, retainLast: Int): Int =
    withTarget(datasetTarget(root))(expireSnapshots(_, retainLast))

  /** Expires snapshots of the Iceberg table at `root` in the store */
  def expireIcebergSnapshots(store: ObjectStore, root: String, retainLast: Int): Int =
    expireSnapshots(
      DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false),
      retainLast
    )

  private def expireSnapshots(target: DatasetTarget, retainLast: Int): Int =
    val layout = DatasetWriterCore.icebergLayout(target)
    DatasetWriterCore.runStepsSync(target, IcebergWrite.expire(layout, retainLast)) match
      case None =>
        0
      case Some(e) =>
        finishCommit(target, layout, e, "Expired snapshots of")
        e.snapshots

  /**
    * Moves the Apache Iceberg table at local folder `from` to `to`, which must not exist yet, and
    * points its metadata at the new location: the current snapshot's manifests are written again
    * with the new paths, so other engines read the moved table. Only the current snapshot is kept;
    * the files of older snapshots and older metadata versions are deleted. Object stores aren't
    * supported, since they can't move a folder.
    */
  def renameIcebergTable(from: String, to: String): Unit =
    val source      = IOPath.parse(from)
    val target      = IOPath.parse(to)
    val metadataDir = source.resolve(IcebergTable.MetadataDir)
    // Checked before moving: only a Hadoop table (metadata/vN.metadata.json) can be relocated
    val hadoop =
      FileSystem.isDirectory(metadataDir) &&
        FileSystem.list(metadataDir).exists(p => p.fileName.matches("""v\d+\.metadata\.json"""))
    if !hadoop then
      throw ParquetException(
        s"${from} is not an Iceberg table that Uni writes (metadata/vN.metadata.json)"
      )
    if FileSystem.exists(target) then
      throw ParquetException(s"Cannot rename ${from} to ${to}: ${to} already exists")
    FileSystem.move(source, target)
    withTarget(datasetTarget(to)) { t =>
      val layout = DatasetWriterCore.icebergLayout(t)
      val e      =
        try
          DatasetWriterCore.runStepsSync(t, IcebergWrite.relocate(layout))
        catch
          case NonFatal(err) =>
            throw ParquetException(
              s"Moved ${from} to ${to}, but updating its metadata for the new location failed; Uni still reads it there: ${err
                  .getMessage}",
              err
            )
      finishCommit(t, layout, e, "Moved the table to")
    }

  /** Writes the hint for a committed version, then deletes the files no snapshot references */
  private def finishCommit(
      target: DatasetTarget,
      layout: IcebergWrite.Layout,
      e: IcebergWrite.Expired,
      what: String
  ): Unit =
    val (path, bytes) = IcebergWrite.hint(layout, e.version)
    DatasetWriterCore.runSync(
      target,
      DatasetWriterCore
        .RootAction
        .Put(
          path,
          bytes,
          s"${what} ${target.rootName} (${e.metadataPath}), but writing ${path} failed"
        )
    )
    // Every file is tried: one that fails to delete stays as an unreferenced file
    val failed = e
      .unreferenced
      .flatMap { p =>
        try
          DatasetWriterCore.runSync(
            target,
            DatasetWriterCore.RootAction.Delete(p, s"Failed to delete ${p}")
          )
          None
        catch
          case NonFatal(err) =>
            Some(p -> err)
      }
    failed
      .headOption
      .foreach { (p, err) =>
        throw ParquetException(
          s"${what} ${target.rootName} (${e.metadataPath}), but ${failed
              .size} unreferenced files could not be deleted, such as ${p}: ${err.getMessage}",
          err
        )
      }

  end finishCommit

  /**
    * Deletes the Apache Iceberg table at `root`: its metadata first, so a drop that fails partway
    * leaves no readable table, then its data files and the folder. Fails when `root` is not an
    * Iceberg table.
    */
  def dropIcebergTable(root: String): Unit = withTarget(datasetTarget(root))(dropTable)

  /** Deletes the Iceberg table at `root` in the store */
  def dropIcebergTable(store: ObjectStore, root: String): Unit = dropTable(
    DatasetTarget.Objects(store, ParquetDataset.objectRoot(root), ownsStore = false)
  )

  private def withTarget[A](target: DatasetTarget)(body: DatasetTarget => A): A =
    try body(target)
    finally target.closeStore()

  private def alterTable(target: DatasetTarget, changes: Seq[IcebergTableChange]): Unit =
    require(changes.nonEmpty, "No schema change is given")
    val layout        = DatasetWriterCore.icebergLayout(target)
    val altered       = DatasetWriterCore.runStepsSync(target, IcebergWrite.alter(layout, changes))
    val (path, bytes) = IcebergWrite.hint(layout, altered)
    DatasetWriterCore.runSync(
      target,
      DatasetWriterCore
        .RootAction
        .Put(
          path,
          bytes,
          s"Committed the schema change to ${target.rootName} (${altered
              .metadataPath}), but writing ${path} failed"
        )
    )

  private def dropTable(target: DatasetTarget): Unit =
    val layout   = DatasetWriterCore.icebergLayout(target)
    val metadata = DatasetWriterCore.listDirSync(target, layout.metadataDir)
    if !metadata.exists(IcebergTable.isMetadataFile) then
      throw ParquetException(s"${target.rootName} is not an Iceberg table")
    // The hint and metadata files go first, so readers stop seeing the table
    val first = metadata
      .filter(n => n == IcebergTable.VersionHint || IcebergTable.isMetadataFile(n))
      .sortBy(n =>
        if n == IcebergTable.VersionHint then
          0
        else
          1
      )
    first.foreach { n =>
      DatasetWriterCore.runSync(
        target,
        DatasetWriterCore
          .RootAction
          .Delete(layout.metadata(n), s"Failed to delete ${layout.metadata(n)}")
      )
    }
    target match
      case DatasetTarget.Local(root) =>
        FileSystem.deleteRecursively(root)
      case DatasetTarget.Objects(store, root, _) =>
        store.list(root, recursive = true).foreach(o => store.delete(o.uri))

  private[parquet] def asyncDriver(
      root: String,
      schema: ParquetSchema,
      options: ParquetDatasetWriteOptions
  ): Rx[AsyncDatasetDriver] =
    if ParquetDataset.isObjectUri(root) then
      Rx.single(storeFor(root))
        .flatMap(store => AsyncDatasetDriver.create(store, root, schema, options, ownsStore = true))
    else
      Rx.single(
        AsyncDatasetDriver.local(DatasetWriterCore.create(datasetTarget(root), schema, options))
      )

  private[parquet] def datasetTarget(root: String): DatasetTarget =
    if ParquetDataset.isObjectUri(root) then
      val uri = ParquetDataset.objectRoot(root)
      DatasetTarget.Objects(storeFor(root), uri, ownsStore = true)
    else
      DatasetTarget.Local(absolutePath(root))

  private def absolutePath(root: String): IOPath =
    val parsed = IOPath.parse(root)
    if parsed.isAbsolute then
      parsed
    else
      FileSystem.currentDirectory.resolve(parsed).normalize

end Parquet

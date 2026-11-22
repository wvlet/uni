package wvlet.uni.parquet.npm

import wvlet.uni.parquet.*
import wvlet.uni.rx.Rx
import wvlet.uni.storage.ObjectStore

import java.time.LocalDate
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/**
  * The dataset half of `@wvlet/uni/parquet`: `openDataset`, `writeDataset`, and `newDatasetWriter`.
  * Their JS shape is documented in parquet.d.ts, which must change together with this file.
  */
object JsDataset:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private def fail(msg: String): Nothing = throw IllegalArgumentException(msg)

  import ParquetNpm.isSet
  import ParquetNpm.optionsOf

  private def isObjectUri(s: String): Boolean = ParquetDataset.isObjectUri(s)

  private def isUrl(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

  private def positiveInt(o: js.Dynamic, key: String): Option[Int] = positiveLong(o, key).map { v =>
    if v > Int.MaxValue then
      fail(s"${key} is too large: ${v}")
    v.toInt
  }

  private def positiveLong(o: js.Dynamic, key: String): Option[Long] =
    val v = o.selectDynamic(key)
    if !isSet(v) then
      None
    else
      val d =
        if js.typeOf(v) == "bigint" then
          v.toString.toDouble
        else if js.typeOf(v) == "number" then
          v.asInstanceOf[Double]
        else
          fail(s"${key} must be a positive integer: ${js.JSON.stringify(v)}")
      if !d.isWhole || d < 1 then
        fail(s"${key} must be a positive integer: ${v.toString}")
      Some(d.toLong)

  private def boolOf(o: js.Dynamic, key: String): Option[Boolean] =
    val v = o.selectDynamic(key)
    if !isSet(v) then
      None
    else if js.typeOf(v) != "boolean" then
      fail(s"${key} must be a boolean")
    else
      Some(v.asInstanceOf[Boolean])

  private val partitionTypeNames: Map[String, ParquetType.Primitive] = Map(
    "string" -> ParquetType.STRING,
    "int32"  -> ParquetType.INT32,
    "int64"  -> ParquetType.INT64,
    "date"   -> ParquetType.DATE
  )

  private[npm] def datasetOptions(o: js.Dynamic): ParquetDatasetOptions =
    var opts = ParquetDatasetOptions.default
    if isSet(o.partitionTypes) then
      if js.typeOf(o.partitionTypes) != "object" then
        fail("partitionTypes must be an object such as { hour: \"int32\" }")
      o.partitionTypes
        .asInstanceOf[js.Dictionary[js.Any]]
        .foreach { (key, t) =>
          val tpe = partitionTypeNames.getOrElse(
            t.toString,
            fail(s"Unknown partition type \"${t}\" for ${key}. Use string, int32, int64, or date")
          )
          opts = opts.withPartitionType(key, tpe)
        }
    if isSet(o.fileExtension) then
      opts = opts.withFileExtension(o.fileExtension.toString)
    boolOf(o, "readCommonMetadata").foreach(b => opts = opts.withReadCommonMetadata(b))
    positiveInt(o, "prefetch").foreach(n => opts = opts.withPrefetch(n))
    opts

  /** Builds the store for a URI from the `storage` option; tests pass an in-memory store */
  private[npm] type Stores = (String, js.Dynamic) => ObjectStore

  /** Runs `body` with a store it owns, closing the store when `body` fails */
  private def withStore[A](uri: String, storage: js.Dynamic, stores: Stores)(
      body: ObjectStore => Rx[A]
  ): Rx[A] = Rx
    .single(stores(uri, storage))
    .flatMap { store =>
      try
        body(store).tapOnFailure(_ => store.close())
      catch
        case e: Throwable =>
          store.close()
          throw e
    }

  private[npm] def datasetOf(source: js.Any, o: js.Dynamic, stores: Stores): Rx[ParquetDataset] =
    val opts    = datasetOptions(o)
    val storage = o.storage
    (source: Any) match
      case s: String if isUrl(s) =>
        fail(
          s"Datasets are read from local paths or s3://, gs://, and az:// URIs, not ${s}. Use openParquet for a single URL"
        )
      case s: String if isObjectUri(s) =>
        withStore(s, storage, stores)(store =>
          Parquet.datasetAsync(store, s, opts, ownsStore = true)
        )
      case s: String =>
        Parquet.datasetAsync(s, opts)
      case a: js.Array[?] =>
        val paths = a
          .toSeq
          .map {
            case p: String =>
              p
            case other =>
              fail(s"A list of files holds strings, not ${js.typeOf(other.asInstanceOf[js.Any])}")
          }
        if paths.isEmpty then
          fail("The list of files is empty")
        if paths.forall(isObjectUri) then
          withStore(paths.head, storage, stores)(store =>
            Rx.single(Parquet.filesDataset(store, paths, opts, ownsStore = true))
          )
        else
          // Local files, or a mix, which Parquet.dataset rejects with a clear error
          Rx.single(Parquet.dataset(paths, opts))
      case other =>
        fail(
          s"openDataset takes a root, a glob, or an array of files, not ${js.typeOf(
              other.asInstanceOf[js.Any]
            )}"
        )
    end match

  end datasetOf

  @JSExportTopLevel("openDataset", moduleID = "parquet")
  def openDataset(
      source: js.Any,
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[JsParquetDataset] = openDatasetWith(source, options, JsStorage.storeFor)

  private[npm] def openDatasetWith(
      source: js.Any,
      options: js.UndefOr[js.Dynamic],
      stores: Stores
  ): js.Promise[JsParquetDataset] = ParquetNpm.toPromise(
    Future
      .unit
      .flatMap { _ =>
        ParquetNpm.first(
          datasetOf(source, optionsOf(options), stores).flatMap { ds =>
            // The schema is read here, asynchronously, so no later call reads footers in sync
            ds.schemaAsync.map(schema => JsParquetDataset(ds, schema)).tapOnFailure(_ => ds.close())
          }
        )
      }
  )

  // ---- Writing ----

  private val modes: Map[String, ParquetWriteMode] = Map(
    "error-if-exists"      -> ParquetWriteMode.ErrorIfExists,
    "overwrite"            -> ParquetWriteMode.Overwrite,
    "append"               -> ParquetWriteMode.Append,
    "overwrite-partitions" -> ParquetWriteMode.OverwritePartitions
  )

  private[npm] def writeOptions(o: js.Dynamic): ParquetDatasetWriteOptions =
    var opts = ParquetDatasetWriteOptions.default
    if isSet(o.partitionBy) then
      if !js.Array.isArray(o.partitionBy) then
        fail("partitionBy must be an array of column names")
      opts = opts.withPartitionBy(o.partitionBy.asInstanceOf[js.Array[String]].toSeq*)
    if isSet(o.mode) then
      val m = o.mode.toString
      opts = opts.withMode(
        modes.getOrElse(
          m,
          fail(
            s"Unknown mode \"${m}\". Use error-if-exists, overwrite, append, or overwrite-partitions"
          )
        )
      )
    positiveLong(o, "maxRowsPerFile").foreach(n => opts = opts.withMaxRowsPerFile(n))
    positiveLong(o, "maxFileSize").foreach(n => opts = opts.withMaxFileSize(n))
    positiveInt(o, "maxOpenFiles").foreach(n => opts = opts.withMaxOpenFiles(n))
    positiveLong(o, "maxBufferedBytes").foreach(n => opts = opts.withMaxBufferedBytes(n))
    boolOf(o, "writeSuccessMarker").foreach(b => opts = opts.withWriteSuccessMarker(b))
    boolOf(o, "writeCommonMetadata").foreach(b => opts = opts.withWriteCommonMetadata(b))
    opts.withWriteOptions(JsWriter.fileOptions(o))

  private[npm] def writerOf(
      root: String,
      schema: js.Array[js.Dynamic],
      o: js.Dynamic,
      stores: Stores = JsStorage.storeFor
  ): Rx[JsDatasetWriter] =
    if isUrl(root) then
      fail(s"Datasets are written to local paths or s3://, gs://, and az:// URIs, not ${root}")
    val cols   = JsWriter.columns(schema)
    val ps     = ParquetSchema.fromColumns("schema", cols.map(_.column))
    val opts   = writeOptions(o)
    val writer =
      if isObjectUri(root) then
        withStore(root, o.storage, stores)(store =>
          AsyncDatasetDriver
            .create(store, root, ps, opts, ownsStore = true)
            .map(ParquetDatasetRecordWriterAsync(_))
        )
      else
        Parquet.newDatasetRecordWriterAsync(root, ps, opts)
    writer.map(JsDatasetWriter(_, cols))

  @JSExportTopLevel("newDatasetWriter", moduleID = "parquet")
  def newDatasetWriter(
      root: String,
      schema: js.Array[js.Dynamic],
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[JsDatasetWriter] = ParquetNpm.toPromise(
    Future.unit.flatMap(_ => ParquetNpm.first(writerOf(root, schema, optionsOf(options))))
  )

  @JSExportTopLevel("writeDataset", moduleID = "parquet")
  def writeDataset(
      root: String,
      rows: js.Iterable[js.Any],
      schema: js.Array[js.Dynamic],
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): js.Promise[js.Array[js.Object]] = ParquetNpm.toPromise(
    Future
      .unit
      .flatMap { _ =>
        ParquetNpm.first(
          writerOf(root, schema, optionsOf(options)).flatMap { w =>
            w.writeAllRx(rows)
              .flatMap(_ => w.closeRx())
              .recoverWith { case e: Throwable =>
                // Deletes the files already uploaded, then reports the first error
                w.abortRx().transformRx(_ => Rx.exception(e))
              }
          }
        )
      }
  )

  // ---- JS values ----

  /** A partition value as JS sees it: the read mapping for STRING, INT32, INT64, and DATE */
  private[npm] def partitionValue(v: Any): js.Any =
    v match
      case null =>
        null
      case s: String =>
        s
      case i: Int =>
        i
      case l: Long =>
        JsValues.bigint(l)
      case d: LocalDate =>
        new js.Date(d.toEpochDay.toDouble * 86400000.0)
      case other =>
        other.toString

  private[npm] def partitions(values: scala.collection.Map[String, Any]): js.Dictionary[js.Any] =
    val d = js.Dictionary.empty[js.Any]
    values.foreach((k, v) => d(k) = partitionValue(v))
    d

  private[npm] def writtenFile(f: WrittenFile): js.Object = js
    .Dynamic
    .literal(
      path = f.path,
      partitions = partitions(f.partitions),
      numRows = f.numRows.toDouble,
      size = f.size.toDouble
    )

end JsDataset

/** A dataset opened from JS; see `ParquetDataset` in parquet.d.ts */
class JsParquetDataset private[npm] (ds: ParquetDataset, datasetSchema: ParquetSchema)
    extends js.Object:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val partitionKeys = ds.partitionColumns.map(_.name).toSet

  val root: String = ds.root

  val partitionColumns: js.Array[String] = ds.partitionColumns.map(_.name).toJSArray

  val schema: js.Array[js.Object] =
    datasetSchema
      .fields
      .map { f =>
        val o = ParquetNpm.describe(f)
        if partitionKeys.contains(f.name) then
          o.asInstanceOf[js.Dynamic].partition = true
        o
      }
      .toJSArray

  val numFiles: Int = ds.files.size

  private def parse(options: js.UndefOr[js.Dynamic]) = ParquetNpm.readOptions(
    options,
    datasetSchema
  )

  /** The files a read with these options opens, after partition pruning. No file is read. */
  def files(options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Array[js.Object]] =
    ParquetNpm.toPromise(
      Future {
        val (opts, _) = parse(options)
        ds.selectFiles(opts)
          .map { f =>
            js.Dynamic
              .literal(
                path = f.path,
                size =
                  if f.size < 0 then
                    null
                  else
                    f.size.toDouble
                ,
                partitions = JsDataset.partitions(f.partitions)
              ): js.Object
          }
          .toJSArray
      }
    )

  /** Pulls one row group at a time, so a slow consumer never buffers the whole dataset */
  private def batchSteps(
      opts: ParquetReadOptions
  ): (() => Future[Option[ColumnBatch]], () => Unit) =
    var reader: DatasetBatchReader          = null
    var pending: Iterator[ColumnBatch]      = Iterator.empty
    var finished                            = false
    def step(): Future[Option[ColumnBatch]] =
      if pending.hasNext then
        Future.successful(Some(pending.next()))
      else if finished then
        Future.successful(None)
      else
        val ready =
          if reader == null then
            ParquetNpm.first(ds.batchReaderAsync(opts)).map(r => reader = r)
          else
            Future.unit
        ready.flatMap { _ =>
          // A Future per row group, so long runs of filtered-out row groups never deepen the stack
          ParquetNpm
            .first(reader.nextRowGroup())
            .flatMap {
              case Some(bs) =>
                pending = bs.iterator
                step()
              case None =>
                finished = true
                Future.successful(None)
            }
        }
    def stop(): Unit =
      finished = true
      pending = Iterator.empty
      if reader != null then
        reader.close()
    (step, stop)

  end batchSteps

  def readRows(options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Array[js.Any]] =
    ParquetNpm.toPromise(
      Future
        .unit
        .flatMap { _ =>
          val (opts, m)                        = parse(options)
          val (step, stop)                     = batchSteps(opts)
          val out                              = js.Array[js.Any]()
          def loop(): Future[js.Array[js.Any]] = step().flatMap {
            case Some(b) =>
              ParquetNpm.rows(b, m).foreach(r => out.push(r))
              loop()
            case None =>
              Future.successful(out)
          }
          loop().transform { r =>
            stop()
            r
          }
        }
    )

  def batches(options: js.UndefOr[js.Dynamic] = js.undefined): js.Object =
    val (opts, m) = ParquetNpm.guard(parse(options))
    ParquetNpm.asyncIterable { () =>
      val (step, stop) = batchSteps(opts)
      (
        () =>
          step()
            .map(_.map(b => ParquetNpm.batch(b, m): js.Any))
            .transform { r =>
              if r.isFailure || r.toOption.exists(_.isEmpty) then
                stop()
              r
            },
        stop
      )
    }

  def close(): Unit = ds.close()

end JsParquetDataset

/** A dataset writer opened from JS; see `DatasetWriter` in parquet.d.ts */
class JsDatasetWriter private[npm] (w: ParquetDatasetRecordWriterAsync, cols: Seq[JsWriter.Col])
    extends js.Object:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  val root: String = w.root

  def numRows: Double = w.numRows.toDouble

  def write(row: js.Any): Unit = ParquetNpm.guard(w.write(JsWriter.row(row, cols)))

  private[npm] final def writeAllRx(rows: js.Iterable[js.Any]): Rx[Unit] =
    val it = rows.jsIterator()
    w.writeAll(
      Iterator.continually(it.next()).takeWhile(!_.done).map(e => JsWriter.row(e.value, cols))
    )

  private[npm] final def closeRx(): Rx[js.Array[js.Object]] = w
    .close()
    .map(_.map(JsDataset.writtenFile).toJSArray)

  private[npm] final def abortRx(): Rx[Unit] = w.abort()

  def writeAll(rows: js.Iterable[js.Any]): js.Promise[Unit] = ParquetNpm.toPromise(
    Future.unit.flatMap(_ => ParquetNpm.first(writeAllRx(rows)))
  )

  def flush(): js.Promise[Unit] = ParquetNpm.toPromise(
    Future.unit.flatMap(_ => ParquetNpm.first(w.flush()))
  )

  def close(): js.Promise[js.Array[js.Object]] = ParquetNpm.toPromise(
    Future.unit.flatMap(_ => ParquetNpm.first(closeRx()))
  )

  def abort(): js.Promise[Unit] = ParquetNpm.toPromise(
    Future.unit.flatMap(_ => ParquetNpm.first(w.abort()))
  )

end JsDatasetWriter

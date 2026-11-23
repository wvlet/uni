package wvlet.uni.cloud.parquet.npm

import org.scalajs.dom
import wvlet.uni.cloud.parquet.*
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.Repetition
import wvlet.uni.cloud.parquet.format.TimeUnit
import wvlet.uni.rx.OnCompletion
import wvlet.uni.rx.OnFailure
import wvlet.uni.rx.OnError
import wvlet.uni.rx.OnNext
import wvlet.uni.rx.Rx
import wvlet.uni.rx.RxRunner

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array

/**
  * The npm entry point `@wvlet/uni/cloud/parquet`. Its JS shape is documented in parquet.d.ts, which must
  * change together with this file.
  */
object ParquetNpm:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** Uni errors reach JS as plain Errors carrying the message */
  private[npm] def jsError(e: Throwable): js.Error =
    e match
      case js.JavaScriptException(err: js.Error) =>
        err
      case _ =>
        val err = js.Error(Option(e.getMessage).getOrElse(e.toString))
        err.asInstanceOf[js.Dynamic].name = "ParquetError"
        err

  private[npm] def guard[A](body: => A): A =
    try
      body
    catch
      case e: js.JavaScriptException =>
        throw e
      case e: Throwable =>
        throw js.JavaScriptException(jsError(e))

  /** The first value of an Rx, as a Future */
  private[npm] def first[A](rx: Rx[A]): Future[A] =
    val p = Promise[A]()
    RxRunner.runOnce(rx) {
      case OnNext(v) =>
        p.trySuccess(v.asInstanceOf[A])
      case OnFailure(e) =>
        p.tryFailure(e)
      case OnCompletion =>
        p.tryFailure(IllegalStateException("The read finished without a value"))
    }
    p.future

  private[npm] def toPromise[A](f: Future[A]): js.Promise[A] = js.Promise[A] { (resolve, reject) =>
    f.onComplete {
      case scala.util.Success(v) =>
        resolve(v)
      case scala.util.Failure(e) =>
        reject(jsError(e))
    }
  }

  private def isUrl(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

  private def hasBlob: Boolean = js.typeOf(js.Dynamic.global.Blob) != "undefined"

  private def open(source: js.Any): Future[ParquetFile] =
    (source: Any) match
      case s: String if isUrl(s) =>
        first(Parquet.openUrlAsync(s))
      case s: String =>
        Future(Parquet.open(s))
      case u: Uint8Array =>
        Future(Parquet.open(JsValues.fromBytes(u)))
      case b: ArrayBuffer =>
        Future(Parquet.open(js.typedarray.int8Array2ByteArray(Int8Array(b))))
      case b if hasBlob && b.isInstanceOf[dom.Blob] =>
        val blob = b.asInstanceOf[dom.Blob]
        val name =
          if js.isUndefined(blob.asInstanceOf[js.Dynamic].name) then
            "blob"
          else
            blob.asInstanceOf[js.Dynamic].name.toString
        first(Parquet.openAsync(BlobSource(blob, name)))
      case other =>
        Future.failed(
          IllegalArgumentException(
            s"openParquet takes a Uint8Array, ArrayBuffer, Blob, file path, or http(s) URL, not ${js
                .typeOf(other)}"
          )
        )

  @JSExportTopLevel("openParquet", moduleID = "parquet")
  def openParquet(source: js.Any): js.Promise[JsParquetFile] = toPromise(
    // Futures run their body later, so a sync failure (e.g. a missing file) rejects the promise
    Future.unit.flatMap(_ => open(source)).map(JsParquetFile(_))
  )

  @JSExportTopLevel("writeParquet", moduleID = "parquet")
  def writeParquet(
      rows: js.Array[js.Any],
      schema: js.Array[js.Dynamic],
      options: js.UndefOr[js.Dynamic] = js.undefined
  ): Uint8Array = guard(JsWriter.write(rows, schema, options.getOrElse(null)))

  // ---- Reading, shared by files and datasets ----

  private[npm] def isSet(v: js.Any): Boolean = !js.isUndefined(v) && v != null

  /** An options argument, or an empty object when it is missing or null */
  private[npm] def optionsOf(options: js.UndefOr[js.Dynamic]): js.Dynamic =
    if options.isEmpty || options.get == null then
      js.Dynamic.literal()
    else
      options.get

  /** Parses `ReadOptions`; the schema resolves what a `Date` in the filter means */
  private[npm] def readOptions(
      options: js.UndefOr[js.Dynamic],
      schema: ParquetSchema
  ): (ParquetReadOptions, JsValues.Mapping) =
    val o    = optionsOf(options)
    var opts = ParquetReadOptions.default
    if isSet(o.columns) then
      opts = opts.withColumns(o.columns.asInstanceOf[js.Array[String]].toSeq*)
    if isSet(o.filter) then
      opts = opts.withFilter(JsFilter.parse(o.filter.asInstanceOf[js.Any], schema))
    val ts = !js.isUndefined(o.timestamps) && o.timestamps.asInstanceOf[String] == "bigint"
    (opts, JsValues.Mapping(timestampsAsBigInt = ts))

  private[npm] def rows(b: ColumnBatch, m: JsValues.Mapping): Iterator[js.Dictionary[js.Any]] =
    Iterator
      .range(0, b.numRows)
      .map { i =>
        val d = js.Dictionary.empty[js.Any]
        b.columns.foreach(c => d(c.name) = JsValues.value(c, i, m))
        d
      }

  private[npm] def batch(b: ColumnBatch, m: JsValues.Mapping): js.Object =
    // Converted when first asked for, so reading a few columns of a wide file stays cheap
    val converted = scala.collection.mutable.Map.empty[String, js.Object]
    js.Dynamic
      .literal(
        numRows = b.numRows,
        columnNames = b.columnNames.toJSArray,
        column = { (name: String) =>
          b.columns.find(_.name == name) match
            case Some(c) =>
              converted.getOrElseUpdate(name, JsValues.column(c, m))
            case None =>
              throw js.JavaScriptException(js.Error(s"No column named ${name}"))
        }: js.Function1[String, js.Object]
      )

  /**
    * An AsyncIterable over `step`, which yields the next value or None at the end. `onReturn` runs
    * when a `for await` loop exits early.
    */
  private[npm] def asyncIterable(
      start: () => (() => Future[Option[js.Any]], () => Unit)
  ): js.Object =
    val iterable                         = js.Object()
    val factory: js.Function0[js.Object] =
      () =>
        val (step, onReturn) = start()
        val done             = js.Dynamic.literal(done = true, value = js.undefined)
        js.Dynamic
          .literal(
            next =
              (
                  () =>
                    toPromise(
                      step().map {
                        case Some(v) =>
                          js.Dynamic.literal(done = false, value = v)
                        case None =>
                          done
                      }
                    )
              ): js.Function0[js.Promise[js.Object]],
            `return` =
              (
                  () =>
                    onReturn()
                    js.Promise.resolve[js.Object](done)
              ): js.Function0[js.Promise[js.Object]]
          )
    js.Dynamic.global.Reflect.set(iterable, js.Dynamic.global.Symbol.asyncIterator, factory)
    iterable

  end asyncIterable

  // ---- Schema description ----

  private def unitName(u: TimeUnit): String = u.toString.toLowerCase

  def typeName(f: ParquetField): String =
    f match
      case p: ParquetField.Primitive =>
        p.logicalType match
          case Some(LogicalType.String) =>
            "string"
          case Some(LogicalType.Enum) =>
            "enum"
          case Some(LogicalType.Json) =>
            "json"
          case Some(LogicalType.Bson) =>
            "bson"
          case Some(LogicalType.Uuid) =>
            "uuid"
          case Some(LogicalType.Float16) =>
            "float16"
          case Some(LogicalType.Date) =>
            "date"
          case Some(LogicalType.Time(_, u)) =>
            s"time-${unitName(u)}"
          case Some(LogicalType.Timestamp(_, u)) =>
            s"timestamp-${unitName(u)}"
          case Some(LogicalType.Decimal(scale, precision)) =>
            s"decimal(${precision},${scale})"
          case Some(LogicalType.Integer(bits, signed)) =>
            s"${
                if signed then
                  "int"
                else
                  "uint"
              }${bits}"
          case _ =>
            p.physicalType match
              case PhysicalType.BOOLEAN =>
                "boolean"
              case PhysicalType.INT32 =>
                "int32"
              case PhysicalType.INT64 =>
                "int64"
              case PhysicalType.INT96 =>
                "int96"
              case PhysicalType.FLOAT =>
                "float"
              case PhysicalType.DOUBLE =>
                "double"
              case PhysicalType.FIXED_LEN_BYTE_ARRAY =>
                s"fixed(${p.typeLength})"
              case _ =>
                "binary"
      case g: ParquetField.Group =>
        g.logicalType match
          case Some(LogicalType.List) =>
            "list"
          case Some(LogicalType.Map) =>
            "map"
          case _ =>
            "struct"

  def describe(f: ParquetField): js.Object =
    val o = js
      .Dynamic
      .literal(
        name = f.name,
        `type` = typeName(f),
        optional = f.repetition != Repetition.REQUIRED,
        repeated = f.repetition == Repetition.REPEATED
      )
    f match
      case g: ParquetField.Group =>
        o.fields = g.children.map(describe).toJSArray
      case _ =>
    o

end ParquetNpm

/** A Parquet file opened from JS; see `ParquetFile` in parquet.d.ts */
class JsParquetFile private[npm] (file: ParquetFile) extends js.Object:
  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  val numRows: Double             = file.numRows.toDouble
  val numRowGroups: Int           = file.numRowGroups
  val schema: js.Array[js.Object] = file.schema.fields.map(ParquetNpm.describe).toJSArray
  val keyValueMetadata: js.Dictionary[String] = file.keyValueMetadata.toJSDictionary
  val createdBy: String | Null                = file.createdBy.orNull

  private def parse(options: js.UndefOr[js.Dynamic]): (ParquetReadOptions, JsValues.Mapping) =
    ParquetNpm.readOptions(options, file.schema)

  /**
    * The batches of one row group. A row group too large for one batch (over 2 GiB in a column)
    * comes as several, as `ParquetFile.readBatches` splits it.
    */
  private def rowGroupBatches(i: Int, options: ParquetReadOptions): Rx[Iterator[ColumnBatch]] = file
    .rowGroupBatchesAsync(i, options)
    .map(_.filter(_.numRows > 0))

  def readRows(options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Array[js.Any]] =
    ParquetNpm.toPromise(
      Future
        .unit
        .flatMap { _ =>
          val (opts, m) = parse(options)
          ParquetNpm.first(
            ParquetFile
              .sequence(file.selectRowGroups(opts).map(rowGroupBatches(_, opts)))
              .map { groups =>
                val out = js.Array[js.Any]()
                groups.foreach(_.foreach(b => ParquetNpm.rows(b, m).foreach(r => out.push(r))))
                out
              }
          )
        }
    )

  /** An AsyncIterable of column batches: one per row group, or several for a large row group */
  def batches(options: js.UndefOr[js.Dynamic] = js.undefined): js.Object =
    val (opts, m) = ParquetNpm.guard(parse(options))
    val groups    = ParquetNpm.guard(file.selectRowGroups(opts))
    ParquetNpm.asyncIterable { () =>
      var nextGroup                      = 0
      var current: Iterator[ColumnBatch] = Iterator.empty
      def step(): Future[Option[js.Any]] =
        if current.hasNext then
          Future(Some(ParquetNpm.batch(current.next(), m)))
        else if nextGroup < groups.size then
          val i = groups(nextGroup)
          nextGroup += 1
          ParquetNpm
            .first(rowGroupBatches(i, opts))
            .flatMap { it =>
              current = it
              step()
            }
        else
          Future.successful(None)
      def stop(): Unit =
        nextGroup = groups.size
        current = Iterator.empty
      (step, stop)
    }

  def close(): Unit = file.close()

end JsParquetFile

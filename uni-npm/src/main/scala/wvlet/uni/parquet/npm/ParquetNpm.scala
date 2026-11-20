package wvlet.uni.parquet.npm

import org.scalajs.dom
import wvlet.uni.parquet.*
import wvlet.uni.parquet.format.LogicalType
import wvlet.uni.parquet.format.PhysicalType
import wvlet.uni.parquet.format.Repetition
import wvlet.uni.parquet.format.TimeUnit
import wvlet.uni.rx.OnCompletion
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
  * The npm entry point `@wvlet/uni/parquet`. Its JS shape is documented in parquet.d.ts, which must
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
      case OnError(e) =>
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
    val o =
      if options.isEmpty || options.get == null then
        js.Dynamic.literal()
      else
        options.get
    val cols =
      if js.isUndefined(o.columns) || o.columns == null then
        None
      else
        Some(o.columns.asInstanceOf[js.Array[String]].toSeq)
    val ts = !js.isUndefined(o.timestamps) && o.timestamps.asInstanceOf[String] == "bigint"
    (ParquetReadOptions(columns = cols), JsValues.Mapping(timestampsAsBigInt = ts))

  private def rowGroup(i: Int, options: ParquetReadOptions): Future[ColumnBatch] = ParquetNpm.first(
    file.readRowGroupAsync(i, options)
  )

  private def sequential[A](groups: Seq[Int], options: ParquetReadOptions)(
      f: ColumnBatch => A
  ): Future[Seq[A]] =
    groups.foldLeft(Future.successful(Vector.empty[A])) { (acc, i) =>
      acc.flatMap(done => rowGroup(i, options).map(b => done :+ f(b)))
    }

  private def rows(b: ColumnBatch, m: JsValues.Mapping): Seq[js.Dictionary[js.Any]] =
    (0 until b.numRows).map { i =>
      val d = js.Dictionary.empty[js.Any]
      b.columns.foreach(c => d(c.name) = JsValues.value(c, i, m))
      d
    }

  private def batch(b: ColumnBatch, m: JsValues.Mapping): js.Object =
    val cols = b.columns.map(c => c.name -> JsValues.column(c, m)).toMap
    js.Dynamic
      .literal(
        numRows = b.numRows,
        columnNames = b.columnNames.toJSArray,
        column = { (name: String) =>
          cols.getOrElse(name, throw js.JavaScriptException(js.Error(s"No column named ${name}")))
        }: js.Function1[String, js.Object]
      )

  def readRows(options: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Array[js.Any]] =
    ParquetNpm.toPromise(
      Future
        .unit
        .flatMap { _ =>
          val (opts, m) = parse(options)
          sequential(file.selectRowGroups(opts), opts)(rows(_, m))
        }
        .map(_.flatten.toJSArray.asInstanceOf[js.Array[js.Any]])
    )

  /** An AsyncIterable of column batches, one per row group */
  def batches(options: js.UndefOr[js.Dynamic] = js.undefined): js.Object =
    val (opts, m)                        = ParquetNpm.guard(parse(options))
    val groups                           = ParquetNpm.guard(file.selectRowGroups(opts))
    val iterable                         = js.Object()
    val factory: js.Function0[js.Object] =
      () =>
        var next = 0
        js.Dynamic
          .literal(
            next = { () =>
              if next >= groups.size then
                js.Promise.resolve[js.Object](js.Dynamic.literal(done = true, value = js.undefined))
              else
                val i = groups(next)
                next += 1
                ParquetNpm.toPromise(
                  rowGroup(i, opts).map(b => js.Dynamic.literal(done = false, value = batch(b, m)))
                )
            }: js.Function0[js.Promise[js.Object]]
          )
    js.Dynamic.global.Reflect.set(iterable, js.Dynamic.global.Symbol.asyncIterator, factory)
    iterable

  def close(): Unit = file.close()

end JsParquetFile

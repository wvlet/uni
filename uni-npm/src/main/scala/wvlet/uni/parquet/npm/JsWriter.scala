package wvlet.uni.parquet.npm

import wvlet.uni.parquet.*
import wvlet.uni.parquet.format.CompressionCodec

import java.time.Instant
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** Parses the JS writer schema and converts JS rows into values uni-parquet writes */
private[npm] object JsWriter:

  /** A column type of the JS schema, with how to convert a JS value for it */
  sealed trait Spec:
    def tpe: ParquetType

  case class Prim(name: String, tpe: ParquetType) extends Spec
  case class Decimal(tpe: ParquetType)            extends Spec
  case class ListOf(element: Spec)                extends Spec:
    def tpe: ParquetType = ParquetType.list(element.tpe, optionalElements = true)

  case class MapOf(key: Spec, value: Spec) extends Spec:
    def tpe: ParquetType = ParquetType.map(key.tpe, value.tpe, optionalValues = true)

  case class Struct(columns: Seq[Col]) extends Spec:
    def tpe: ParquetType = ParquetType.Struct(columns.map(_.column))

  case class Col(name: String, spec: Spec, optional: Boolean):
    def column: ParquetColumn =
      if optional then
        ParquetColumn.optional(name, spec.tpe)
      else
        ParquetColumn.required(name, spec.tpe)

  private val primitives: Map[String, ParquetType] = Map(
    "boolean"          -> ParquetType.BOOLEAN,
    "int32"            -> ParquetType.INT32,
    "int64"            -> ParquetType.INT64,
    "float"            -> ParquetType.FLOAT,
    "double"           -> ParquetType.DOUBLE,
    "string"           -> ParquetType.STRING,
    "binary"           -> ParquetType.BINARY,
    "json"             -> ParquetType.JSON,
    "uuid"             -> ParquetType.UUID,
    "date"             -> ParquetType.DATE,
    "timestamp-millis" -> ParquetType.TIMESTAMP_MILLIS,
    "timestamp-micros" -> ParquetType.TIMESTAMP_MICROS,
    "timestamp-nanos"  -> ParquetType.TIMESTAMP_NANOS
  )

  private def fail(msg: String): Nothing = throw IllegalArgumentException(msg)

  private def has(o: js.Dynamic, key: String): Boolean = !js.isUndefined(o.selectDynamic(key))

  def spec(t: js.Any, at: String): Spec =
    if js.typeOf(t) == "string" then
      val n = t.asInstanceOf[String]
      primitives.get(n) match
        case Some(p) =>
          Prim(n, p)
        case None =>
          fail(
            s"Unknown type \"${n}\" for ${at}. Use one of ${primitives
                .keys
                .toSeq
                .sorted
                .mkString(", ")}, or {decimal}, {list}, {map}, {struct}"
          )
    else if t != null && js.typeOf(t) == "object" then
      val o = t.asInstanceOf[js.Dynamic]
      if has(o, "decimal") then
        val d                     = o.decimal
        def int(key: String): Int =
          val v = d.selectDynamic(key)
          if js.typeOf(v) != "number" || !v.asInstanceOf[Double].isWhole then
            fail(s"${at} needs an integer decimal ${key}")
          v.asInstanceOf[Double].toInt
        Decimal(ParquetType.decimal(int("precision"), int("scale")))
      else if has(o, "list") then
        ListOf(spec(o.list.asInstanceOf[js.Any], s"${at}[]"))
      else if has(o, "map") then
        MapOf(
          spec(o.map.key.asInstanceOf[js.Any], s"${at}.key"),
          spec(o.map.value.asInstanceOf[js.Any], s"${at}.value")
        )
      else if has(o, "struct") then
        Struct(columns(o.struct.asInstanceOf[js.Array[js.Dynamic]], s"${at}."))
      else
        fail(s"Unknown type for ${at}: ${js.JSON.stringify(t)}")
    else
      fail(s"Missing type for ${at}")

  def columns(cols: js.Array[js.Dynamic], prefix: String = ""): Seq[Col] = cols
    .toSeq
    .map { c =>
      if js.typeOf(c.name) != "string" then
        fail(s"Every column needs a name: ${js.JSON.stringify(c)}")
      val name = c.name.asInstanceOf[String]
      // Columns are optional unless they say otherwise, as JS rows often miss fields
      val optional = js.isUndefined(c.optional) || c.optional.asInstanceOf[Boolean]
      Col(name, spec(c.selectDynamic("type").asInstanceOf[js.Any], s"${prefix}${name}"), optional)
    }

  def compression(name: js.UndefOr[String]): CompressionCodec =
    name.getOrElse("snappy") match
      case "snappy" =>
        CompressionCodec.SNAPPY
      case "none" =>
        CompressionCodec.UNCOMPRESSED
      case "lz4" =>
        CompressionCodec.LZ4_RAW
      case "zstd" =>
        CompressionCodec.ZSTD
      case "gzip" =>
        CompressionCodec.GZIP
      case other =>
        fail(s"Unknown compression \"${other}\". Use snappy, none, lz4, zstd, or gzip")

  private def isBigInt(v: js.Any): Boolean = js.typeOf(v) == "bigint"
  private def bigIntOf(v: js.Any): BigInt  = BigInt(v.toString)

  private def isDate(v: js.Any): Boolean = v.isInstanceOf[js.Date]

  /** The time of a Date; an Invalid Date would otherwise be written as the epoch */
  private def epochMillis(v: js.Any): Double =
    val t = v.asInstanceOf[js.Date].getTime()
    if t.isNaN then
      fail("Invalid Date")
    t

  private def instantOf(raw: BigInt, name: String): Instant =
    val (perSecond, nanosPer) =
      name match
        case "timestamp-millis" =>
          (1000L, 1000000L)
        case "timestamp-micros" =>
          (1000000L, 1000L)
        case _ =>
          (1000000000L, 1L)
    val (sec, rem) = raw /% BigInt(perSecond)
    if rem < 0 then
      Instant.ofEpochSecond((sec - 1).toLong, ((rem + perSecond) * nanosPer).toLong)
    else
      Instant.ofEpochSecond(sec.toLong, (rem * nanosPer).toLong)

  /** A JS value converted for a column of the given type; mismatches are left to the writer */
  def convert(v: js.Any, s: Spec): Any =
    if v == null || js.isUndefined(v) then
      null
    else
      s match
        case Prim(name, _) =>
          prim(v, name)
        case Decimal(_) =>
          // Decimals go through strings so no precision is lost on the way
          v.toString
        case ListOf(e) =>
          (v: Any) match
            case a: js.Array[?] =>
              a.toSeq.map(x => convert(x.asInstanceOf[js.Any], e))
            case _ =>
              fail(s"Expected an array, got ${js.typeOf(v)}")
        case MapOf(k, x) =>
          if v.isInstanceOf[js.Map[?, ?]] then
            v.asInstanceOf[js.Map[js.Any, js.Any]]
              .toSeq
              .map((a, b) => convert(a, k) -> convert(b, x))
              .toMap
          else if js.typeOf(v) == "object" && !v.isInstanceOf[js.Array[?]] then
            v.asInstanceOf[js.Dictionary[js.Any]]
              .toSeq
              .map((a, b) => convert(a, k) -> convert(b, x))
              .toMap
          else
            fail(s"Expected an object or a Map, got ${js.typeOf(v)}")
        case Struct(cols) =>
          row(v, cols)

  def row(v: js.Any, cols: Seq[Col]): Map[String, Any] =
    if js.typeOf(v) != "object" || v.isInstanceOf[js.Array[?]] then
      fail(s"Expected an object for a row, got ${js.typeOf(v)}")
    val d = v.asInstanceOf[js.Dictionary[js.Any]]
    cols.map(c => c.name -> convert(d.getOrElse(c.name, null), c.spec)).toMap

  private def prim(v: js.Any, name: String): Any =
    name match
      case "int32" | "int64" =>
        if isBigInt(v) then
          bigIntOf(v)
        else
          v
      case "float" | "double" =>
        if isBigInt(v) then
          bigIntOf(v).toDouble
        else
          v
      case "json" =>
        if js.typeOf(v) == "string" then
          v
        else
          js.JSON.stringify(v)
      case "binary" =>
        (v: Any) match
          case u: Uint8Array =>
            JsValues.fromBytes(u)
          case _ =>
            v
      case "date" =>
        if isDate(v) then
          Math.floor(epochMillis(v) / 86400000.0).toLong
        else
          v
      case n if n.startsWith("timestamp-") =>
        if isDate(v) then
          Instant.ofEpochMilli(epochMillis(v).toLong)
        else if isBigInt(v) then
          instantOf(bigIntOf(v), n)
        else
          v
      case _ =>
        v

  /** Writes rows to a Parquet file in memory */
  def write(rows: js.Array[js.Any], schema: js.Array[js.Dynamic], options: js.Dynamic): Uint8Array =
    val cols       = columns(schema)
    val ps         = ParquetSchema.fromColumns("schema", cols.map(_.column))
    val hasOptions = !js.isUndefined(options) && options != null
    val codec      =
      if hasOptions then
        options.compression.asInstanceOf[js.UndefOr[String]]
      else
        js.undefined
    var opts = ParquetWriteOptions.default.withCompression(compression(codec))
    if hasOptions && !js.isUndefined(options.compressionLevel) then
      val level = options.compressionLevel
      if js.typeOf(level) != "number" || !level.asInstanceOf[Double].isWhole then
        fail(s"compressionLevel must be an integer: ${js.JSON.stringify(level)}")
      opts = opts.withCompressionLevel(level.asInstanceOf[Double].toInt)
    val out = java.io.ByteArrayOutputStream()
    val w   = Parquet.newRecordWriter(out, ps, opts)
    try rows.foreach(r => w.write(row(r, cols)))
    finally w.close()
    JsValues.bytes(out.toByteArray)

end JsWriter

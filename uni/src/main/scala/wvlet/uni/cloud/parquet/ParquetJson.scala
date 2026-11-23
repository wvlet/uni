package wvlet.uni.cloud.parquet

import wvlet.uni.json.JSON.*

/**
  * Converts row values, as [[ColumnVector.getValue]] returns them, to JSON.
  *
  *   - Integers become JSON integers; unsigned 64-bit values above Long.MaxValue become strings
  *   - FLOAT and DOUBLE become JSON numbers; NaN and infinities become strings ("NaN", "Infinity")
  *   - DECIMAL becomes a string, so no digits are lost
  *   - TIMESTAMP, DATE, TIME, and UUID become ISO-8601 or canonical strings
  *   - Binary values become base64 strings
  *   - Lists become arrays, and maps and groups become objects; map keys become strings
  */
object ParquetJson:

  /** A row map (column name to value) as a JSON object, in the row's column order */
  def fromRow(row: Map[String, Any]): JSONObject = JSONObject(
    row.toSeq.map((k, v) => k -> toJSON(v))
  )

  def toJSON(v: Any): JSONValue =
    v match
      case null =>
        JSONNull()
      case b: Boolean =>
        JSONBoolean(b)
      case i: Int =>
        JSONLong(i.toLong)
      case l: Long =>
        JSONLong(l)
      case f: Float =>
        number(f.toString.toDouble)
      case d: Double =>
        number(d)
      case s: String =>
        JSONString(s)
      case b: BigInt =>
        if b.isValidLong then
          JSONLong(b.toLong)
        else
          JSONString(b.toString)
      case d: BigDecimal =>
        JSONString(d.bigDecimal.toPlainString)
      case b: Array[Byte] =>
        JSONString(java.util.Base64.getEncoder.encodeToString(b))
      case m: Map[?, ?] =>
        JSONObject(
          m.toSeq
            .map { (k, v) =>
              key(k) -> toJSON(v)
            }
        )
      case s: Seq[?] =>
        JSONArray(s.map(toJSON).toIndexedSeq)
      case other =>
        // Instant, LocalDate, LocalTime, UUID
        JSONString(other.toString)

  /** A map key as a string, rendered the way the same value would be as a JSON value */
  private def key(k: Any): String =
    toJSON(k) match
      case JSONString(s) =>
        s
      case other =>
        other.toJSON

  private def number(d: Double): JSONValue =
    if d.isNaN || d.isInfinite then
      JSONString(d.toString)
    else
      JSONDouble(d)

end ParquetJson

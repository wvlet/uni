package wvlet.uni.parquet

import wvlet.uni.io.CRC32
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.io.codec.Inflate
import wvlet.uni.io.codec.Snappy
import wvlet.uni.io.codec.Zstd

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * A minimal reader of Avro object container files, for Iceberg manifests and manifest lists. It
  * decodes every record with the writer's schema from the file header into a generic tree: a record
  * is a `Map[String, Any]` of its fields, an array an `IndexedSeq[Any]`, a map a
  * `Map[String, Any]`, an enum its symbol, bytes and fixed an `Array[Byte]`, and primitives their
  * Scala value (`null` for null). Logical types are not applied. Codecs: null, deflate, snappy, and
  * zstandard.
  */
private[parquet] object Avro:

  /** A schema node */
  sealed trait Schema
  object Schema:
    case object Null                             extends Schema
    case object Boolean                          extends Schema
    case object Int                              extends Schema
    case object Long                             extends Schema
    case object Float                            extends Schema
    case object Double                           extends Schema
    case object Bytes                            extends Schema
    case object Str                              extends Schema
    case class Fixed(size: scala.Int)            extends Schema
    case class Enum(symbols: IndexedSeq[String]) extends Schema
    case class ArrayOf(items: Schema)            extends Schema
    case class MapOf(values: Schema)             extends Schema
    case class Union(types: IndexedSeq[Schema])  extends Schema
    // Fields are filled after the record registers its name, so it can refer to itself
    final class Record(
        val name: String,
        var fields: IndexedSeq[(String, Schema)] = IndexedSeq.empty
    ) extends Schema

  private val Magic = Array[Byte]('O', 'b', 'j', 1)

  /** The file metadata and the decoded records of an Avro object container file */
  case class Container(metadata: Map[String, Array[Byte]], records: IndexedSeq[Any]):
    def meta(key: String): Option[String] = metadata.get(key).map(String(_, StandardCharsets.UTF_8))

  def read(bytes: Array[Byte], source: String): Container =
    def fail(msg: String): Nothing =
      throw ParquetException(s"${source} is not a valid Avro file: ${msg}")
    if bytes.length < 4 || !(0 until 4).forall(i => bytes(i) == Magic(i)) then
      fail("no Avro magic")
    val header = Decoder(bytes, 4, bytes.length, source)
    val meta   = mutable.LinkedHashMap.empty[String, Array[Byte]]
    header.blocks { () =>
      val k = header.string()
      meta(k) = header.bytes()
    }
    val sync       = header.fixed(16)
    val metadata   = meta.toMap
    val schemaJson = metadata
      .get("avro.schema")
      .map(String(_, StandardCharsets.UTF_8))
      .getOrElse(fail("no avro.schema in the header"))
    val schema = parseSchema(JSON.parse(schemaJson), "", mutable.HashMap.empty, source)
    val codec  = metadata.get("avro.codec").map(String(_, StandardCharsets.UTF_8)).getOrElse("null")
    val records = IndexedSeq.newBuilder[Any]
    var pos     = header.pos
    while pos < bytes.length do
      val d     = Decoder(bytes, pos, bytes.length, source)
      val count = d.long()
      val size  = d.long()
      if count < 0 || size < 0 || size > bytes.length - d.pos then
        fail(s"a block at byte ${pos} is truncated")
      val start = d.pos
      val data  = decompress(codec, bytes, start, size.toInt, source)
      val body  = Decoder(data, 0, data.length, source)
      var i     = 0L
      while i < count do
        records += body.value(schema)
        i += 1
      val end = start + size.toInt
      if end + 16 > bytes.length || !(0 until 16).forall(i => bytes(end + i) == sync(i)) then
        fail(s"the block at byte ${pos} doesn't end with the sync marker")
      pos = end + 16
    end while
    Container(metadata, records.result())

  end read

  private def decompress(
      codec: String,
      bytes: Array[Byte],
      offset: Int,
      length: Int,
      source: String
  ): Array[Byte] = ParquetException.fromCodec(decompressBlock(codec, bytes, offset, length, source))

  private def decompressBlock(
      codec: String,
      bytes: Array[Byte],
      offset: Int,
      length: Int,
      source: String
  ): Array[Byte] =
    codec match
      case "null" =>
        java.util.Arrays.copyOfRange(bytes, offset, offset + length)
      case "deflate" =>
        Inflate.inflate(bytes, offset, length, length * 4)
      case "snappy" =>
        // Each block ends with the big-endian CRC32 of its uncompressed data
        if length < 4 then
          throw ParquetException(s"${source} has a snappy block without its checksum")
        val data = Snappy.decompress(bytes, offset, length - 4, snappyLength(bytes, offset))
        val p    = offset + length - 4
        val crc  =
          ((bytes(p) & 0xff) << 24) |
            ((bytes(p + 1) & 0xff) << 16) |
            ((bytes(p + 2) & 0xff) << 8) |
            (bytes(p + 3) & 0xff)
        if CRC32.compute(data) != crc then
          throw ParquetException(s"${source} has a snappy block with a wrong checksum")
        data
      case "zstandard" =>
        Zstd.decompressUnknownSize(bytes, offset, length)
      case other =>
        throw ParquetException(s"${source} uses the Avro codec ${other}, which Uni can't read")

  /** The uncompressed length in a raw Snappy block's varint preamble */
  private def snappyLength(bytes: Array[Byte], offset: Int): Int =
    var v     = 0L
    var shift = 0
    var i     = offset
    while i < bytes.length && shift <= 28 && {
        val b = bytes(i) & 0xff
        v |= (b & 0x7fL) << shift
        shift += 7
        i += 1
        (b & 0x80) != 0
      }
    do
      (
    )
    v.toInt

  // ---- Schema ----

  private def parseSchema(
      json: JSONValue,
      namespace: String,
      named: mutable.HashMap[String, Schema],
      source: String
  ): Schema =
    def fail(msg: String): Nothing =
      throw ParquetException(s"${source} has an Avro schema that Uni can't read: ${msg}")
    def fullName(name: String, ns: String): String =
      if name.contains('.') || ns.isEmpty then
        name
      else
        s"${ns}.${name}"
    def register(o: JSONObject, s: Schema): String =
      val name = o
        .get("name")
        .collect { case JSONString(n) =>
          n
        }
        .getOrElse(fail("a named type has no name"))
      val ns = o
        .get("namespace")
        .collect { case JSONString(n) =>
          n
        }
        .getOrElse(namespace)
      val full = fullName(name, ns)
      named(full) = s
      named.getOrElseUpdate(name, s)
      full.lastIndexOf('.') match
        case -1 =>
          ""
        case i =>
          full.substring(0, i)
    json match
      case JSONString(name) =>
        name match
          case "null" =>
            Schema.Null
          case "boolean" =>
            Schema.Boolean
          case "int" =>
            Schema.Int
          case "long" =>
            Schema.Long
          case "float" =>
            Schema.Float
          case "double" =>
            Schema.Double
          case "bytes" =>
            Schema.Bytes
          case "string" =>
            Schema.Str
          case ref =>
            named
              .get(fullName(ref, namespace))
              .orElse(named.get(ref))
              .getOrElse(fail(s"unknown type ${ref}"))
      case JSONArray(types) =>
        Schema.Union(types.map(parseSchema(_, namespace, named, source)))
      case o: JSONObject =>
        o.get("type") match
          case Some(JSONString("record" | "error")) =>
            val r = Schema.Record(
              o.get("name")
                .collect { case JSONString(n) =>
                  n
                }
                .getOrElse("")
            )
            val ns = register(o, r)
            r.fields =
              o.get("fields") match
                case Some(JSONArray(fs)) =>
                  fs.map {
                    case f: JSONObject =>
                      val n = f
                        .get("name")
                        .collect { case JSONString(s) =>
                          s
                        }
                        .getOrElse(fail("a field has no name"))
                      n ->
                        parseSchema(
                          f.get("type").getOrElse(fail(s"field ${n} has no type")),
                          ns,
                          named,
                          source
                        )
                    case other =>
                      fail(s"a field is not an object: ${other.toJSON}")
                  }
                case _ =>
                  fail(s"record ${r.name} has no fields")
            r
          case Some(JSONString("enum")) =>
            val symbols =
              o.get("symbols") match
                case Some(JSONArray(vs)) =>
                  vs.collect { case JSONString(s) =>
                    s
                  }
                case _ =>
                  fail("an enum has no symbols")
            val e = Schema.Enum(symbols)
            register(o, e)
            e
          case Some(JSONString("fixed")) =>
            val size =
              o.get("size") match
                case Some(JSONLong(n)) =>
                  n.toInt
                case Some(JSONDouble(n)) =>
                  n.toInt
                case _ =>
                  fail("a fixed type has no size")
            val f = Schema.Fixed(size)
            register(o, f)
            f
          case Some(JSONString("array")) =>
            Schema.ArrayOf(
              parseSchema(
                o.get("items").getOrElse(fail("an array has no items")),
                namespace,
                named,
                source
              )
            )
          case Some(JSONString("map")) =>
            Schema.MapOf(
              parseSchema(
                o.get("values").getOrElse(fail("a map has no values")),
                namespace,
                named,
                source
              )
            )
          case Some(t) =>
            // A primitive with attributes, such as {"type": "long", "logicalType": ...}
            parseSchema(t, namespace, named, source)
          case None =>
            fail(s"a type has no type: ${o.toJSON}")
      case other =>
        fail(s"unexpected ${other.toJSON}")
    end match

  end parseSchema

  // ---- Binary decoding ----

  private final class Decoder(buf: Array[Byte], var pos: Int, end: Int, source: String):
    private def fail(msg: String): Nothing =
      throw ParquetException(s"${source} is not a valid Avro file: ${msg}")

    private def need(n: Long): Unit =
      if n < 0 || n > end - pos then
        fail("data ends early")

    def long(): Long =
      var v     = 0L
      var shift = 0
      var b     = 0
      while
        if shift > 63 then
          fail("a varint is too long")
        need(1)
        b = buf(pos) & 0xff
        pos += 1
        v |= (b & 0x7fL) << shift
        shift += 7
        (b & 0x80) != 0
      do
        (
      )
      (v >>> 1) ^ -(v & 1)

    def int(): Int = long().toInt

    def fixed(n: Int): Array[Byte] =
      need(n)
      val a = java.util.Arrays.copyOfRange(buf, pos, pos + n)
      pos += n
      a

    def bytes(): Array[Byte] =
      val n = long()
      need(n)
      fixed(n.toInt)

    def string(): String =
      val n = long()
      need(n)
      val s = String(buf, pos, n.toInt, StandardCharsets.UTF_8)
      pos += n.toInt
      s

    private def littleEndian(n: Int): Long =
      need(n)
      var v = 0L
      var i = 0
      while i < n do
        v |= (buf(pos + i) & 0xffL) << (8 * i)
        i += 1
      pos += n
      v

    /** Reads array or map blocks, calling `item` once per item */
    def blocks(item: () => Unit): Unit =
      var count = long()
      while count != 0 do
        if count < 0 then
          count = -count
          long() // The block's size in bytes
        var i = 0L
        while i < count do
          item()
          i += 1
        count = long()

    def value(s: Schema): Any =
      s match
        case Schema.Null =>
          null
        case Schema.Boolean =>
          need(1)
          val b = buf(pos) != 0
          pos += 1
          b
        case Schema.Int =>
          int()
        case Schema.Long =>
          long()
        case Schema.Float =>
          java.lang.Float.intBitsToFloat(littleEndian(4).toInt)
        case Schema.Double =>
          java.lang.Double.longBitsToDouble(littleEndian(8))
        case Schema.Bytes =>
          bytes()
        case Schema.Str =>
          string()
        case Schema.Fixed(n) =>
          fixed(n)
        case Schema.Enum(symbols) =>
          val i = int()
          if i < 0 || i >= symbols.size then
            fail(s"enum index ${i} is out of range")
          symbols(i)
        case Schema.ArrayOf(items) =>
          val b = IndexedSeq.newBuilder[Any]
          blocks(() => b += value(items))
          b.result()
        case Schema.MapOf(values) =>
          val m = Map.newBuilder[String, Any]
          blocks(() => m += string() -> value(values))
          m.result()
        case Schema.Union(types) =>
          val i = long()
          if i < 0 || i >= types.size then
            fail(s"union index ${i} is out of range")
          value(types(i.toInt))
        case r: Schema.Record =>
          val m = Map.newBuilder[String, Any]
          r.fields.foreach((n, t) => m += n -> value(t))
          m.result()

  end Decoder

  // ---- Writing ----

  /**
    * Writes an Avro object container file with the `null` codec, which every Avro reader supports.
    * Records use the same generic tree [[read]] returns: a record is a `Map[String, Any]` (a
    * missing field is null), an array any `Seq`, a map a `Map[String, Any]`, bytes and fixed an
    * `Array[Byte]`. A union takes the first branch that fits the value. `schemaJson` is stored as
    * written, so attributes such as Iceberg's `field-id` reach the readers.
    */
  def write(schemaJson: String, metadata: Seq[(String, String)], records: Seq[Any]): Array[Byte] =
    val schema = parseSchema(JSON.parse(schemaJson), "", mutable.HashMap.empty, "the Avro schema")
    val out    = ByteBuf(1024)
    out.write(Magic)
    val meta = Seq("avro.schema" -> schemaJson, "avro.codec" -> "null") ++ metadata
    val head = Encoder(out)
    head.long(meta.size.toLong)
    meta.foreach { (k, v) =>
      head.string(k)
      head.bytes(v.getBytes(StandardCharsets.UTF_8))
    }
    head.long(0)
    val sync = new Array[Byte](16)
    scala.util.Random.nextBytes(sync)
    out.write(sync)
    if records.nonEmpty then
      val body = ByteBuf(4096)
      val e    = Encoder(body)
      records.foreach(e.value(schema, _, "record"))
      head.long(records.size.toLong)
      head.long(body.size.toLong)
      out.write(body)
      out.write(sync)
    out.toByteArray

  end write

  private final class Encoder(out: ByteBuf):
    def long(v: Long): Unit = out.writeUnsignedVarLong((v << 1) ^ (v >> 63))

    def bytes(b: Array[Byte]): Unit =
      long(b.length.toLong)
      out.write(b)

    def string(s: String): Unit = bytes(s.getBytes(StandardCharsets.UTF_8))

    private def fail(path: String, s: Schema, v: Any): Nothing =
      throw IllegalArgumentException(
        s"Avro ${path}: ${
            if v == null then
              "null"
            else
              v.getClass.getSimpleName
          } doesn't fit ${s}"
      )

    private def fits(s: Schema, v: Any): Boolean =
      (s, v) match
        case (Schema.Null, null) =>
          true
        case (Schema.Boolean, _: Boolean) =>
          true
        case (Schema.Int, _: Int) =>
          true
        case (Schema.Long, _: Long | _: Int) =>
          true
        case (Schema.Float, _: Float) =>
          true
        case (Schema.Double, _: Double | _: Float) =>
          true
        case (Schema.Bytes, _: Array[Byte]) =>
          true
        case (Schema.Fixed(n), b: Array[Byte]) =>
          b.length == n
        case (Schema.Str, _: String) =>
          true
        case (Schema.Enum(symbols), x: String) =>
          symbols.contains(x)
        case (_: Schema.ArrayOf, _: Seq[?]) =>
          true
        case (_: Schema.MapOf, _: Map[?, ?]) =>
          true
        case (_: Schema.Record, _: Map[?, ?]) =>
          true
        case _ =>
          false

    def value(s: Schema, v: Any, path: String): Unit =
      s match
        case Schema.Union(types) =>
          val i = types.indexWhere(fits(_, v))
          if i < 0 then
            fail(path, s, v)
          long(i.toLong)
          value(types(i), v, path)
        case _ if !fits(s, v) =>
          fail(path, s, v)
        case Schema.Null    =>
        case Schema.Boolean =>
          out.writeByte(
            if v.asInstanceOf[Boolean] then
              1
            else
              0
          )
        case Schema.Int =>
          long(v.asInstanceOf[Int].toLong)
        case Schema.Long =>
          v match
            case i: Int =>
              long(i.toLong)
            case l =>
              long(l.asInstanceOf[Long])
        case Schema.Float =>
          out.writeIntLE(java.lang.Float.floatToIntBits(v.asInstanceOf[Float]))
        case Schema.Double =>
          val d =
            v match
              case f: Float =>
                f.toDouble
              case d =>
                d.asInstanceOf[Double]
          out.writeLongLE(java.lang.Double.doubleToLongBits(d))
        case Schema.Bytes =>
          bytes(v.asInstanceOf[Array[Byte]])
        case Schema.Fixed(_) =>
          out.write(v.asInstanceOf[Array[Byte]])
        case Schema.Str =>
          string(v.asInstanceOf[String])
        case Schema.Enum(symbols) =>
          long(symbols.indexOf(v).toLong)
        case Schema.ArrayOf(items) =>
          val xs = v.asInstanceOf[Seq[Any]]
          if xs.nonEmpty then
            long(xs.size.toLong)
            xs.foreach(value(items, _, s"${path}[]"))
          long(0)
        case Schema.MapOf(values) =>
          val m = v.asInstanceOf[Map[String, Any]]
          if m.nonEmpty then
            long(m.size.toLong)
            m.foreach { (k, x) =>
              string(k)
              value(values, x, s"${path}.${k}")
            }
          long(0)
        case r: Schema.Record =>
          val m = v.asInstanceOf[Map[String, Any]]
          r.fields.foreach((n, t) => value(t, m.getOrElse(n, null), s"${path}.${n}"))
      end match

    end value

  end Encoder

end Avro

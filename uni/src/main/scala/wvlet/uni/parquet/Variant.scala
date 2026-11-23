package wvlet.uni.parquet

import wvlet.uni.parquet.ColumnVector.*
import wvlet.uni.parquet.io.ByteReader

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import scala.collection.immutable.VectorMap

/**
  * Decodes VARIANT values, as the Parquet Variant encoding and shredding specs define them, into
  * Scala values: null, Boolean, Int (for 8-, 16-, and 32-bit integers), Long, Float, Double,
  * BigDecimal, String, Array[Byte], LocalDate, LocalTime, Instant (for timestamps with and without
  * a zone), UUID, `Map[String, Any]` for objects (fields in name order), and `Seq[Any]` for arrays.
  *
  * A VARIANT group has a `metadata` binary (the field-name dictionary), a `value` binary, and, when
  * shredded, a `typed_value` column. A non-null `typed_value` holds the value in a Parquet type: a
  * primitive, a LIST of `{value, typed_value}` elements, or a group of `{value, typed_value}`
  * fields. A shredded object's `value` holds the fields that aren't shredded.
  */
private[parquet] object Variant:

  // Marks an object field that a row doesn't have
  private object Missing

  /** Row i of a non-null VARIANT group */
  def decode(s: StructColumn, i: Int): Any =
    val meta =
      s.child("metadata") match
        case Some(b: BinaryColumn) if !b.isNull(i) =>
          Metadata(b.data, b.offsets(i), b.length(i))
        case _ =>
          throw ParquetException.corrupt(s"VARIANT column ${s.field.pathString} has no metadata")
    try
      shredded(meta, s, i) match
        case Missing =>
          null
        case v =>
          v
    catch
      case e: IndexOutOfBoundsException =>
        throw ParquetException.corrupt(
          s"VARIANT value of ${s.field.pathString} overruns its bytes: ${e.getMessage}"
        )

  /** The value of a `{value, typed_value}` group at row i, or Missing when both are null */
  private def shredded(meta: Metadata, g: StructColumn, i: Int): Any =
    val value =
      g.child("value") match
        case Some(b: BinaryColumn) if !b.isNull(i) =>
          b
        case _ =>
          null
    g.child("typed_value") match
      case Some(t) if !t.isNull(i) =>
        t match
          case o: StructColumn =>
            val fields = Seq.newBuilder[(String, Any)]
            o.children
              .foreach {
                case f: StructColumn =>
                  shredded(meta, f, i) match
                    case Missing =>
                    case v       =>
                      fields += f.name -> v
                case other =>
                  throw ParquetException.corrupt(
                    s"shredded VARIANT field ${other.field.pathString} is not a group"
                  )
              }
            if value != null then
              // A partially shredded object: the other fields are in `value`
              binary(meta, value.data, value.offsets(i)) match
                case m: Map[?, ?] =>
                  m.foreach((k, v) => fields += k.toString -> v)
                case _ =>
                  throw ParquetException.corrupt(
                    s"shredded VARIANT object ${g
                        .field
                        .pathString} has a value that isn't an object"
                  )
            VectorMap.from(fields.result().sortBy(_._1))
          case l: ListColumn =>
            (l.offsets(i) until l.offsets(i + 1)).map { j =>
              l.elements match
                case e: StructColumn =>
                  shredded(meta, e, j) match
                    case Missing =>
                      null
                    case v =>
                      v
                case other =>
                  throw ParquetException.corrupt(
                    s"shredded VARIANT element ${other.field.pathString} is not a group"
                  )
            }
          case p =>
            p.getValue(i)
      case _ =>
        if value == null then
          Missing
        else
          binary(meta, value.data, value.offsets(i))
    end match

  end shredded

  /** The field-name dictionary of a variant */
  private final class Metadata(b: Array[Byte], off: Int, len: Int):
    private val header = b(off) & 0xff
    if (header & 0x0f) != 1 then
      throw ParquetException.unsupported(s"VARIANT metadata version ${header & 0x0f}")

    private val offsetSize = ((header >>> 6) & 3) + 1
    private val size       = uint(b, off + 1, offsetSize)
    private val offsets    = off + 1 + offsetSize
    private val strings    = offsets + (size + 1) * offsetSize
    private val names      = new Array[String](size)

    def name(id: Int): String =
      if id < 0 || id >= size then
        throw ParquetException.corrupt(
          s"VARIANT field id ${id} is outside the dictionary (${size})"
        )
      var s = names(id)
      if s == null then
        val from = uint(b, offsets + id * offsetSize, offsetSize)
        val to   = uint(b, offsets + (id + 1) * offsetSize, offsetSize)
        s = String(b, strings + from, to - from, UTF_8)
        names(id) = s
      s

  /** An unsigned little-endian integer of 1 to 4 bytes */
  private def uint(b: Array[Byte], off: Int, size: Int): Int =
    var v = 0
    var k = 0
    while k < size do
      v |= (b(off + k) & 0xff) << (8 * k)
      k += 1
    v

  /** The binary-encoded value at `off` */
  private def binary(meta: Metadata, b: Array[Byte], off: Int): Any =
    val h     = b(off) & 0xff
    val basic = h & 3
    val vh    = h >>> 2
    basic match
      case 0 =>
        primitive(b, off + 1, vh)
      case 1 =>
        // A short string: the length is in the header
        String(b, off + 1, vh, UTF_8)
      case 2 =>
        val offsetSize = (vh & 3) + 1
        val idSize     = ((vh >>> 2) & 3) + 1
        val large      = ((vh >>> 4) & 1) == 1
        val countSize  =
          if large then
            4
          else
            1
        val n       = uint(b, off + 1, countSize)
        val ids     = off + 1 + countSize
        val offsets = ids + n * idSize
        val values  = offsets + (n + 1) * offsetSize
        val fields  = VectorMap.newBuilder[String, Any]
        var k       = 0
        while k < n do
          val name = meta.name(uint(b, ids + k * idSize, idSize))
          fields += name -> binary(meta, b, values + uint(b, offsets + k * offsetSize, offsetSize))
          k += 1
        fields.result()
      case _ =>
        val offsetSize = (vh & 3) + 1
        val large      = ((vh >>> 2) & 1) == 1
        val countSize  =
          if large then
            4
          else
            1
        val n       = uint(b, off + 1, countSize)
        val offsets = off + 1 + countSize
        val values  = offsets + (n + 1) * offsetSize
        IndexedSeq.tabulate(n) { k =>
          binary(meta, b, values + uint(b, offsets + k * offsetSize, offsetSize))
        }
    end match

  end binary

  private def primitive(b: Array[Byte], p: Int, id: Int): Any =
    id match
      case 0 =>
        null
      case 1 =>
        true
      case 2 =>
        false
      case 3 =>
        b(p).toInt
      case 4 =>
        ((b(p) & 0xff) | (b(p + 1) << 8)).toShort.toInt
      case 5 =>
        ByteReader.intLE(b, p)
      case 6 =>
        ByteReader.longLE(b, p)
      case 7 =>
        java.lang.Double.longBitsToDouble(ByteReader.longLE(b, p))
      case 8 =>
        BigDecimal(BigInt(ByteReader.intLE(b, p + 1)), b(p) & 0xff)
      case 9 =>
        BigDecimal(BigInt(ByteReader.longLE(b, p + 1)), b(p) & 0xff)
      case 10 =>
        // 16 bytes of little-endian two's complement
        val be = new Array[Byte](16)
        var k  = 0
        while k < 16 do
          be(k) = b(p + 16 - k)
          k += 1
        BigDecimal(BigInt(be), b(p) & 0xff)
      case 11 =>
        LocalDate.ofEpochDay(ByteReader.intLE(b, p).toLong)
      case 12 | 13 =>
        LogicalValues.timestamp(ByteReader.longLE(b, p), format.TimeUnit.MICROS)
      case 14 =>
        java.lang.Float.intBitsToFloat(ByteReader.intLE(b, p))
      case 15 =>
        val n = ByteReader.intLE(b, p)
        java.util.Arrays.copyOfRange(b, p + 4, p + 4 + n)
      case 16 =>
        String(b, p + 4, ByteReader.intLE(b, p), UTF_8)
      case 17 =>
        LogicalValues.localTime(ByteReader.longLE(b, p) * 1000L)
      case 18 | 19 =>
        LogicalValues.timestamp(ByteReader.longLE(b, p), format.TimeUnit.NANOS)
      case 20 =>
        java.util.UUID(ByteReader.longBE(b, p), ByteReader.longBE(b, p + 8))
      case other =>
        throw ParquetException.unsupported(s"VARIANT primitive type ${other}")

end Variant

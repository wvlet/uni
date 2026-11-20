package wvlet.uni.parquet.thrift

import wvlet.uni.parquet.ParquetException
import wvlet.uni.parquet.io.ByteReader

/**
  * A Thrift struct decoded without a schema: field id to value. Values are Boolean, Byte, Short,
  * Int, Long, Double, Array[Byte] (binary and string), IndexedSeq[Any] (list and set), Map[Any,
  * Any], or TStruct.
  *
  * Parquet metadata is read once per file and once per page, so a schema-less decoder keeps the
  * code small and skips fields added by newer writers for free.
  */
private[parquet] final class TStruct(fields: Map[Int, Any]):
  def has(id: Int): Boolean     = fields.contains(id)
  def get(id: Int): Option[Any] = fields.get(id)

  private def required(id: Int, name: String): Any = fields.getOrElse(
    id,
    throw ParquetException.corrupt(s"missing required Thrift field ${id} (${name})")
  )

  def int(id: Int, name: String): Int   = TStruct.toInt(required(id, name), name)
  def intOpt(id: Int): Option[Int]      = fields.get(id).map(TStruct.toInt(_, s"field ${id}"))
  def long(id: Int, name: String): Long = TStruct.toLong(required(id, name), name)
  def longOpt(id: Int): Option[Long]    = fields.get(id).flatMap(TStruct.asLong)
  def bool(id: Int, default: Boolean): Boolean =
    fields.get(id) match
      case Some(b: Boolean) =>
        b
      case _ =>
        default

  def binaryOpt(id: Int): Option[Array[Byte]] =
    fields.get(id) match
      case Some(b: Array[Byte]) =>
        Some(b)
      case _ =>
        None

  def string(id: Int, name: String): String =
    required(id, name) match
      case b: Array[Byte] =>
        String(b, "UTF-8")
      case other =>
        throw ParquetException.corrupt(s"${name} is not a string")

  def stringOpt(id: Int): Option[String] = binaryOpt(id).map(String(_, "UTF-8"))

  def struct(id: Int, name: String): TStruct =
    required(id, name) match
      case s: TStruct =>
        s
      case _ =>
        throw ParquetException.corrupt(s"${name} is not a struct")

  def structOpt(id: Int): Option[TStruct] =
    fields.get(id) match
      case Some(s: TStruct) =>
        Some(s)
      case _ =>
        None

  def list(id: Int): IndexedSeq[Any] =
    fields.get(id) match
      case Some(l: IndexedSeq[?]) =>
        l
      case _ =>
        IndexedSeq.empty

  def structList(id: Int): IndexedSeq[TStruct] = list(id).collect { case s: TStruct =>
    s
  }

  /** The id of the single field set in a Thrift union, with its value */
  def unionField: Option[(Int, Any)] = fields.headOption

end TStruct

private[parquet] object TStruct:
  private def asInt(v: Any): Option[Int] =
    v match
      case i: Int =>
        Some(i)
      case s: Short =>
        Some(s.toInt)
      case b: Byte =>
        Some(b.toInt)
      case _ =>
        None

  private def asLong(v: Any): Option[Long] =
    v match
      case l: Long =>
        Some(l)
      case _ =>
        asInt(v).map(_.toLong)

  private def toInt(v: Any, name: String): Int =
    v match
      case i: Int =>
        i
      case s: Short =>
        s.toInt
      case b: Byte =>
        b.toInt
      case _ =>
        throw ParquetException.corrupt(s"${name} is not an integer")

  private def toLong(v: Any, name: String): Long =
    v match
      case l: Long =>
        l
      case i: Int =>
        i.toLong
      case s: Short =>
        s.toLong
      case b: Byte =>
        b.toLong
      case _ =>
        throw ParquetException.corrupt(s"${name} is not an integer")

end TStruct

/**
  * Reads the Thrift Compact Protocol, which Parquet uses for its footer and page headers.
  */
private[parquet] object CompactProtocol:
  private final val Stop         = 0
  private final val BoolTrue     = 1
  private final val BoolFalse    = 2
  private final val TByte        = 3
  private final val TI16         = 4
  private final val TI32         = 5
  private final val TI64         = 6
  private final val TDouble      = 7
  private final val TBinary      = 8
  private final val TList        = 9
  private final val TSet         = 10
  private final val TMap         = 11
  private final val TStructType  = 12
  private final val MaxDepth     = 64
  private final val MaxContainer = 1 << 24

  def readStruct(r: ByteReader): TStruct = readStruct(r, 0)

  private def readStruct(r: ByteReader, depth: Int): TStruct =
    if depth > MaxDepth then
      throw ParquetException.corrupt("Thrift structs are nested too deeply")
    val fields  = Map.newBuilder[Int, Any]
    var fieldId = 0
    var done    = false
    while !done do
      val header = r.readUnsignedByte()
      val tpe    = header & 0x0f
      if tpe == Stop then
        done = true
      else
        val delta = header >>> 4
        fieldId =
          if delta == 0 then
            r.readZigZagInt().toShort.toInt
          else
            fieldId + delta
        val v =
          tpe match
            case BoolTrue =>
              true
            case BoolFalse =>
              false
            case _ =>
              readValue(r, tpe, depth)
        fields += fieldId -> v
    TStruct(fields.result())

  private def readValue(r: ByteReader, tpe: Int, depth: Int): Any =
    tpe match
      case BoolTrue | BoolFalse =>
        // Booleans inside lists and maps are one byte each
        r.readByte() == 1
      case TByte =>
        r.readByte()
      case TI16 =>
        r.readZigZagInt().toShort
      case TI32 =>
        r.readZigZagInt()
      case TI64 =>
        r.readZigZagLong()
      case TDouble =>
        r.readDoubleLE()
      case TBinary =>
        val len = r.readUnsignedVarInt()
        r.readBytes(len)
      case TList | TSet =>
        val header   = r.readUnsignedByte()
        val elemType = header & 0x0f
        val size     =
          if (header >>> 4) == 15 then
            r.readUnsignedVarInt()
          else
            header >>> 4
        checkSize(size, r)
        IndexedSeq.fill(size)(readValue(r, elemType, depth + 1))
      case TMap =>
        val size = r.readUnsignedVarInt()
        checkSize(size, r)
        if size == 0 then
          Map.empty
        else
          val kv = r.readUnsignedByte()
          (0 until size)
            .map { _ =>
              readValue(r, kv >>> 4, depth + 1) -> readValue(r, kv & 0x0f, depth + 1)
            }
            .toMap
      case TStructType =>
        readStruct(r, depth + 1)
      case other =>
        throw ParquetException.corrupt(s"unknown Thrift type ${other}")

  // Every element takes at least one byte, so a size beyond the remaining bytes is corrupt
  private def checkSize(size: Int, r: ByteReader): Unit =
    if size < 0 || size > MaxContainer || size > r.remaining then
      throw ParquetException.corrupt(s"Thrift container size ${size} is out of range")

end CompactProtocol

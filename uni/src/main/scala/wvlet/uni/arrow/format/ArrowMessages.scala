package wvlet.uni.arrow.format

import wvlet.uni.arrow.ArrowField
import wvlet.uni.arrow.ArrowSchema
import wvlet.uni.arrow.ArrowTimeUnit
import wvlet.uni.arrow.ArrowType

/** A FieldNode of a RecordBatch: the length and null count of one array, in field pre-order */
private[arrow] final case class FieldNode(length: Long, nullCount: Long)

/** A Buffer of a RecordBatch: its offset in the message body and its length */
private[arrow] final case class BufferRef(offset: Long, length: Long)

/** A Block of the file footer: where a message starts, its metadata length, and its body length */
private[arrow] final case class Block(offset: Long, metaDataLength: Int, bodyLength: Long)

/**
  * Encodes the Arrow metadata FlatBuffers (Schema.fbs, Message.fbs, File.fbs) that the writer
  * needs. Field slots and enum values follow the `.fbs` files of the Arrow format, metadata version
  * V5.
  */
private[arrow] object ArrowMessages:
  final val MetadataV5 = 4

  // MessageHeader union
  private final val HeaderSchema      = 1
  private final val HeaderRecordBatch = 3

  // Type union
  private final val TypeNull            = 1
  private final val TypeInt             = 2
  private final val TypeFloatingPoint   = 3
  private final val TypeBinary          = 4
  private final val TypeUtf8            = 5
  private final val TypeBool            = 6
  private final val TypeDecimal         = 7
  private final val TypeDate            = 8
  private final val TypeTime            = 9
  private final val TypeTimestamp       = 10
  private final val TypeInterval        = 11
  private final val TypeList            = 12
  private final val TypeStruct          = 13
  private final val TypeFixedSizeBinary = 15
  private final val TypeMap             = 17

  /** CompressionType values of BodyCompression */
  final val CodecLz4Frame = 0
  final val CodecZstd     = 1

  /** A Message holding a Schema */
  def schemaMessage(schema: ArrowSchema): Array[Byte] =
    val b      = FlatBufferBuilder()
    val header = writeSchema(b, schema)
    b.finish(message(b, HeaderSchema, header, 0L))

  /** A Message holding a RecordBatch; `codec` is -1 for an uncompressed body */
  def recordBatchMessage(
      length: Long,
      nodes: collection.IndexedSeq[FieldNode],
      buffers: collection.IndexedSeq[BufferRef],
      codec: Int,
      bodyLength: Long
  ): Array[Byte] =
    val b = FlatBufferBuilder()
    // Vectors of structs: elements last to first, struct fields last to first
    b.startVector(16, nodes.size, 8)
    var i = nodes.size - 1
    while i >= 0 do
      b.addLong(nodes(i).nullCount)
      b.addLong(nodes(i).length)
      i -= 1
    val nodesVec = b.endVector()
    b.startVector(16, buffers.size, 8)
    i = buffers.size - 1
    while i >= 0 do
      b.addLong(buffers(i).length)
      b.addLong(buffers(i).offset)
      i -= 1
    val buffersVec  = b.endVector()
    val compression =
      if codec < 0 then
        0
      else
        b.startTable(2)
        b.addByteField(0, codec)
        b.endTable()
    b.startTable(5)
    b.addLongField(0, length)
    b.addOffsetField(1, nodesVec)
    b.addOffsetField(2, buffersVec)
    if compression != 0 then
      b.addOffsetField(3, compression)
    val batch = b.endTable()
    b.finish(message(b, HeaderRecordBatch, batch, bodyLength))

  end recordBatchMessage

  /** The file footer: the schema and the blocks of record batches */
  def footer(schema: ArrowSchema, batches: collection.IndexedSeq[Block]): Array[Byte] =
    val b                                             = FlatBufferBuilder()
    val schemaOff                                     = writeSchema(b, schema)
    def blocks(bs: collection.IndexedSeq[Block]): Int =
      b.startVector(24, bs.size, 8)
      var i = bs.size - 1
      while i >= 0 do
        b.addLong(bs(i).bodyLength)
        b.addInt(0) // padding
        b.addInt(bs(i).metaDataLength)
        b.addLong(bs(i).offset)
        i -= 1
      b.endVector()
    val dictionaries = blocks(IndexedSeq.empty)
    val records      = blocks(batches)
    b.startTable(5)
    b.addShortField(0, MetadataV5)
    b.addOffsetField(1, schemaOff)
    b.addOffsetField(2, dictionaries)
    b.addOffsetField(3, records)
    b.finish(b.endTable())

  private def message(b: FlatBufferBuilder, headerType: Int, header: Int, bodyLength: Long): Int =
    b.startTable(5)
    b.addLongField(3, bodyLength)
    b.addOffsetField(2, header)
    b.addShortField(0, MetadataV5)
    b.addByteField(1, headerType)
    b.endTable()

  private def writeSchema(b: FlatBufferBuilder, schema: ArrowSchema): Int =
    val fields   = b.createOffsetVector(schema.fields.map(writeField(b, _)))
    val metadata = keyValues(b, schema.metadata)
    b.startTable(4)
    b.addOffsetField(1, fields)
    if metadata != 0 then
      b.addOffsetField(2, metadata)
    // endianness: Little (0) is the default
    b.endTable()

  private def keyValues(b: FlatBufferBuilder, kv: Map[String, String]): Int =
    if kv.isEmpty then
      0
    else
      val entries = kv
        .toSeq
        .sortBy(_._1)
        .map { (k, v) =>
          val ko = b.createString(k)
          val vo = b.createString(v)
          b.startTable(2)
          b.addOffsetField(0, ko)
          b.addOffsetField(1, vo)
          b.endTable()
        }
      b.createOffsetVector(entries)

  private def writeField(b: FlatBufferBuilder, f: ArrowField): Int =
    val children           = b.createOffsetVector(f.children.map(writeField(b, _)))
    val metadata           = keyValues(b, f.metadata)
    val name               = b.createString(f.name)
    val (typeType, typeOf) = writeType(b, f.tpe)
    b.startTable(7)
    b.addOffsetField(0, name)
    b.addOffsetField(3, typeOf)
    b.addOffsetField(5, children)
    if metadata != 0 then
      b.addOffsetField(6, metadata)
    b.addBoolField(1, f.nullable)
    b.addByteField(2, typeType)
    b.endTable()

  private def timeUnit(u: ArrowTimeUnit): Int = u.ordinal

  /** The Type union tag and the type table */
  private def writeType(b: FlatBufferBuilder, t: ArrowType): (Int, Int) =
    def empty(tag: Int): (Int, Int) =
      b.startTable(0)
      (tag, b.endTable())
    t match
      case ArrowType.Null =>
        empty(TypeNull)
      case ArrowType.Bool =>
        empty(TypeBool)
      case ArrowType.Utf8 =>
        empty(TypeUtf8)
      case ArrowType.Binary =>
        empty(TypeBinary)
      case ArrowType.List =>
        empty(TypeList)
      case ArrowType.Struct =>
        empty(TypeStruct)
      case ArrowType.Int(bits, signed) =>
        b.startTable(2)
        b.addIntField(0, bits)
        b.addBoolField(1, signed)
        (TypeInt, b.endTable())
      case ArrowType.FloatingPoint(bits) =>
        val precision =
          bits match
            case 16 =>
              0
            case 32 =>
              1
            case _ =>
              2
        b.startTable(1)
        b.addShortField(0, precision)
        (TypeFloatingPoint, b.endTable())
      case ArrowType.FixedSizeBinary(w) =>
        b.startTable(1)
        b.addIntField(0, w)
        (TypeFixedSizeBinary, b.endTable())
      case ArrowType.Date32 =>
        // DateUnit defaults to MILLISECOND (1); DAY is 0
        b.startTable(1)
        b.addShortField(0, 0)
        (TypeDate, b.endTable())
      case ArrowType.Time(u) =>
        val bits =
          if u == ArrowTimeUnit.SECOND || u == ArrowTimeUnit.MILLISECOND then
            32
          else
            64
        b.startTable(2)
        b.addShortField(0, timeUnit(u))
        b.addIntField(1, bits)
        (TypeTime, b.endTable())
      case ArrowType.Timestamp(u, tz) =>
        val tzOff = tz.map(b.createString).getOrElse(0)
        b.startTable(2)
        if tzOff != 0 then
          b.addOffsetField(1, tzOff)
        b.addShortField(0, timeUnit(u))
        (TypeTimestamp, b.endTable())
      case ArrowType.IntervalMonthDayNano =>
        b.startTable(1)
        b.addShortField(0, 2)
        (TypeInterval, b.endTable())
      case ArrowType.Decimal(precision, scale, bits) =>
        b.startTable(3)
        b.addIntField(0, precision)
        b.addIntField(1, scale)
        b.addIntField(2, bits)
        (TypeDecimal, b.endTable())
      case ArrowType.Map(sorted) =>
        b.startTable(1)
        b.addBoolField(0, sorted)
        (TypeMap, b.endTable())
    end match

  end writeType

end ArrowMessages

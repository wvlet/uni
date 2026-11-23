package wvlet.uni.cloud.parquet.encoding

import wvlet.uni.io.codec.Bytes
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.column.*
import wvlet.uni.cloud.parquet.format.Encoding
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.io.ByteReader

/** Decodes the non-null values of a page into a value buffer */
private[parquet] object ValueDecoder:

  /**
    * A buffer for values of `tpe`. With `int32AsLong`, INT32 values go in a [[LongBuffer]], widened
    * as they are decoded, so a column read as Long needs no Int array and no widening copy.
    */
  def newBuffer(tpe: PhysicalType, capacity: Int, int32AsLong: Boolean = false): ValueBuffer =
    tpe match
      case PhysicalType.BOOLEAN =>
        BooleanBuffer(capacity)
      case PhysicalType.INT32 if int32AsLong =>
        LongBuffer(capacity)
      case PhysicalType.INT32 =>
        IntBuffer(capacity)
      case PhysicalType.INT64 =>
        LongBuffer(capacity)
      case PhysicalType.FLOAT =>
        FloatBuffer(capacity)
      case PhysicalType.DOUBLE =>
        DoubleBuffer(capacity)
      case PhysicalType.INT96 | PhysicalType.BYTE_ARRAY | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        BinaryBuffer(capacity)

  /** The byte width of fixed-size values, or -1 for BYTE_ARRAY */
  def fixedWidth(tpe: PhysicalType, typeLength: Int): Int =
    tpe match
      case PhysicalType.INT32 | PhysicalType.FLOAT =>
        4
      case PhysicalType.INT64 | PhysicalType.DOUBLE =>
        8
      case PhysicalType.INT96 =>
        12
      case PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        if typeLength <= 0 then
          throw ParquetException.corrupt(s"FIXED_LEN_BYTE_ARRAY has invalid length ${typeLength}")
        typeLength
      case PhysicalType.BOOLEAN | PhysicalType.BYTE_ARRAY =>
        -1

  /** How many dictionary indices are expanded at a time */
  final val DictionaryBlock = 1024

  def decode(
      encoding: Encoding,
      tpe: PhysicalType,
      typeLength: Int,
      buf: Array[Byte],
      start: Int,
      end: Int,
      n: Int,
      out: ValueBuffer,
      dictionary: Option[ValueBuffer],
      scratch: Array[Int] = null
  ): Unit =
    if n > 0 then
      encoding match
        case Encoding.PLAIN =>
          decodePlain(tpe, typeLength, buf, start, end, n, out)
        case Encoding.PLAIN_DICTIONARY | Encoding.RLE_DICTIONARY =>
          val dict = dictionary.getOrElse(
            throw ParquetException.corrupt("dictionary-encoded page without a dictionary page")
          )
          if start >= end then
            throw ParquetException.corrupt("dictionary index data is empty")
          val bitWidth = buf(start) & 0xff
          // Indices are expanded and looked up one block at a time, so a page never needs an index
          // array of its own size. They go in `scratch` when it holds a block.
          val block   = math.min(n, DictionaryBlock)
          val indices =
            if scratch != null && scratch.length >= block then
              scratch
            else
              new Array[Int](block)
          val dec  = RleBitPackedDecoder(buf, start + 1, end, bitWidth)
          var done = 0
          while done < n do
            val k = math.min(block, n - done)
            dec.read(indices, 0, k)
            out.appendFromDictionary(dict, indices, k)
            done += k
        case Encoding.RLE if tpe == PhysicalType.BOOLEAN =>
          // A 4-byte length, then RLE with bit width 1
          val r   = ByteReader(buf, start, end)
          val len = r.readIntLE()
          r.ensure(len)
          val bits = new Array[Int](n)
          RleBitPackedDecoder(buf, r.pos, r.pos + len, 1).read(bits, 0, n)
          val b = out.asInstanceOf[BooleanBuffer]
          b.ensure(n)
          var i = 0
          while i < n do
            b.values(b.size + i) = bits(i) != 0
            i += 1
          b.size += n
        case Encoding.DELTA_BINARY_PACKED =>
          DeltaDecoder.decodeBinaryPacked(tpe, buf, start, end, n, out)
        case Encoding.DELTA_LENGTH_BYTE_ARRAY =>
          DeltaDecoder.decodeLengthByteArray(buf, start, end, n, out.asInstanceOf[BinaryBuffer])
        case Encoding.DELTA_BYTE_ARRAY =>
          DeltaDecoder.decodeByteArray(buf, start, end, n, out.asInstanceOf[BinaryBuffer])
        case Encoding.BYTE_STREAM_SPLIT =>
          decodeByteStreamSplit(tpe, typeLength, buf, start, end, n, out)
        case other =>
          throw ParquetException.unsupported(s"${other} encoding for ${tpe} values")

  def decodePlain(
      tpe: PhysicalType,
      typeLength: Int,
      buf: Array[Byte],
      start: Int,
      end: Int,
      n: Int,
      out: ValueBuffer
  ): Unit =
    val r = ByteReader(buf, start, end)
    tpe match
      case PhysicalType.BOOLEAN =>
        r.ensure((n + 7) / 8)
        val b = out.asInstanceOf[BooleanBuffer]
        b.ensure(n)
        var i = 0
        while i < n do
          b.values(b.size + i) = ((buf(start + (i >>> 3)) >>> (i & 7)) & 1) != 0
          i += 1
        b.size += n
      case PhysicalType.INT32 =>
        ensureBytes(r, n.toLong * 4)
        out match
          case b: LongBuffer =>
            // INT32 values read as Long (see [[newBuffer]]) are widened as they are decoded
            b.ensure(n)
            var i = 0
            while i < n do
              b.values(b.size + i) = Bytes.intLE(buf, start + i * 4).toLong
              i += 1
            b.size += n
          case _ =>
            val b = out.asInstanceOf[IntBuffer]
            b.ensure(n)
            var i = 0
            while i < n do
              b.values(b.size + i) = Bytes.intLE(buf, start + i * 4)
              i += 1
            b.size += n
      case PhysicalType.INT64 =>
        ensureBytes(r, n.toLong * 8)
        val b = out.asInstanceOf[LongBuffer]
        b.ensure(n)
        var i = 0
        while i < n do
          b.values(b.size + i) = Bytes.longLE(buf, start + i * 8)
          i += 1
        b.size += n
      case PhysicalType.FLOAT =>
        ensureBytes(r, n.toLong * 4)
        val b = out.asInstanceOf[FloatBuffer]
        b.ensure(n)
        var i = 0
        while i < n do
          b.values(b.size + i) = java.lang.Float.intBitsToFloat(Bytes.intLE(buf, start + i * 4))
          i += 1
        b.size += n
      case PhysicalType.DOUBLE =>
        ensureBytes(r, n.toLong * 8)
        val b = out.asInstanceOf[DoubleBuffer]
        b.ensure(n)
        var i = 0
        while i < n do
          b.values(b.size + i) = java.lang.Double.longBitsToDouble(Bytes.longLE(buf, start + i * 8))
          i += 1
        b.size += n
      case PhysicalType.BYTE_ARRAY =>
        val b = out.asInstanceOf[BinaryBuffer]
        b.ensureValues(n)
        var i = 0
        while i < n do
          val len = r.readIntLE()
          r.ensure(len)
          b.add(buf, r.pos, len)
          r.pos += len
          i += 1
      case PhysicalType.INT96 | PhysicalType.FIXED_LEN_BYTE_ARRAY =>
        val w = fixedWidth(tpe, typeLength)
        ensureBytes(r, n.toLong * w)
        val b = out.asInstanceOf[BinaryBuffer]
        b.ensureValues(n)
        b.ensureData(n * w)
        var i = 0
        while i < n do
          b.add(buf, start + i * w, w)
          i += 1
    end match

  end decodePlain

  private def ensureBytes(r: ByteReader, n: Long): Unit =
    if n > r.remaining then
      throw ParquetException.corrupt(s"page needs ${n} bytes of values, but has ${r.remaining}")

  /** BYTE_STREAM_SPLIT stores byte k of every value in stream k */
  private def decodeByteStreamSplit(
      tpe: PhysicalType,
      typeLength: Int,
      buf: Array[Byte],
      start: Int,
      end: Int,
      n: Int,
      out: ValueBuffer
  ): Unit =
    val w = fixedWidth(tpe, typeLength)
    if w <= 0 then
      throw ParquetException.unsupported(s"BYTE_STREAM_SPLIT encoding for ${tpe} values")
    // The streams span the whole page, which may hold more values than `n` only if it is corrupt
    val total = (end - start).toLong
    if total % w != 0 || total / w < n then
      throw ParquetException.corrupt("BYTE_STREAM_SPLIT data has the wrong size")
    val count  = (total / w).toInt
    val joined = new Array[Byte](n * w)
    var i      = 0
    while i < n do
      var k = 0
      while k < w do
        joined(i * w + k) = buf(start + k * count + i)
        k += 1
      i += 1
    decodePlain(tpe, typeLength, joined, 0, joined.length, n, out)

end ValueDecoder

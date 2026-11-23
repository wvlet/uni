package wvlet.uni.cloud.arrow

import wvlet.uni.cloud.arrow.ArrowPlan.*
import wvlet.uni.cloud.arrow.format.ArrowMessages
import wvlet.uni.cloud.arrow.format.Block
import wvlet.uni.cloud.arrow.format.BufferRef
import wvlet.uni.cloud.arrow.format.FieldNode
import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.io.codec.Lz4Frame
import wvlet.uni.io.codec.ZstdCompressor
import wvlet.uni.cloud.parquet.ColumnBatch
import wvlet.uni.cloud.parquet.ColumnVector
import wvlet.uni.cloud.parquet.ColumnVector.*
import wvlet.uni.cloud.parquet.ParquetException
import wvlet.uni.cloud.parquet.LogicalValues
import wvlet.uni.cloud.parquet.ParquetSchema
import wvlet.uni.cloud.parquet.io.ByteReader
import wvlet.uni.cloud.parquet.writer.BatchShredder

import java.io.OutputStream
import scala.collection.mutable.ArrayBuffer

/**
  * Writes [[ColumnBatch]]es as an Arrow IPC stream or file. Each batch becomes one record batch.
  * The schema message goes out with the first batch (or at [[close]] when there is none), so a
  * schema the writer cannot map fails at creation, before any bytes are written.
  *
  * Each batch is checked and encoded in memory before any of its bytes are written, so when
  * [[write]] throws, the output holds only whole batches. A stream reader cannot tell such a stream
  * from a short result, so when a write fails, call [[abort]] (or fail the HTTP response) rather
  * than [[close]], which would end the stream normally.
  */
class ArrowWriter private[arrow] (
    out: OutputStream,
    parquetSchema: ParquetSchema,
    options: ArrowWriteOptions,
    fileFormat: Boolean
) extends AutoCloseable:
  private val plans: IndexedSeq[ArrowPlan] = parquetSchema
    .fields
    .map(ArrowPlan.of(_, options.binaryAsString))

  /** The Arrow schema of the output */
  val schema: ArrowSchema = ArrowSchema(plans.map(_.arrow), options.metadata)

  private val checker  = BatchShredder(parquetSchema, allowInt96 = true)
  private val encoder  = BatchEncoder(options)
  private val blocks   = ArrayBuffer.empty[Block]
  private var position = 0L
  private var started  = false
  private var closed   = false
  private var rows     = 0L

  /** The number of rows written so far */
  def numRows: Long = rows

  /** Writes one batch as one record batch; throws [[ArrowException]] when it does not fit */
  def write(batch: ColumnBatch): Unit =
    if closed then
      throw IllegalStateException("The Arrow writer is closed")
    val vectors =
      try
        checker.check(batch)
      catch
        case e: ParquetException =>
          throw ArrowException(e.getMessage, e)
    val (meta, body) = encoder.encode(plans, vectors, batch.numRows)
    start()
    writeMessage(meta, body)
    rows += batch.numRows

  def writeAll(batches: IterableOnce[ColumnBatch]): Unit = batches.iterator.foreach(write)

  /**
    * Writes the end-of-stream marker (and the footer for the file format), then closes the output
    */
  override def close(): Unit =
    if !closed then
      start()
      writeInt(-1)
      writeInt(0)
      if fileFormat then
        val footer = ArrowMessages.footer(schema, blocks)
        writeBytes(footer, footer.length)
        writeInt(footer.length)
        writeBytes(ArrowWriter.Magic, 6)
      closed = true
      out.close()

  /**
    * Closes the output without the end-of-stream marker or footer, after a failed write. The output
    * then reads as truncated in the file format; in the stream format, also fail the transport (for
    * example, the HTTP response), since stream readers accept a stream that ends between messages.
    */
  def abort(): Unit =
    if !closed then
      closed = true
      out.close()

  private def start(): Unit =
    if !started then
      started = true
      if fileFormat then
        writeBytes(ArrowWriter.Magic, 6)
        writeBytes(ArrowWriter.Zeros, 2)
      writeMessage(ArrowMessages.schemaMessage(schema), null)

  /** Encapsulated message: continuation marker, metadata length, metadata, padding, body */
  private def writeMessage(meta: Array[Byte], body: ByteBuf): Unit =
    val padded = ((meta.length + 8 + 7) & ~7) - 8
    val offset = position
    writeInt(-1)
    writeInt(padded)
    writeBytes(meta, meta.length)
    writeBytes(ArrowWriter.Zeros, padded - meta.length)
    val bodyLength =
      if body == null then
        0
      else
        writeBytes(body.array, body.size)
        body.size
    if body != null then
      blocks += Block(offset, 8 + padded, bodyLength)

  private val intBuf = new Array[Byte](4)

  private def writeInt(v: Int): Unit =
    intBuf(0) = v.toByte
    intBuf(1) = (v >>> 8).toByte
    intBuf(2) = (v >>> 16).toByte
    intBuf(3) = (v >>> 24).toByte
    writeBytes(intBuf, 4)

  private def writeBytes(b: Array[Byte], n: Int): Unit =
    out.write(b, 0, n)
    position += n

end ArrowWriter

private[arrow] object ArrowWriter:
  val Magic: Array[Byte] = "ARROW1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
  val Zeros: Array[Byte] = new Array[Byte](8)

/** Turns checked vectors into a RecordBatch message and its body */
private[arrow] final class BatchEncoder(options: ArrowWriteOptions):
  private val body    = ByteBuf(64 * 1024)
  private val scratch = ByteBuf(64 * 1024)
  private val nodes   = ArrayBuffer.empty[FieldNode]
  private val buffers = ArrayBuffer.empty[BufferRef]

  private val codec =
    options.compression match
      case ArrowCompression.NONE =>
        -1
      case ArrowCompression.LZ4_FRAME =>
        ArrowMessages.CodecLz4Frame
      case ArrowCompression.ZSTD =>
        ArrowMessages.CodecZstd

  /** The metadata and the body (valid until the next call) of one batch */
  def encode(
      plans: IndexedSeq[ArrowPlan],
      vectors: Array[ColumnVector],
      n: Int
  ): (Array[Byte], ByteBuf) =
    body.reset()
    nodes.clear()
    buffers.clear()
    var i = 0
    while i < plans.length do
      field(plans(i), vectors(i), n)
      i += 1
    val meta = ArrowMessages.recordBatchMessage(n.toLong, nodes, buffers, codec, body.size)
    (meta, body)

  private def fail(p: ArrowPlan, msg: String): Nothing =
    throw ArrowException(s"Cannot write column ${p.parquet.pathString} as Arrow: ${msg}")

  // Buffers

  private def pad(): Unit =
    while (body.size & 7) != 0 do
      body.writeByte(0)

  private def emptyBuffer(): Unit = buffers += BufferRef(body.size, 0)

  // Not inlined into `buffer`: a case class of another package referenced from an inline method
  // fails at run time with NoClassDefFoundError for the package
  private def endBuffer(start: Int): Unit =
    buffers += BufferRef(start, body.size - start)
    pad()

  /** Appends one buffer that `fill` writes, compressed when the options ask for it */
  private inline def buffer(inline fill: ByteBuf => Unit): Unit =
    if codec < 0 then
      val start = body.size
      fill(body)
      endBuffer(start)
    else
      scratch.reset()
      fill(scratch)
      compressed()

  private def compressed(): Unit =
    val n     = scratch.size
    val start = body.size
    if n > 0 then
      val c =
        if codec == ArrowMessages.CodecZstd then
          ZstdCompressor.compress(
            scratch.array,
            n,
            options.compressionLevel.getOrElse(ZstdCompressor.DefaultLevel)
          )
        else
          Lz4Frame.compress(scratch.array, 0, n)
      if c.length < n then
        body.writeLongLE(n.toLong)
        body.write(c)
      else
        // Stored uncompressed, as the -1 length says
        body.writeLongLE(-1L)
        body.write(scratch.array, 0, n)
    endBuffer(start)

  private def nullCount(nulls: Array[Boolean], n: Int): Int =
    if nulls == null then
      0
    else
      var c = 0
      var i = 0
      while i < n do
        if nulls(i) then
          c += 1
        i += 1
      c

  /** Adds the field node and the validity bitmap; returns the null flags to honour, or null */
  private def validity(p: ArrowPlan, v: ColumnVector, n: Int): Array[Boolean] =
    val nulls = v.nullFlags
    // Non-null fields may still flag rows under a null parent; those rows are hidden by the parent
    val count =
      if p.arrow.nullable then
        nullCount(nulls, n)
      else
        0
    nodes += FieldNode(n, count)
    if count == 0 then
      emptyBuffer()
      null
    else
      buffer { b =>
        var i = 0
        while i < n do
          var bits = 0
          var j    = 0
          while j < 8 && i + j < n do
            if !nulls(i + j) then
              bits |= 1 << j
            j += 1
          b.writeByte(bits)
          i += 8
      }
      nulls

  private def offsets(o: Array[Int], n: Int, base: Int): Unit = buffer { b =>
    var i = 0
    while i <= n do
      b.writeIntLE(o(i) - base)
      i += 1
  }

  private def field(p: ArrowPlan, v: ColumnVector, n: Int): Unit =
    p match
      case l: LeafPlan =>
        leaf(l, v, n)
      case l: ListPlan =>
        val lc = v.asInstanceOf[ListColumn]
        validity(p, v, n)
        offsets(lc.offsets, n, 0)
        field(l.element, lc.elements, lc.elements.size)
      case m: MapPlan =>
        val mc = v.asInstanceOf[MapColumn]
        validity(p, v, n)
        offsets(mc.offsets, n, 0)
        val size = mc.keys.size
        // The entries struct: never null
        nodes += FieldNode(size, 0)
        emptyBuffer()
        field(m.key, mc.keys, size)
        field(m.value, mc.values, size)
      case s: StructPlan =>
        val sc = v.asInstanceOf[StructColumn]
        validity(p, v, n)
        s.children
          .foreach { c =>
            val child = sc.children.find(_.name == c.parquet.name).get
            field(c, child, n)
          }

  private def leaf(p: LeafPlan, v: ColumnVector, n: Int): Unit =
    if p.leaf == Leaf.Null then
      // The null type has no buffers; every row is null
      nodes += FieldNode(n, n)
    else
      val nulls                          = validity(p, v, n)
      inline def isNull(i: Int): Boolean = nulls != null && nulls(i)
      p.leaf match
        case Leaf.Null =>
        case Leaf.Bool =>
          val values = v.asInstanceOf[BooleanColumn].values
          buffer { b =>
            var i = 0
            while i < n do
              var bits = 0
              var j    = 0
              while j < 8 && i + j < n do
                if values(i + j) then
                  bits |= 1 << j
                j += 1
              b.writeByte(bits)
              i += 8
          }
        case Leaf.FromInt(bytes, signed) =>
          val values = v.asInstanceOf[IntColumn].values
          if bytes == 4 then
            buffer { b =>
              var i = 0
              while i < n do
                b.writeIntLE(values(i))
                i += 1
            }
          else
            val bits     = bytes * 8
            val (lo, hi) =
              if signed then
                (-(1 << (bits - 1)), (1 << (bits - 1)) - 1)
              else
                (0, (1 << bits) - 1)
            var i = 0
            while i < n do
              val x = values(i)
              if (x < lo || x > hi) && !isNull(i) then
                fail(p, s"row ${i} holds ${x}, which does not fit in ${p.arrow.tpe}")
              i += 1
            buffer { b =>
              var i = 0
              while i < n do
                val x = values(i)
                b.writeByte(x)
                if bytes == 2 then
                  b.writeByte(x >>> 8)
                i += 1
            }
        case Leaf.FromLong =>
          val values = v.asInstanceOf[LongColumn].values
          buffer { b =>
            var i = 0
            while i < n do
              b.writeLongLE(values(i))
              i += 1
          }
        case Leaf.Float =>
          val values = v.asInstanceOf[FloatColumn].values
          buffer { b =>
            var i = 0
            while i < n do
              b.writeIntLE(java.lang.Float.floatToRawIntBits(values(i)))
              i += 1
          }
        case Leaf.Double =>
          val values = v.asInstanceOf[DoubleColumn].values
          buffer { b =>
            var i = 0
            while i < n do
              b.writeLongLE(java.lang.Double.doubleToRawLongBits(values(i)))
              i += 1
          }
        case Leaf.VarBinary =>
          val c    = v.asInstanceOf[BinaryColumn]
          val base = c.offsets(0)
          offsets(c.offsets, n, base)
          buffer(_.write(c.data, base, c.offsets(n) - base))
        case Leaf.Fixed(width) =>
          val c       = v.asInstanceOf[BinaryColumn]
          var uniform = true
          var k       = 0
          while uniform && k < n do
            uniform = c.length(k) == width
            k += 1
          if uniform then
            // Every row has its bytes: copy them in one run
            buffer(_.write(c.data, c.offsets(0), width * n))
          else
            buffer { b =>
              var i = 0
              while i < n do
                if c.length(i) == width then
                  b.write(c.data, c.offsets(i), width)
                else
                  // A null row, or one under a null parent
                  b.write(BatchEncoder.zeros(width), 0, width)
                i += 1
            }
        case Leaf.Int96 =>
          val c     = v.asInstanceOf[BinaryColumn]
          val nanos = new Array[Long](n)
          var i     = 0
          while i < n do
            if c.length(i) != 12 && c.length(i) != 0 && !isNull(i) then
              fail(p, s"row ${i} has ${c.length(i)} bytes, but INT96 values have 12")
            if c.length(i) == 12 then
              val off = c.offsets(i)
              val nod = ByteReader.longLE(c.data, off)
              val day = ByteReader.intLE(c.data, off + 8).toLong
              try
                nanos(i) = Math.addExact(
                  Math.multiplyExact(day - LogicalValues.JulianEpochDay, 86400L * 1000000000L),
                  nod
                )
              catch
                case _: ArithmeticException if !isNull(i) =>
                  fail(p, s"row ${i} is outside the years 1677 to 2262 of timestamp(ns)")
                case _: ArithmeticException =>
            i += 1
          buffer { b =>
            var i = 0
            while i < n do
              b.writeLongLE(nanos(i))
              i += 1
          }
        case Leaf.Interval =>
          val c = v.asInstanceOf[BinaryColumn]
          buffer { b =>
            var i = 0
            while i < n do
              if c.length(i) == 12 then
                val off = c.offsets(i)
                b.writeIntLE(ByteReader.intLE(c.data, off))
                b.writeIntLE(ByteReader.intLE(c.data, off + 4))
                b.writeLongLE((ByteReader.intLE(c.data, off + 8) & 0xffffffffL) * 1000000L)
              else
                b.writeLongLE(0L)
                b.writeLongLE(0L)
              i += 1
          }
        case Leaf.DecimalFromInt(bytes) =>
          val values = v.asInstanceOf[IntColumn].values
          buffer { b =>
            var i = 0
            while i < n do
              BatchEncoder.writeDecimal(b, values(i).toLong, bytes)
              i += 1
          }
        case Leaf.DecimalFromLong(bytes) =>
          val values = v.asInstanceOf[LongColumn].values
          buffer { b =>
            var i = 0
            while i < n do
              BatchEncoder.writeDecimal(b, values(i), bytes)
              i += 1
          }
        case Leaf.DecimalFromBinary(bytes) =>
          val c = v.asInstanceOf[BinaryColumn]
          var i = 0
          while i < n do
            if !isNull(i) && !BatchEncoder.fitsDecimal(c.data, c.offsets(i), c.length(i), bytes)
            then
              fail(p, s"row ${i} has a ${c.length(i)}-byte unscaled value, over ${bytes} bytes")
            i += 1
          buffer { b =>
            var i = 0
            while i < n do
              BatchEncoder.writeBigEndianDecimal(b, c.data, c.offsets(i), c.length(i), bytes)
              i += 1
          }
      end match

  end leaf

end BatchEncoder

private[arrow] object BatchEncoder:
  private val zeroBytes = new Array[Byte](16)

  def zeros(n: Int): Array[Byte] =
    if n <= zeroBytes.length then
      zeroBytes
    else
      new Array[Byte](n)

  /** A sign-extended little-endian value of `bytes` bytes */
  def writeDecimal(b: ByteBuf, v: Long, bytes: Int): Unit =
    b.writeLongLE(v)
    val sign = v >> 63
    var k    = 8
    while k < bytes do
      b.writeLongLE(sign)
      k += 8

  /**
    * Whether a big-endian value fits in `bytes` bytes: any extra leading bytes only extend the sign
    */
  def fitsDecimal(data: Array[Byte], off: Int, len: Int, bytes: Int): Boolean =
    if len <= bytes then
      true
    else
      val signByte =
        if data(off) < 0 then
          -1
        else
          0
      var i = 0
      while i < len - bytes do
        if data(off + i) != signByte then
          return false
        i += 1
      // The kept top byte must carry the same sign
      (data(off + len - bytes) < 0) == (signByte == -1)

  /** A big-endian two's complement value as `bytes` little-endian bytes, sign-extended */
  def writeBigEndianDecimal(b: ByteBuf, data: Array[Byte], off: Int, len: Int, bytes: Int): Unit =
    val n    = math.min(len, bytes)
    var i    = 0
    val last = off + len - 1
    while i < n do
      b.writeByte(data(last - i))
      i += 1
    val sign =
      if len > 0 && data(off) < 0 then
        0xff
      else
        0
    while i < bytes do
      b.writeByte(sign)
      i += 1

end BatchEncoder

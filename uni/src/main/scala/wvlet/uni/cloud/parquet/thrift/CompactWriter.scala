package wvlet.uni.cloud.parquet.thrift

import wvlet.uni.io.codec.ByteBuf

/**
  * Writes the Thrift Compact Protocol. Call the field methods in increasing field-id order inside
  * `struct`; nested structs and lists keep their own field-id state.
  *
  * {{{
  * val w = CompactWriter()
  * w.struct {
  *   w.i32(1, 42)
  *   w.binary(2, "name".getBytes("UTF-8"))
  * }
  * }}}
  */
private[parquet] final class CompactWriter(val out: ByteBuf = ByteBuf()):
  import CompactWriter.*

  private var lastFieldId = 0
  private val stack       = IntStack()

  def toByteArray: Array[Byte] = out.toByteArray

  /** Writes a struct: the fields that `body` writes, then a stop byte */
  def struct(body: => Unit): Unit =
    stack.push(lastFieldId)
    lastFieldId = 0
    body
    out.writeByte(Stop)
    lastFieldId = stack.pop()

  private def fieldHeader(id: Int, tpe: Int): Unit =
    val delta = id - lastFieldId
    if delta > 0 && delta <= 15 then
      out.writeByte((delta << 4) | tpe)
    else
      out.writeByte(tpe)
      out.writeUnsignedVarInt(zigzag(id))
    lastFieldId = id

  def bool(id: Int, v: Boolean): Unit = fieldHeader(
    id,
    if v then
      BoolTrue
    else
      BoolFalse
  )

  def i8(id: Int, v: Int): Unit =
    fieldHeader(id, TByte)
    out.writeByte(v)

  def i16(id: Int, v: Int): Unit =
    fieldHeader(id, TI16)
    out.writeUnsignedVarInt(zigzag(v))

  def i32(id: Int, v: Int): Unit =
    fieldHeader(id, TI32)
    out.writeUnsignedVarInt(zigzag(v))

  def i64(id: Int, v: Long): Unit =
    fieldHeader(id, TI64)
    out.writeUnsignedVarLong((v << 1) ^ (v >> 63))

  def binary(id: Int, v: Array[Byte]): Unit =
    fieldHeader(id, TBinary)
    out.writeUnsignedVarInt(v.length)
    out.write(v)

  def string(id: Int, v: String): Unit = binary(id, v.getBytes("UTF-8"))

  def structField(id: Int)(body: => Unit): Unit =
    fieldHeader(id, TStructType)
    struct(body)

  /** A union with one empty struct member, such as `LogicalType.STRING` */
  def emptyStructField(id: Int): Unit = structField(id)(())

  private def listHeader(id: Int, elemType: Int, size: Int): Unit =
    fieldHeader(id, TList)
    if size < 15 then
      out.writeByte((size << 4) | elemType)
    else
      out.writeByte(0xf0 | elemType)
      out.writeUnsignedVarInt(size)

  def i32List(id: Int, values: Seq[Int]): Unit =
    listHeader(id, TI32, values.size)
    values.foreach(v => out.writeUnsignedVarInt(zigzag(v)))

  def stringList(id: Int, values: Seq[String]): Unit =
    listHeader(id, TBinary, values.size)
    values.foreach { v =>
      val b = v.getBytes("UTF-8")
      out.writeUnsignedVarInt(b.length)
      out.write(b)
    }

  def structList[A](id: Int, values: Seq[A])(body: A => Unit): Unit =
    listHeader(id, TStructType, values.size)
    values.foreach(v => struct(body(v)))

end CompactWriter

private[parquet] object CompactWriter:
  private final val Stop        = 0
  private final val BoolTrue    = 1
  private final val BoolFalse   = 2
  private final val TByte       = 3
  private final val TI16        = 4
  private final val TI32        = 5
  private final val TI64        = 6
  private final val TBinary     = 8
  private final val TList       = 9
  private final val TStructType = 12

  private def zigzag(v: Int): Int = (v << 1) ^ (v >> 31)

  private final class IntStack:
    private var a          = new Array[Int](8)
    private var len        = 0
    def push(v: Int): Unit =
      if len == a.length then
        a = java.util.Arrays.copyOf(a, len * 2)
      a(len) = v
      len += 1

    def pop(): Int =
      len -= 1
      a(len)

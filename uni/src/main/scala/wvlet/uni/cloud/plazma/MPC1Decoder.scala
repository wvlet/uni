package wvlet.uni.cloud.plazma

import wvlet.uni.cloud.parquet.ColumnVector.BinaryColumn
import wvlet.uni.cloud.parquet.ColumnVector.BooleanColumn
import wvlet.uni.cloud.parquet.ColumnVector.DoubleColumn
import wvlet.uni.cloud.parquet.ColumnVector.LongColumn
import wvlet.uni.cloud.parquet.ParquetField
import wvlet.uni.cloud.parquet.format.LogicalType
import wvlet.uni.cloud.parquet.format.PhysicalType
import wvlet.uni.cloud.parquet.format.Repetition
import wvlet.uni.msgpack.impl.ByteArrayBuffer
import wvlet.uni.msgpack.spi.Code
import wvlet.uni.msgpack.spi.MessageException
import wvlet.uni.msgpack.spi.MessageFormat
import wvlet.uni.msgpack.spi.OffsetUnpacker
import wvlet.uni.msgpack.spi.ReadCursor
import wvlet.uni.msgpack.spi.ValueType

/**
  * The type of the values in an MPC1 column, found by [[MPC1Decoder.columnType]]. It names the
  * typed reader that decodes the column.
  */
enum MPC1ColumnType:
  /** Integers only: read with `readLongs` */
  case LONG

  /** Floats, or floats mixed with integers: read with `readDoubles` */
  case DOUBLE

  /** Booleans only: read with `readBooleans` */
  case BOOLEAN

  /** Strings only: read with `readStrings` */
  case STRING

  /** Binaries, or binaries mixed with strings: read with `readBinaries` */
  case BINARY

  /** No row has a value (every row is nil, or there are no rows); any typed reader reads it */
  case NIL

  /**
    * Arrays, maps, extension values (such as timestamps), or mixed types: read with `readColumn`
    */
  case ANY

end MPC1ColumnType

/**
  * Decodes MPC1 column blocks (one MessagePack value per row, nil for rows without a value) into
  * unboxed column vectors, without building a MessagePack `Value` per row.
  *
  * Each reader accepts nil (a null row) and the values of its type. A row with another type fails
  * the read with an [[MPC1Exception]] naming the column and row; call [[columnType]] first to pick
  * the reader. Two widenings are allowed: `decodeDoubles` reads integers, and `decodeBinaries`
  * reads strings as their UTF-8 bytes. An unsigned 64-bit integer over `Long.MaxValue` fails
  * `decodeLongs` and `decodeDoubles`.
  *
  * The vectors are Parquet [[wvlet.uni.cloud.parquet.ColumnVector]]s of an optional field named
  * after the column, so they work with code that reads Parquet column batches. Their `nullFlags` is
  * null when no row is nil.
  *
  * [[MPC1File]] wraps these functions by column name. Use them directly to decompress a block once
  * (`MPC1File.readColumnBlock`), probe it, then decode it.
  */
object MPC1Decoder:

  private inline val TypeInt   = 1
  private inline val TypeFloat = 2
  private inline val TypeBool  = 4
  private inline val TypeStr   = 8
  private inline val TypeBin   = 16

  private inline val InitialCapacity = 1024

  /** Finds the type of a column block by reading only the format byte of each value */
  def columnType(block: Array[Byte]): MPC1ColumnType =
    val cursor = newCursor(block)
    var seen   = 0
    var other  = false
    while !other && cursor.position < block.length do
      val b = cursor.peekByte
      MessageFormat.of(b).valueType match
        case ValueType.NIL     =>
        case ValueType.INTEGER =>
          seen |= TypeInt
        case ValueType.FLOAT =>
          seen |= TypeFloat
        case ValueType.BOOLEAN =>
          seen |= TypeBool
        case ValueType.STRING =>
          seen |= TypeStr
        case ValueType.BINARY =>
          seen |= TypeBin
        case _ =>
          other = true
      if !other then
        OffsetUnpacker.skipValue(cursor)
        cursor.proceedCursor
    if other then
      MPC1ColumnType.ANY
    else if seen == 0 then
      MPC1ColumnType.NIL
    else if seen == TypeInt then
      MPC1ColumnType.LONG
    else if (seen & ~(TypeInt | TypeFloat)) == 0 then
      MPC1ColumnType.DOUBLE
    else if seen == TypeBool then
      MPC1ColumnType.BOOLEAN
    else if seen == TypeStr then
      MPC1ColumnType.STRING
    else if (seen & ~(TypeStr | TypeBin)) == 0 then
      MPC1ColumnType.BINARY
    else
      MPC1ColumnType.ANY

  end columnType

  /** Decodes integers as longs */
  def decodeLongs(name: String, block: Array[Byte], columnIndex: Int = 0): LongColumn =
    val cursor = newCursor(block)
    val nulls  = Nulls()
    var values = new Array[Long](capacity(block))
    var n      = 0
    while cursor.position < block.length do
      if n == values.length then
        values = java.util.Arrays.copyOf(values, n * 2)
      val b = cursor.peekByte
      if b == Code.NIL then
        cursor.skipBytes(1)
        nulls.set(n)
      else if MessageFormat.of(b).valueType == ValueType.INTEGER then
        values(n) = unpack(name, n)(OffsetUnpacker.unpackLong(cursor))
      else
        mismatch(name, n, "integer", b)
      cursor.proceedCursor
      n += 1
    LongColumn(field(name, PhysicalType.INT64, None, columnIndex), trim(values, n), nulls.result(n))

  end decodeLongs

  /** Decodes floats and integers as doubles */
  def decodeDoubles(name: String, block: Array[Byte], columnIndex: Int = 0): DoubleColumn =
    val cursor = newCursor(block)
    val nulls  = Nulls()
    var values = new Array[Double](capacity(block))
    var n      = 0
    while cursor.position < block.length do
      if n == values.length then
        values = java.util.Arrays.copyOf(values, n * 2)
      val b = cursor.peekByte
      if b == Code.NIL then
        cursor.skipBytes(1)
        nulls.set(n)
      else if b == Code.FLOAT64 || b == Code.FLOAT32 then
        values(n) = OffsetUnpacker.unpackDouble(cursor)
      else if MessageFormat.of(b).valueType == ValueType.INTEGER then
        values(n) = unpack(name, n)(OffsetUnpacker.unpackLong(cursor)).toDouble
      else
        mismatch(name, n, "float or integer", b)
      cursor.proceedCursor
      n += 1
    DoubleColumn(
      field(name, PhysicalType.DOUBLE, None, columnIndex),
      trim(values, n),
      nulls.result(n)
    )

  end decodeDoubles

  def decodeBooleans(name: String, block: Array[Byte], columnIndex: Int = 0): BooleanColumn =
    val cursor = newCursor(block)
    val nulls  = Nulls()
    // Booleans and nils take one byte each, so the block size bounds the row count
    val values = new Array[Boolean](block.length)
    var n      = 0
    while cursor.position < block.length do
      val b = cursor.readByte
      if b == Code.TRUE then
        values(n) = true
      else if b == Code.NIL then
        nulls.set(n)
      else if b != Code.FALSE then
        mismatch(name, n, "boolean", b)
      cursor.proceedCursor
      n += 1
    BooleanColumn(
      field(name, PhysicalType.BOOLEAN, None, columnIndex),
      trim(values, n),
      nulls.result(n)
    )

  end decodeBooleans

  /** Decodes strings into a column of UTF-8 bytes whose `getString` returns each row */
  def decodeStrings(name: String, block: Array[Byte], columnIndex: Int = 0): BinaryColumn =
    decodeRaw(name, block, acceptBinary = false, columnIndex)

  /** Decodes binaries, and strings as their UTF-8 bytes */
  def decodeBinaries(name: String, block: Array[Byte], columnIndex: Int = 0): BinaryColumn =
    decodeRaw(name, block, acceptBinary = true, columnIndex)

  private def decodeRaw(
      name: String,
      block: Array[Byte],
      acceptBinary: Boolean,
      columnIndex: Int
  ): BinaryColumn =
    val cursor  = newCursor(block)
    val nulls   = Nulls()
    var offsets = new Array[Int](capacity(block) + 1)
    // Payloads are part of the block, so the block size bounds the data size
    val data = new Array[Byte](block.length)
    var size = 0
    var n    = 0
    while cursor.position < block.length do
      if n + 1 == offsets.length then
        offsets = java.util.Arrays.copyOf(offsets, n * 2 + 1)
      val b = cursor.peekByte
      if b == Code.NIL then
        cursor.skipBytes(1)
        nulls.set(n)
      else
        val vt = MessageFormat.of(b).valueType
        if vt == ValueType.STRING || (acceptBinary && vt == ValueType.BINARY) then
          val len = OffsetUnpacker.unpackRawStringHeader(cursor)
          cursor.readBytes(len, data, size)
          size += len
        else if acceptBinary then
          mismatch(name, n, "binary or string", b)
        else
          mismatch(name, n, "string", b)
      cursor.proceedCursor
      n += 1
      offsets(n) = size

    val logicalType =
      if acceptBinary then
        None
      else
        Some(LogicalType.String)
    BinaryColumn(
      field(name, PhysicalType.BYTE_ARRAY, logicalType, columnIndex),
      trim(offsets, n + 1),
      trim(data, size),
      nulls.result(n),
      binaryAsString = !acceptBinary
    )

  end decodeRaw

  private def newCursor(block: Array[Byte]): ReadCursor = ReadCursor(
    ByteArrayBuffer.fromArray(block, 0, block.length),
    0
  )

  // Every value takes at least one byte, so the block size bounds the row count
  private def capacity(block: Array[Byte]): Int = math.min(block.length, InitialCapacity)

  private def trim(a: Array[Long], n: Int): Array[Long] =
    if a.length == n then
      a
    else
      java.util.Arrays.copyOf(a, n)

  private def trim(a: Array[Double], n: Int): Array[Double] =
    if a.length == n then
      a
    else
      java.util.Arrays.copyOf(a, n)

  private def trim(a: Array[Boolean], n: Int): Array[Boolean] =
    if a.length == n then
      a
    else
      java.util.Arrays.copyOf(a, n)

  private def trim(a: Array[Int], n: Int): Array[Int] =
    if a.length == n then
      a
    else
      java.util.Arrays.copyOf(a, n)

  private def trim(a: Array[Byte], n: Int): Array[Byte] =
    if a.length == n then
      a
    else
      java.util.Arrays.copyOf(a, n)

  private def field(
      name: String,
      physicalType: PhysicalType,
      logicalType: Option[LogicalType],
      columnIndex: Int
  ): ParquetField.Primitive = ParquetField.Primitive(
    name = name,
    repetition = Repetition.OPTIONAL,
    physicalType = physicalType,
    typeLength = 0,
    logicalType = logicalType,
    path = Seq(name),
    maxDefinitionLevel = 1,
    maxRepetitionLevel = 0,
    columnIndex = columnIndex
  )

  // Wraps an integer overflow (an unsigned 64-bit value over Long.MaxValue) with the row
  private inline def unpack[A](name: String, row: Int)(inline body: A): A =
    try
      body
    catch
      case e: MessageException =>
        throw MPC1Exception(s"Column ${name}, row ${row}: ${e.getMessage}", e)

  private def mismatch(name: String, row: Int, expected: String, b: Byte): Nothing =
    // The never-used format 0xc1 has no value type
    val found = Option(MessageFormat.of(b).valueType)
      .map(_.name.toLowerCase)
      .getOrElse("an invalid byte")
    val code = (b & 0xff).toHexString
    throw MPC1Exception(
      s"Column ${name}, row ${row}: expected ${expected} or nil, but found ${found} (0x${code})"
    )

  /** Null flags by row, allocated at the first nil */
  private class Nulls:
    private var flags: Array[Boolean] = null

    def set(row: Int): Unit =
      if flags == null then
        flags = new Array[Boolean](math.max(InitialCapacity, row + 1))
      else if row >= flags.length then
        flags = java.util.Arrays.copyOf(flags, math.max(flags.length * 2, row + 1))
      flags(row) = true

    def result(numRows: Int): Array[Boolean] =
      if flags == null then
        null
      else if flags.length == numRows then
        flags
      else
        java.util.Arrays.copyOf(flags, numRows)

end MPC1Decoder

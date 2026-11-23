package wvlet.uni.cloud.arrow.format

/**
  * A minimal FlatBuffers builder for the fixed set of Arrow metadata tables. Like the official
  * builders, it fills a buffer from the end toward the front, so children (strings, vectors, nested
  * tables) are written before the tables that point to them, and every unsigned offset points
  * forward.
  *
  * Offsets returned by this builder count bytes from the end of the buffer. Tables get their own
  * vtable each; Arrow messages are small, so vtables are not deduplicated.
  */
private[arrow] final class FlatBufferBuilder(initialCapacity: Int = 1024):
  private var bb: Array[Byte]    = new Array[Byte](math.max(64, initialCapacity))
  private var space: Int         = bb.length
  private var minAlign: Int      = 1
  private var vtable: Array[Int] = new Array[Int](16)
  private var vtableInUse: Int   = 0
  private var objectStart: Int   = 0
  private var inTable: Boolean   = false
  private var vectorCount: Int   = 0

  /** Bytes written so far, which is the offset of the most recent object from the buffer's end */
  def offset: Int = bb.length - space

  private def grow(): Unit =
    val old = bb.length
    if old > (Int.MaxValue >> 1) then
      throw IllegalStateException("a FlatBuffer grew beyond 1 GiB")
    val next = new Array[Byte](old << 1)
    System.arraycopy(bb, space, next, old + space, old - space)
    space += old
    bb = next

  /**
    * Pads so that, after `additional` bytes are written, the offset is a multiple of `size`
    */
  private def prep(size: Int, additional: Int): Unit =
    if size > minAlign then
      minAlign = size
    val pad = (-(offset + additional)) & (size - 1)
    while space < pad + size + additional do
      grow()
    var i = 0
    while i < pad do
      space -= 1
      bb(space) = 0
      i += 1

  private def putByte(v: Int): Unit =
    space -= 1
    bb(space) = v.toByte

  private def putShort(v: Int): Unit =
    space -= 2
    bb(space) = v.toByte
    bb(space + 1) = (v >>> 8).toByte

  private def putInt(v: Int): Unit =
    space -= 4
    bb(space) = v.toByte
    bb(space + 1) = (v >>> 8).toByte
    bb(space + 2) = (v >>> 16).toByte
    bb(space + 3) = (v >>> 24).toByte

  private def putLong(v: Long): Unit =
    space -= 8
    var i = 0
    while i < 8 do
      bb(space + i) = (v >>> (8 * i)).toByte
      i += 1

  def addByte(v: Int): Unit =
    prep(1, 0)
    putByte(v)

  def addShort(v: Int): Unit =
    prep(2, 0)
    putShort(v)

  def addInt(v: Int): Unit =
    prep(4, 0)
    putInt(v)

  def addLong(v: Long): Unit =
    prep(8, 0)
    putLong(v)

  /** Writes a uoffset pointing to an object written earlier */
  def addOffset(off: Int): Unit =
    prep(4, 0)
    putInt(offset - off + 4)

  def createString(s: String): Int = createBytes(
    s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  )

  private def createBytes(b: Array[Byte]): Int =
    prep(4, b.length + 1)
    putByte(0)
    space -= b.length
    System.arraycopy(b, 0, bb, space, b.length)
    putInt(b.length)
    offset

  /**
    * Starts a vector of `n` elements of `elemSize` bytes. Write the elements (or the fields of each
    * struct element) last to first, then call [[endVector]].
    */
  def startVector(elemSize: Int, n: Int, alignment: Int): Unit =
    vectorCount = n
    prep(4, elemSize * n)
    prep(alignment, elemSize * n)

  def endVector(): Int =
    putInt(vectorCount)
    offset

  /** A vector of offsets to tables or strings written earlier */
  def createOffsetVector(offsets: Seq[Int]): Int =
    startVector(4, offsets.size, 4)
    var i = offsets.size - 1
    while i >= 0 do
      addOffset(offsets(i))
      i -= 1
    endVector()

  def startTable(numFields: Int): Unit =
    if inTable then
      throw IllegalStateException("FlatBuffer tables cannot nest while being built")
    if vtable.length < numFields then
      vtable = new Array[Int](numFields)
    java.util.Arrays.fill(vtable, 0, numFields, 0)
    vtableInUse = numFields
    objectStart = offset
    inTable = true

  private def slot(i: Int): Unit = vtable(i) = offset

  // Fields are always written, even when they equal the schema default, which FlatBuffers allows

  def addBoolField(i: Int, v: Boolean): Unit =
    addByte(
      if v then
        1
      else
        0
    )
    slot(i)

  def addByteField(i: Int, v: Int): Unit =
    addByte(v)
    slot(i)

  def addShortField(i: Int, v: Int): Unit =
    addShort(v)
    slot(i)

  def addIntField(i: Int, v: Int): Unit =
    addInt(v)
    slot(i)

  def addLongField(i: Int, v: Long): Unit =
    addLong(v)
    slot(i)

  def addOffsetField(i: Int, off: Int): Unit =
    addOffset(off)
    slot(i)

  def endTable(): Int =
    if !inTable then
      throw IllegalStateException("endTable without startTable")
    addInt(0)
    val objectOffset = offset
    var n            = vtableInUse
    while n > 0 && vtable(n - 1) == 0 do
      n -= 1
    var i = n - 1
    while i >= 0 do
      addShort(
        if vtable(i) != 0 then
          objectOffset - vtable(i)
        else
          0
      )
      i -= 1
    addShort(objectOffset - objectStart)
    addShort((n + 2) * 2)
    val vtableOffset = offset
    // soffset from the table to its vtable, which sits just before it
    val tablePos = bb.length - objectOffset
    val v        = vtableOffset - objectOffset
    bb(tablePos) = v.toByte
    bb(tablePos + 1) = (v >>> 8).toByte
    bb(tablePos + 2) = (v >>> 16).toByte
    bb(tablePos + 3) = (v >>> 24).toByte
    inTable = false
    objectOffset

  /** Finishes the buffer with `root` as its root table and returns the bytes */
  def finish(root: Int): Array[Byte] =
    prep(minAlign, 4)
    addOffset(root)
    java.util.Arrays.copyOfRange(bb, space, bb.length)

end FlatBufferBuilder

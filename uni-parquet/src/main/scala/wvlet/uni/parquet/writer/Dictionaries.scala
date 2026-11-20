package wvlet.uni.parquet.writer

import wvlet.uni.parquet.io.ByteBuf
import wvlet.uni.parquet.io.IntBuf

/**
  * The dictionary of a numeric column: an open-addressing table from a value's bits (an INT32 or
  * INT64 value, or the raw bits of a FLOAT or DOUBLE) to its dictionary index, so values are never
  * boxed.
  */
private[writer] final class LongDictionary:
  private var keys  = new Array[Long](64)
  private var slots = new Array[Int](64) // index + 1, or 0 when empty
  private var count = 0

  /** True when the last [[indexOf]] added the key */
  var added: Boolean = false

  def size: Int = count

  private def slotOf(k: Long, mask: Int): Int =
    val h = k * 0x9e3779b97f4a7c15L
    (h ^ (h >>> 32)).toInt & mask

  /** The index of `k`, added at the end if it is new */
  def indexOf(k: Long): Int =
    val mask = slots.length - 1
    var s    = slotOf(k, mask)
    while slots(s) != 0 && keys(s) != k do
      s = (s + 1) & mask
    if slots(s) != 0 then
      added = false
      slots(s) - 1
    else
      added = true
      keys(s) = k
      count += 1
      slots(s) = count
      if count * 2 > slots.length then
        grow()
      count - 1

  private def grow(): Unit =
    val oldKeys  = keys
    val oldSlots = slots
    keys = new Array[Long](oldKeys.length * 2)
    slots = new Array[Int](oldSlots.length * 2)
    val mask = slots.length - 1
    var i    = 0
    while i < oldSlots.length do
      if oldSlots(i) != 0 then
        var s = slotOf(oldKeys(i), mask)
        while slots(s) != 0 do
          s = (s + 1) & mask
        keys(s) = oldKeys(i)
        slots(s) = oldSlots(i)
      i += 1

  def clear(): Unit =
    if slots.length > 64 then
      keys = new Array[Long](64)
      slots = new Array[Int](64)
    else
      java.util.Arrays.fill(slots, 0)
    count = 0

end LongDictionary

/**
  * The dictionary of a binary column: an open-addressing table over byte ranges. Values are stored
  * once, in the PLAIN-encoded dictionary page that the column writer builds (`page`); entries point
  * at their bytes there.
  */
private[writer] final class BytesDictionary(page: ByteBuf, lengthPrefix: Boolean):
  private var slots   = new Array[Int](64) // index + 1, or 0 when empty
  private val offsets = IntBuf()
  private val lengths = IntBuf()
  private val hashes  = IntBuf()
  private var count   = 0
  var added: Boolean  = false
  def size: Int       = count

  private def hash(src: Array[Byte], off: Int, len: Int): Int =
    var h = len
    var i = 0
    while i < len do
      h = 31 * h + src(off + i)
      i += 1
    h ^ (h >>> 16)

  private def same(e: Int, src: Array[Byte], off: Int, len: Int): Boolean =
    lengths(e) == len && {
      val d = page.array
      val o = offsets(e)
      var i = 0
      while i < len && d(o + i) == src(off + i) do
        i += 1
      i == len
    }

  /** The index of the value, added to the page if it is new */
  def indexOf(src: Array[Byte], off: Int, len: Int): Int =
    val h    = hash(src, off, len)
    val mask = slots.length - 1
    var s    = h & mask
    while slots(s) != 0 && !(hashes(slots(s) - 1) == h && same(slots(s) - 1, src, off, len)) do
      s = (s + 1) & mask
    if slots(s) != 0 then
      added = false
      slots(s) - 1
    else
      added = true
      if lengthPrefix then
        page.writeIntLE(len)
      offsets.add(page.size)
      lengths.add(len)
      hashes.add(h)
      page.write(src, off, len)
      count += 1
      slots(s) = count
      if count * 2 > slots.length then
        grow()
      count - 1

  private def grow(): Unit =
    slots = new Array[Int](slots.length * 2)
    val mask = slots.length - 1
    var e    = 0
    while e < count do
      var s = hashes(e) & mask
      while slots(s) != 0 do
        s = (s + 1) & mask
      slots(s) = e + 1
      e += 1

  def clear(): Unit =
    if slots.length > 64 then
      slots = new Array[Int](64)
    else
      java.util.Arrays.fill(slots, 0)
    offsets.reset()
    lengths.reset()
    hashes.reset()
    count = 0

end BytesDictionary

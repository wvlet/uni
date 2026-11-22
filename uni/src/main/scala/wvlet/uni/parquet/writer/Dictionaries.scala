package wvlet.uni.parquet.writer

import wvlet.uni.io.codec.ByteBuf
import wvlet.uni.parquet.io.IntBuf

/**
  * The dictionary of a numeric column: an open-addressing table from a value's bits (an INT32 or
  * INT64 value, or the raw bits of a FLOAT or DOUBLE) to its dictionary index, so values are never
  * boxed.
  */
private[writer] final class LongDictionary:
  // Slot s holds its key at 2s and its index + 1 at 2s + 1 (0 when empty), so a probe reads one
  // cache line rather than one in each of two arrays
  private var table = new Array[Long](2 * 64)
  private var mask  = 63
  private var count = 0

  /** True when the last [[indexOf]] added the key */
  var added: Boolean = false

  def size: Int = count

  // Folds the key to 32 bits, then mixes with MurmurHash3's finalizer, so keys that differ only
  // in their high bits (the bits of a round DOUBLE) spread too. 32-bit multiplies: Scala.js
  // emulates a 64-bit one.
  private inline def slotOf(k: Long, mask: Int): Int =
    var h = k.toInt ^ ((k >>> 32).toInt * 0x9e3779b9)
    h ^= h >>> 16
    h *= 0x85ebca6b
    h ^= h >>> 13
    h *= 0xc2b2ae35
    h ^= h >>> 16
    h & mask

  /** The index of `k`, added at the end if it is new */
  def indexOf(k: Long): Int =
    val t = table
    var s = slotOf(k, mask)
    while t(2 * s + 1) != 0 && t(2 * s) != k do
      s = (s + 1) & mask
    if t(2 * s + 1) != 0 then
      added = false
      t(2 * s + 1).toInt - 1
    else
      added = true
      t(2 * s) = k
      count += 1
      t(2 * s + 1) = count
      if count * 2 > mask + 1 then
        grow()
      count - 1

  private def grow(): Unit =
    val old = table
    table = new Array[Long](old.length * 2)
    mask = mask * 2 + 1
    var i = 0
    while i < old.length do
      if old(i + 1) != 0 then
        var s = slotOf(old(i), mask)
        while table(2 * s + 1) != 0 do
          s = (s + 1) & mask
        table(2 * s) = old(i)
        table(2 * s + 1) = old(i + 1)
      i += 2

  def clear(): Unit =
    if mask > 63 then
      table = new Array[Long](2 * 64)
      mask = 63
    else
      java.util.Arrays.fill(table, 0L)
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

  /** Where entry e's bytes start in the page, after any length prefix */
  def offsetOf(e: Int): Int = offsets(e)
  def lengthOf(e: Int): Int = lengths(e)

  /** True when entry e holds the bytes `src[off, off + len)` */
  def same(e: Int, src: Array[Byte], off: Int, len: Int): Boolean =
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

package wvlet.uni.parquet.codec

import scala.scalanative.libc.string.memcpy
import scala.scalanative.runtime.Intrinsics.castLongToRawPtr
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** 8-byte moves between byte arrays at any offset, through pointers into the array data */
private[parquet] object Word8:

  /**
    * Copies 8 bytes from `src(sp)` to `dst(dp)`. Both ranges must be inside their arrays: nothing
    * checks bounds here. A fixed 8-byte `memcpy` compiles to one unaligned load and store, where a
    * `Ptr[Long]` load would assume 8-byte alignment.
    */
  inline def copy(src: Array[Byte], sp: Int, dst: Array[Byte], dp: Int): Unit = memcpy(
    dst.atUnsafe(dp),
    src.atUnsafe(sp),
    8.toCSize
  )

  /**
    * The little-endian long at `b(i)`, where `b(i)` to `b(i + 7)` must be inside the array.
    *
    * Scala Native emits a `Ptr[Long]` load without an alignment, so LLVM assumes 8 bytes, and a
    * `memcpy` into a `stackalloc` slot grows the stack in every loop iteration. So this reads the
    * two aligned words that hold the 8 bytes and shifts them together (one word when `b(i)` is
    * aligned). Neither aligned read crosses a page or leaves the array's heap object, since each
    * holds a byte of the array; with the array header, the low one may start before the data. Raw
    * pointers from `runtime.Intrinsics` keep this at 76 ms on `orders`; the same reads through
    * public `Ptr` arithmetic measured 88 ms.
    */
  inline def le64(b: Array[Byte], i: Int): Long =
    val a     = b.atUnsafe(i).toLong
    val lo    = !fromRawPtr[Long](castLongToRawPtr(a & ~7L))
    val hi    = !fromRawPtr[Long](castLongToRawPtr((a + 7) & ~7L))
    val shift = ((a & 7) << 3).toInt
    // hi << 1 << (63 - shift) is hi << (64 - shift), but also 0 when shift is 0
    (lo >>> shift) | ((hi << 1) << (63 - shift))

  /**
    * The little-endian int at `b(i)`, where `b(i)` to `b(i + 3)` must be inside the array: read as
    * in [[le64]], from the aligned words that hold `b(i)` and `b(i + 3)`, so a read at the array's
    * last 4 bytes stays inside its heap object
    */
  inline def le32(b: Array[Byte], i: Int): Int =
    val a     = b.atUnsafe(i).toLong
    val lo    = !fromRawPtr[Long](castLongToRawPtr(a & ~7L))
    val hi    = !fromRawPtr[Long](castLongToRawPtr((a + 3) & ~7L))
    val shift = ((a & 7) << 3).toInt
    ((lo >>> shift) | ((hi << 1) << (63 - shift))).toInt

  /**
    * The length of the match at `src(a)` and `src(b)` (`a` below `b`), whose first 4 bytes are
    * equal, counting up to `limit` (exclusive) for `b`, in the low 32 bits; the int at the end of
    * the match on the `b` side in the high 32 bits, unless fewer than 8 bytes remain there before
    * `limit`. Needs `b + 16 <= limit`.
    *
    * A match of 4 to 11 bytes ends inside the 8 bytes at `b + 4`, and the word at its end comes
    * from the loads at `b + 4` and `b + 8` alone: a compressor's next hash lookup then doesn't wait
    * for a load that depends on the match length, as in the reference Snappy's FindMatchLength.
    */
  def matchAndNextWord(src: Array[Byte], a: Int, b: Int, limit: Int): Long =
    val w1 = le64(src, b + 4)
    val w2 = le64(src, b + 8)
    val x  = le64(src, a + 4) ^ w1
    if x != 0 then
      val shift = java.lang.Long.numberOfTrailingZeros(x)
      // The end is byte shift >>> 3 of w1; from byte 4 on, w2 holds it 4 bytes lower
      val next =
        (
          if x.toInt == 0 then
            w2
          else
            w1
        ) >>> (shift & 24)
      (4 + (shift >>> 3)) | (next << 32)
    else
      val n    = 12 + matchLength(src, a + 12, b + 12, limit)
      val next =
        if b + n + 8 <= limit then
          le32(src, b + n)
        else
          0
      n | (next.toLong << 32)

  /**
    * The number of equal bytes at `src(a)` and `src(b)`, counting up to `limit` (exclusive) for
    * `b`, where `a` is below `b`. Compares 8 bytes at a time: the first differing byte is the
    * lowest set byte of the XOR.
    */
  def matchLength(src: Array[Byte], a: Int, b: Int, limit: Int): Int =
    var n = 0
    while b + n <= limit - 8 do
      val x = le64(src, a + n) ^ le64(src, b + n)
      if x != 0 then
        return n + (java.lang.Long.numberOfTrailingZeros(x) >>> 3)
      n += 8
    while b + n < limit && src(a + n) == src(b + n) do
      n += 1
    n

end Word8

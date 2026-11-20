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

end Word8

package wvlet.uni.parquet.codec

import scala.scalanative.libc.string.memcpy
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

  /** The little-endian long at `b(i)` */
  inline def le64(b: Array[Byte], i: Int): Long =
    (Zstd.le32(b, i) & 0xffffffffL) | (Zstd.le32(b, i + 4).toLong << 32)

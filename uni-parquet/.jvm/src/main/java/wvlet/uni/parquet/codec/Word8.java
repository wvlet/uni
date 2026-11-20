package wvlet.uni.parquet.codec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * 8-byte loads and moves on byte arrays at any offset, through a byte-array view VarHandle.
 * Written in Java so the handle is a static final constant the JIT folds, and the calls get exact
 * signatures: Scala compiles VarHandle.get with an Object return, which boxes every load.
 */
final class Word8 {
  private static final VarHandle LONGS =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  private Word8() {}

  /** Copies 8 bytes from src[sp] to dst[dp]. Both ranges must be inside their arrays. */
  static void copy(byte[] src, int sp, byte[] dst, int dp) {
    LONGS.set(dst, dp, (long) LONGS.get(src, sp));
  }

  /** The little-endian long at b[i] */
  static long le64(byte[] b, int i) {
    return (long) LONGS.get(b, i);
  }
}

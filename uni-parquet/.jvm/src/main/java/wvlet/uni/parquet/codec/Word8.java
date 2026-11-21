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
  private static final VarHandle INTS =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

  private Word8() {}

  /** Copies 8 bytes from src[sp] to dst[dp]. Both ranges must be inside their arrays. */
  static void copy(byte[] src, int sp, byte[] dst, int dp) {
    LONGS.set(dst, dp, (long) LONGS.get(src, sp));
  }

  /** The little-endian long at b[i] */
  static long le64(byte[] b, int i) {
    return (long) LONGS.get(b, i);
  }

  /** The little-endian int at b[i], where b[i] to b[i + 3] must be inside the array */
  static int le32(byte[] b, int i) {
    return (int) INTS.get(b, i);
  }

  /**
   * The number of equal bytes at src[a] and src[b], counting up to limit (exclusive) for b, where
   * a is below b. Compares 8 bytes at a time: the first differing byte is the lowest set byte of
   * the XOR.
   */
  static int matchLength(byte[] src, int a, int b, int limit) {
    int n = 0;
    while (b + n <= limit - 8) {
      long x = (long) LONGS.get(src, a + n) ^ (long) LONGS.get(src, b + n);
      if (x != 0) {
        return n + (Long.numberOfTrailingZeros(x) >>> 3);
      }
      n += 8;
    }
    while (b + n < limit && src[a + n] == src[b + n]) {
      n++;
    }
    return n;
  }
}

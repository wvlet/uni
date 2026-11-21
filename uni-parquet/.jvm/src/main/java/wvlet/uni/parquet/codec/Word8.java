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
   * The length of the match at src[a] and src[b] (a below b), whose first 4 bytes are equal,
   * counting up to limit (exclusive) for b, in the low 32 bits; the int at the end of the match
   * on the b side in the high 32 bits, unless fewer than 8 bytes remain there before limit. Needs
   * b + 16 <= limit.
   *
   * <p>A match of 4 to 11 bytes ends inside the 8 bytes at b + 4, and the word at its end comes
   * from the loads at b + 4 and b + 8 alone: a compressor's next hash lookup then doesn't wait
   * for a load that depends on the match length, as in the reference Snappy's FindMatchLength.
   */
  static long matchAndNextWord(byte[] src, int a, int b, int limit) {
    long w1 = (long) LONGS.get(src, b + 4);
    long w2 = (long) LONGS.get(src, b + 8);
    long x = (long) LONGS.get(src, a + 4) ^ w1;
    if (x != 0) {
      int shift = Long.numberOfTrailingZeros(x);
      // shift & 24 is the bit offset of the end inside w1, or inside w2 for 4 or more bytes
      long next = ((int) x == 0 ? w2 : w1) >>> (shift & 24);
      return (4 + (shift >>> 3)) | (next << 32);
    }
    return longMatchAndNextWord(src, a, b, limit);
  }

  /** matchAndNextWord for a match of 12 or more bytes; a separate method keeps the hot one small */
  private static long longMatchAndNextWord(byte[] src, int a, int b, int limit) {
    int n = 12 + matchLength(src, a + 12, b + 12, limit);
    int next = b + n + 8 <= limit ? (int) INTS.get(src, b + n) : 0;
    return n | ((long) next << 32);
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

package wvlet.uni.io.codec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bulk reads of little-endian values from byte arrays, for decoding PLAIN Parquet pages. The
 * same-width reads go through a little-endian ByteBuffer view, whose bulk get copies memory when
 * the platform is little-endian; widening ints to longs goes through a byte-array view VarHandle.
 * Written in Java for the same reason as {@link Word8}: Scala boxes VarHandle.get. Callers check
 * the ranges.
 */
final class LittleEndian {
  private static final VarHandle INTS =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

  private LittleEndian() {}

  private static ByteBuffer view(byte[] src, int off, int len) {
    return ByteBuffer.wrap(src, off, len).order(ByteOrder.LITTLE_ENDIAN);
  }

  static void ints(byte[] src, int off, int[] dst, int dstOff, int n) {
    view(src, off, n * 4).asIntBuffer().get(dst, dstOff, n);
  }

  static void intsAsLongs(byte[] src, int off, long[] dst, int dstOff, int n) {
    for (int i = 0; i < n; i++) {
      dst[dstOff + i] = (int) INTS.get(src, off + i * 4);
    }
  }

  static void longs(byte[] src, int off, long[] dst, int dstOff, int n) {
    view(src, off, n * 8).asLongBuffer().get(dst, dstOff, n);
  }

  static void floats(byte[] src, int off, float[] dst, int dstOff, int n) {
    view(src, off, n * 4).asFloatBuffer().get(dst, dstOff, n);
  }

  static void doubles(byte[] src, int off, double[] dst, int dstOff, int n) {
    view(src, off, n * 8).asDoubleBuffer().get(dst, dstOff, n);
  }
}

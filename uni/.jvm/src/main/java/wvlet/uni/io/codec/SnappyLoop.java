package wvlet.uni.io.codec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * The JVM's fast loop for {@link Snappy}: it decodes the common tags of a stream and returns at
 * the first tag it leaves to the caller's checked decoder, or near the end of the input or the
 * output.
 *
 * <p>The next 16 input bytes sit in two registers, {@code lo} and {@code hi}. A tag's fields come
 * from {@code lo}, and both registers then shift by the tag's length, so finding the next tag
 * waits on shifts instead of a memory load at an address that depends on this tag. Literals of 1
 * to 6 bytes and copies of up to 16 bytes with an offset of 8 or more share one body with no
 * branch on the tag kind, which C2 would otherwise mispredict on most tags of columnar data:
 *
 * <ul>
 *   <li>A 16-byte move from {@code op - offset} to {@code op}. For a literal the table gives an
 *       offset of 8, so the move writes bytes that the next step and later tags overwrite.
 *   <li>An 8-byte store of the literal's bytes, which are bytes 1 to 7 of {@code lo}. For a copy
 *       the store goes 16 bytes ahead, where later tags overwrite it.
 * </ul>
 *
 * <p>Both steps write past the tag's own bytes, up to 24 bytes from {@code op}, so the loop runs
 * only while that stays below the output limit. Written in Java for the same reason as {@link
 * Word8}: Scala boxes the result of VarHandle.get.
 */
final class SnappyLoop {
  /** Tells the shared decoder that this platform has the loop */
  static final boolean enabled = true;

  private static final VarHandle LONGS =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  /** Input bytes the loop may read from a tag's first byte: two 8-byte loads, the second 15 on */
  private static final int INPUT_MARGIN = 24;

  /** Output bytes the loop may write from a tag's first output byte */
  private static final int OUTPUT_MARGIN = 24;

  /**
   * One entry per tag byte, 0 for the tags the loop leaves to {@link #rareTag}:
   *
   * <ul>
   *   <li>bits 16-31: the mask for the two bytes after the tag, which hold a copy's offset
   *   <li>bits 8-10: the high offset bits of a copy with a 1-byte offset
   *   <li>bit 3: set for a literal, which makes its offset 8
   *   <li>bit 4: set for a copy, the distance ahead of {@code op} of the literal store
   *   <li>bits 11-15: the number of output bytes
   * </ul>
   *
   * An entry of 0 gives an offset of 0, which fails the offset check, so the loop needs no
   * separate test for these tags.
   */
  private static final int[] TAGS = new int[256];

  static {
    for (int tag = 0; tag < 256; tag++) {
      int x = tag >>> 2;
      int e = 0;
      switch (tag & 3) {
        case 0:
          // A literal of up to 6 bytes: with its tag it leaves a byte of lo for the next tag
          if (x + 1 <= 6) {
            e = 8 | ((x + 1) << 11);
          }
          break;
        case 1:
          e = (0xff << 16) | ((tag >>> 5) << 8) | 16 | ((4 + (x & 7)) << 11);
          break;
        case 2:
          if (x + 1 <= 16) {
            e = (0xffff << 16) | 16 | ((x + 1) << 11);
          }
          break;
        default:
          break;
      }
      TAGS[tag] = e;
    }
  }

  private SnappyLoop() {}

  /** The input and output positions as one value: ip in the high 32 bits, op in the low 32 */
  private static long state(int ip, int op) {
    return ((long) ip << 32) | op;
  }

  /**
   * Decodes tags from src[ip] on, writing from out[op] on and never at or past out[limit]. Returns
   * the new ip in the high 32 bits and the new op in the low 32. It stops, having consumed whole
   * tags only, at a tag it doesn't decode (a corrupt one included), within 24 bytes of {@code
   * end}, or within 24 bytes of {@code limit}; the caller decodes one tag and may call again.
   */
  static long run(byte[] src, int ip, int end, byte[] out, int op, int limit) {
    final int ipLimit = end - INPUT_MARGIN;
    final int opLimit = limit - OUTPUT_MARGIN;
    if (ip > ipLimit) {
      return state(ip, op);
    }
    long lo = (long) LONGS.get(src, ip);
    long hi = (long) LONGS.get(src, ip + 8);
    // The bytes of lo before hi's are merged in: enough for the tag byte, and ready a step earlier
    long t = lo;
    while (ip <= ipLimit && op <= opLimit) {
      int tag = (int) t;
      int e = TAGS[tag & 0xff];
      int off = ((int) (lo >>> 8) & (e >>> 16)) | (e & 0x708);
      // One test for an offset below 8, an offset before the output's start, and an entry of 0
      if (((off - 8) | (op - off)) < 0) {
        long st = rareTag(src, ip, end, out, op, limit);
        if (st < 0) {
          break;
        }
        ip = (int) (st >>> 32);
        op = (int) st;
        if (ip > ipLimit) {
          break;
        }
        lo = (long) LONGS.get(src, ip);
        hi = (long) LONGS.get(src, ip + 8);
        t = lo;
        continue;
      }
      copy16(out, op - off, out, op);
      LONGS.set(out, op + (e & 16), lo >>> 8);
      op += (e >>> 11) & 31;
      // The tag's length in bits, less the tag byte: 8 per literal byte, or 8 per offset byte.
      // A long shift, since a 2-byte offset shifts the literal term by 32
      int kind = tag & 3;
      int literalBits = (int) ((((t & 0xfc) << 1) + 8) >>> (kind << 4));
      int bits = literalBits + (kind << 3);
      t = (lo >>> 8) >>> bits;
      lo = t | (hi << (56 - bits));
      ip += (bits >>> 3) + 1;
      hi = (long) LONGS.get(src, ip + 8);
    }
    return state(ip, op);
  }

  /**
   * Decodes the tag at src[ip], one the loop's body doesn't take, when it is valid and its bytes
   * fit: a copy with a 1- or 2-byte offset, or a literal of up to 60 bytes. Returns the new
   * positions, or -1 for any other tag, which the caller's checked decoder then decodes or
   * rejects. The caller ensures ip <= end - 24 and op <= limit - 24.
   */
  private static long rareTag(byte[] src, int ip, int end, byte[] out, int op, int limit) {
    int tag = src[ip] & 0xff;
    int kind = tag & 3;
    int x = tag >>> 2;
    if (kind == 0) {
      int len = x + 1;
      if (len <= 16) {
        copy16(src, ip + 1, out, op);
      } else if (len <= 60 && len <= end - ip - 1 && len <= limit - op) {
        System.arraycopy(src, ip + 1, out, op, len);
      } else {
        return -1;
      }
      return state(ip + 1 + len, op + len);
    }
    int len;
    int off;
    if (kind == 1) {
      len = 4 + (x & 7);
      off = ((tag >>> 5) << 8) | (src[ip + 1] & 0xff);
    } else if (kind == 2) {
      len = x + 1;
      off = (src[ip + 1] & 0xff) | ((src[ip + 2] & 0xff) << 8);
    } else {
      return -1;
    }
    if (off <= 0 || off > op || len > limit - op) {
      return -1;
    }
    int from = op - off;
    if (off >= 8 && len <= limit - op - 8) {
      // 8 bytes at a time: each move reads bytes that are final or that an earlier move wrote
      for (int i = 0; i < len; i += 8) {
        LONGS.set(out, op + i, (long) LONGS.get(out, from + i));
      }
    } else {
      // The ranges overlap closely, which repeats a pattern
      for (int i = 0; i < len; i++) {
        out[op + i] = out[from + i];
      }
    }
    return state(ip + kind + 1, op + len);
  }

  /** Copies 16 bytes as two 8-byte moves, the low one first */
  private static void copy16(byte[] src, int sp, byte[] dst, int dp) {
    LONGS.set(dst, dp, (long) LONGS.get(src, sp));
    LONGS.set(dst, dp + 8, (long) LONGS.get(src, sp + 8));
  }
}

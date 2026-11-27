package wvlet.uni.io.codec;

import java.io.IOException;

/**
 * Decodes Snappy with snappy-java's native library when the application has snappy-java on its
 * classpath. Uni declares snappy-java as a provided dependency, so it is never pulled in for
 * users: those who add it get the native decoder, and everyone else gets {@link Snappy}'s own.
 *
 * <p>The JVM links snappy-java's classes only when {@link #decompressInto} first runs, which
 * happens only when {@link #available} is true, so this class loads fine without snappy-java.
 * Setting the system property {@code wvlet.uni.snappy.useSnappyJava} to {@code false} turns it
 * off, for comparing the two decoders.
 */
final class SnappyJava {
  /** Whether snappy-java is on the classpath, its native library loaded, and it isn't turned off */
  static final boolean available = load();

  private SnappyJava() {}

  private static boolean load() {
    if ("false".equalsIgnoreCase(System.getProperty("wvlet.uni.snappy.useSnappyJava"))) {
      return false;
    }
    try {
      // Initializing the class loads the native library; the call checks that it works
      return org.xerial.snappy.Snappy.maxCompressedLength(1) > 0;
    } catch (Throwable e) {
      // NoClassDefFoundError without snappy-java, SnappyError or UnsatisfiedLinkError when no
      // native library fits this platform
      return false;
    }
  }

  /**
   * Decodes src[offset, offset + length) into out from index 0 and returns the number of bytes
   * written. snappy-java checks that the stream's stated length fits in out; the caller checks the
   * input range and that the stated length is the one it expects.
   */
  static int decompressInto(byte[] src, int offset, int length, byte[] out) throws IOException {
    return org.xerial.snappy.Snappy.uncompress(src, offset, length, out, 0);
  }
}

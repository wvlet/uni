/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.canvas;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Off-heap memory from libc {@code malloc}, which, unlike {@code Arena.allocate}, does not
 * zero-fill. Each segment is attached to an arena that calls {@code free} when it closes, so the
 * arena's bounds and liveness checks still guard every access.
 *
 * <p>Linker and {@code reinterpret} are restricted methods. Without native access for this module
 * (for example {@code --enable-native-access=ALL-UNNAMED}) the JVM would print a warning on first
 * use, or refuse the call, so {@link #available} is false then and callers use {@code
 * Arena.allocate} instead.
 *
 * <p>Written in Java so the downcalls use {@code invokeExact} with exact types and no boxing.
 * Load this class only on Java 22 or later.
 */
final class NativeMalloc {
  private NativeMalloc() {}

  private static final MethodHandle MALLOC;
  private static final MethodHandle FREE;

  /** Whether malloc is usable: native access is enabled and libc exports malloc and free */
  static final boolean available;

  static {
    MethodHandle malloc = null;
    MethodHandle free = null;
    if (!"false".equalsIgnoreCase(System.getProperty("wvlet.uni.canvas.useMalloc"))
        && NativeMalloc.class.getModule().isNativeAccessEnabled()) {
      try {
        Linker linker = Linker.nativeLinker();
        Optional<MemorySegment> m = linker.defaultLookup().find("malloc");
        Optional<MemorySegment> f = linker.defaultLookup().find("free");
        if (m.isPresent() && f.isPresent()) {
          malloc =
              linker.downcallHandle(
                  m.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
          free = linker.downcallHandle(f.get(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        }
      } catch (RuntimeException e) {
        // IllegalCallerException when native access is denied, or a platform without libc
        malloc = null;
        free = null;
      }
    }
    MALLOC = malloc;
    FREE = free;
    available = malloc != null;
  }

  /**
   * Allocate {@code size} bytes owned by {@code arena}. Closing the arena frees them. The contents
   * are whatever the memory held before.
   */
  static MemorySegment allocate(long size, Arena arena) {
    MemorySegment p;
    try {
      // malloc(0) may return NULL, so allocate at least one byte, as on Scala Native
      p = (MemorySegment) MALLOC.invokeExact(Math.max(size, 1L));
    } catch (Throwable e) {
      throw new IllegalStateException("malloc failed", e);
    }
    if (p.address() == 0L) {
      throw new OutOfMemoryError("Failed to allocate " + size + " bytes of off-heap memory");
    }
    return p.reinterpret(size, arena, NativeMalloc::free);
  }

  private static void free(MemorySegment segment) {
    try {
      FREE.invokeExact(segment);
    } catch (Throwable e) {
      throw new IllegalStateException("free failed", e);
    }
  }
}

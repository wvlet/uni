# Socket server: pooled send buffers and compressors

## Goals

- Stop allocating a new response array and a new zlib compressor per response in the shared socket server (Native and the JVM built-in server).
- On Native, every array above ~400 bytes takes Boehm's global lock. On both platforms, creating a `Deflater` sets up a fresh zlib state (about 256 KB of native memory) per compressed response.

## Background

- Follows #981–#984. After them, a 3.4 KB RPC response on Native still allocated a head+body array, a `ByteArrayOutputStream` with growth, its final copy, and a `Deflater` with its stream buffers.
- The user asked whether off-heap memory (`wvlet.uni.canvas`) would help. What matters is reuse, not where the memory lives: a reused heap array avoids Boehm's lock as well, and Scala Native allocates byte arrays atomic (never scanned). Off-heap `Canvas` also needs Java 22+ on the JVM, which this shared server runs on.

## Design

Lane: Express. It's an internal performance change, and the bytes on the wire stay the same (tested).

- A small shared `ScratchPool` (capped at the core count) instead of `ThreadLocal`: the JVM server runs each connection on its own virtual thread, so per-thread compressors and buffers would grow with the number of connections.
- `HttpResponseWriter.write` assembles the head and body in a pooled buffer and sends it. Bodies over 64 KB are written after their head, from their own array.
- Pooled gzip and deflate compressors call `Deflater.reset()`. gzip framing (header and CRC-32 trailer) is written by hand, as `GZIPOutputStream` does. The OS byte is 255, as on the JDK.
- Tests: the written bytes equal `serialize` (small, 200 KB, HEAD, 204, keep-alive or not). gzip and deflate round-trip across pool reuse and buffer growth, and gzip matches `GZIPOutputStream` apart from the OS byte.

## Alternatives and Why Not?

- **`ThreadLocal` buffers.** They would grow with connections on the JVM (virtual threads).
- **Off-heap `Canvas` buffers.** Same effect as a reused heap array here, but they need Java 22+ on the JVM.

# Decode gzip and deflate request bodies correctly on Windows Native

## Goals

- A `Content-Encoding: gzip` or `deflate` request body sent to a Scala Native socket server on Windows decodes to the original bytes, at any body size.
- A Native test that runs on the `Scala Native (Windows)` CI job proves it, through `RequestDecompression` and through the server.

## Background

- Root cause, found in the Scala Native 0.5.12 javalib source (`scala/scalanative/ffi/zlib.scala`): on Windows, the `z_stream` layout declares `avail_in` and `avail_out` as 16-bit (`CUnsignedShort`), assuming 16-bit `uInt`. zlib on 64-bit Windows uses a 32-bit `uInt`. The field offsets still line up because of alignment, but `setInput`, `inflate`, and `deflate` write only the low 16 bits.
  - Any single call with more than 65,535 bytes is truncated mod 65,536.
  - `Deflater.setInput(body)` with a 300 KB body compresses only a prefix, so the stream decodes to the wrong bytes and the trailer's CRC doesn't match. That is the "Crc mismatch" seen in #985/#989.
  - `DeflaterOutputStream.write(b)` with more than 64 KiB loops forever, because `needsInput` never turns true (javalib `DeflaterOutputStream.deflate()`).
  - Hypothesis, not yet confirmed by a log: #990's `gzipStream` helper writes a 300 KB body in one call, which would hang. Its first Windows run had been in progress for more than 80 minutes when I checked. The rerun was also still going. The job normally takes about 15 minutes and has no timeout.
  - Main's own pooled response path (`ResponseCompression.compress`, `d.setInput(body)`) has the same bug for bodies over 64 KiB. That's response compression, which #990 covers. The new server test sends no `Accept-Encoding`, so it doesn't touch that path.
- The CRC32 binding-width issue (`USize` vs 32-bit `uLong`) is unlikely to matter on x64: the values travel in registers, and a 32-bit return zero-extends in practice.
- `RequestDecompression` already feeds zlib at most 8 KiB per call (`GZIPInputStream(in, 8192)`, 8 KiB read chunks), so it should already decode correctly on Windows. Nothing guards or tests that, though: no Native test sends a compressed request body. The server-level decode test lives in `uni/.jvm` only.
- javalib's `GZIPInputStream` stops after the first gzip member and silently drops the rest, on every Native OS. The JVM's version decodes all members.

## Design

Lane: Express. A test-backed fix with a clear diagnosis, no Windows-only code path, and no change to JVM, Linux, or macOS behaviour. Merge waits for the Windows job to pass (per the launch prompt).

- Keep the decoder on javalib's `GZIPInputStream`/`InflaterInputStream`. Make the 8 KiB-per-call bound an explicit invariant in `RequestDecompression`, with a comment that names the Windows 64 KiB limit, so a later "read it all at once" refactor doesn't bring the bug back.
- Add a shared JVM/Native test (it runs on the Windows job) that:
  - decodes gzip, zlib-wrapped deflate, and raw deflate bodies of several sizes (empty, 1 B, under 8 KiB, about 70 KB, and about 300 KB of incompressible data), both through `RequestDecompression.decode` and through a running socket server.
  - builds its compressed inputs in 8 KiB writes, so building them doesn't hit the javalib bug on Windows.
  - checks that multi-member gzip decodes every member on the JVM. On Native, it pins today's first-member-only result, so a later fix has to update the test.
- The PR description carries an upstream-ready write-up of the javalib bug. I'll ask before filing it.
- Follow-ups, out of scope:
  - Native multi-member gzip: today the server answers 200 with only the first member. Fixing it means either decoding every member or rejecting leftover bytes with 400 (the ADR's rule for malformed bodies). Either way, Linux and macOS behaviour changes too.
  - uni-core's Native `Gzip.compress`, which calls `GZIPOutputStream.write(data)` in one call and would hang on Windows above 64 KiB.
  - #990: its `gzipStream` helper needs chunked writes, and pooled response compression over 64 KiB is still wrong on Windows.

## Alternatives and Why Not?

- Windows-only pure-Scala inflater: a lot of code for a bug that bounded chunking already avoids.
- Patch the javalib `z_stream` layout from uni: it's private to javalib; fix it upstream instead.

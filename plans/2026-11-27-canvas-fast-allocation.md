# Canvas: fast off-heap allocation without zero-filling

## Goals

- Make `Canvas.newOffHeapCanvas` the fastest allocation available on each platform, so engines (Wvlet at SF100: string vectors, join/aggregation tables, spill buffers) don't pay for zero-filling memory they overwrite anyway.
- Give engines the primitives to build their own memory pools on top of Canvas. Pool policy (size classes, budgets, spilling) belongs to the engine, not to Uni.
- Measure the gain at 64 KB, 1 MB, and 64 MB against `new Array[Byte]` and today's zero-filling allocation, and record it in the PR and an ADR.

## Background

- JVM today: every `newOffHeapCanvas` creates its own `Arena.ofShared()`, and `arena.allocate` always zero-fills (its spec promises zeros).
- No supported JDK API skips zeroing: `Arena.allocate` and `ByteBuffer.allocateDirect` both zero-fill. The documented FFM pattern is a downcall to libc `malloc`/`free`, wrapped with `MemorySegment.reinterpret(size, arena, cleanup)`.
- `Linker` and `reinterpret` are restricted methods: without `--enable-native-access=ALL-UNNAMED` the JVM warns on first use (Java 22+), and a future JDK will deny them by default. Uni already uses restricted FFM for `PosixPty`.
- Scala Native already uses `malloc`; Scala.js `ArrayBuffer` is always zero-filled.
- The Canvas ADR promises only "not guaranteed to be zero-filled", so this is not an API break.

## Design

Lane: Standard — changes how JVM off-heap memory is obtained (user approved; scope reduced on the user's feedback: no pool in Uni).

- **JVM allocation:** when native access is enabled for Uni's module (`Module.isNativeAccessEnabled()`), `newOffHeapCanvas` gets memory from `malloc` via an FFM downcall, attached to the canvas's own shared arena with a cleanup that calls `free` when the arena closes.
- **Fallback:** without native access, it silently keeps today's zero-filling `arena.allocate`, so Uni never triggers the native-access warning. The docs tell engines to add the flag.
- **Safety unchanged:** the malloc'd segment is owned by a shared arena exactly as before, so bounds checks, `IllegalStateException` after `close()`, safety under concurrent `close()`, and the Cleaner safety net all stay. A NULL from `malloc` throws `OutOfMemoryError`; size 0 allocates 1 byte, as on Native.
- **Contents contract:** off-heap canvas contents are unspecified (zeros or leftover bytes); callers must not rely on either.
- **New `Canvas.fill(offset, length, value)`** on every platform (FFM `fill`, `Int8Array.fill`, `memset`, `Arrays.fill`), so an engine that reuses buffers can zero just the part it needs.
- **Benchmark:** a small JVM program compares allocate + close and first touch for `new Array[Byte]`, zero-filling arena, malloc arena, and slices of one pre-allocated segment (the cost an engine-owned pool would pay), at 64 KB / 1 MB / 64 MB.
- **Tests:** JVM tests fork with `--enable-native-access=ALL-UNNAMED` so the malloc path runs in CI; a test covers the fallback path directly; shared tests cover `fill`.
- **Docs:** new ADR, docs/core/canvas.md (flag, contents contract, how to build a pool from slices), the Canvas rows in docs/guide/for-ai-agents.md and the `uni` skill (plugin version bump), and the PTY ADR's "Java 24+" warning claim.

## Alternatives and Why Not?

- **`CanvasPool` in Uni:** pool policy (budgets, size classes, spill triggers) is engine-specific; a DBMS like Wvlet should own it. Dropped on user feedback.
- **`sun.misc.Unsafe.allocateMemory`:** rejected by the Canvas ADR; deprecated for removal (JEP 471).
- **malloc always, without the native-access check:** prints a JVM warning in every app that touches a canvas, and breaks once the JDK denies restricted calls by default.

## Preferences learned

- Context: canvas allocation contract. Chose: "contents are not zeroed" contract. User chose: zeroing is optional; the default must simply be the fastest allocation. Principle: promise the outcome the caller needs (speed), not an implementation side effect.
- Context: memory pooling. Chose: `CanvasPool` in Uni (from the launch prompt). User chose: no pool in Canvas; the DBMS manages its own pool. Principle: a utility library provides primitives; policy-heavy resource management belongs to the application.

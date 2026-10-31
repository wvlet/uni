# NativeServer failures under concurrent keep-alive load on Linux

## Goals

- Make the Native RPC benchmark serve concurrent keep-alive load on Linux x86_64 and arm64 with 0 failed requests and no stall.
- Tell users of `NativeServer` how to avoid the same failure until Scala Native fixes it.

## Background

- Root cause is not in uni. It is a Scala Native 0.5.12 GC bug: with several threads allocating at once, Immix/Commix sometimes free or clobber live objects, most often with the release-mode default of trap-based yieldpoints. Upstream issues: scala-native#5046 (the sweeper frees a live array's line), #5160 (registers corrupted after a safepoint trap on Linux aarch64), and #5071 (a `NativeConfig` setting for the yieldpoint mode).
- Symptoms in NativeServer: `ArrayIndexOutOfBoundsException` with impossible indexes in the request parser and the ISO-8859-1 encoder, and garbled response bytes. On arm64 the process aborts (`std::terminate`) within a second, and the kernel then spends a long time writing a core dump, which looks like a stall.
- `handleConnection` logs unexpected exceptions at debug level and closes the socket, so on x86 the corruption surfaced only as "connection closed before message completed".
- Diagnostic A/B, same binary config (immix, release-full, full LTO, server on 2 cores, oha `-c 16` for 10 s, 6 runs each):
  - arm64: traps on, 6/6 aborted; traps off (`SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`), 6/6 clean at ~32k req/s.
  - x86_64: traps on, 6/6 had 19–38 failures. With traps off, the follow-up diagnostic showed every trial corrupting and then stalling permanently.
  - Boehm and no-GC builds were clean; a single server core was clean; macOS (18 cores) did not reproduce.
- Neither the GC choice nor the yieldpoint variable (read from sbt's environment at link time) can be set by a library for its users. uni's own Native tests link in debug mode and never hit the bug.

## Design

Lane: Standard. The direction changed twice: first from a uni code fix to a build workaround, then from conditional yieldpoints to the Boehm GC.

- Build the example with `GC.boehm`. The user chose this direction; it also fixed a crash in Wvlet. CI and `bench/build.sh` install libgc, and the container image ships `libgc.so.1` next to the binary.
- Conditional yieldpoints (`SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`) were tried first and dropped. They fixed arm64, but on x86_64 every trial still corrupted memory, a worker then segfaulted, and the GC waited on it forever (a permanent stall at ~2 req/s).
- Docs (`docs/http/server.md`, the example README, the `NativeServer` scaladoc) recommend Boehm for NativeServer apps, link the upstream issues, and state the throughput cost (~14k vs ~90k req/s on an M5 Pro).
- Log unexpected exceptions in `handleConnection` at warn instead of debug, so corruption isn't hidden as dropped connections.
- Add a NativeServer test with concurrent keep-alive clients. It runs in debug mode, so it guards keep-alive handling but can't reproduce this GC bug.
- Revisit Immix after a Scala Native release ships the upstream fix (#5159, merged Oct 29, unreleased).

## Alternatives and Why Not?

- **Conditional yieldpoints**: fixed arm64 only; x86_64 still corrupted memory and then stalled.
- **Work around it in NativeServer code**: the corruption hits any allocation-heavy multithreaded code, so it would only make the bug rarer.
- **Build Scala Native main with #5159 and test it**: no published nightly was found, and building it costs 30–45 min per CI run. Revisit once 0.5.13 is released.
- **A single-threaded event loop for NativeServer**: avoids a multithreaded GC, but it's a redesign, and blocking handlers would stall the whole server.

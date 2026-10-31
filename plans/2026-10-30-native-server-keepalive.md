# NativeServer failures under concurrent keep-alive load on Linux

## Goals

- Make the Native RPC benchmark serve concurrent keep-alive load on Linux x86_64 and arm64 with 0 failed requests and no stall.
- Tell users of `NativeServer` (and any multithreaded release-mode Native app) how to avoid the same failure until Scala Native fixes it.

## Background

- Root cause is not in uni. It is a Scala Native 0.5.12 GC bug: in release builds, multithreaded Immix/Commix use trap-based yieldpoints by default, and the GC sometimes frees or clobbers live objects. Upstream issues: scala-native#5046 (sweeper frees a live array's line), #5160 (registers corrupted after a safepoint trap on Linux aarch64), and #5071 (asks for a `NativeConfig` setting for the yieldpoint mode).
- Symptoms in NativeServer: `ArrayIndexOutOfBoundsException` with impossible indexes in the request parser and the ISO-8859-1 encoder, and garbled response bytes. On arm64 the process aborts (`std::terminate`) within a second, and the kernel then spends a long time writing a core dump, which looks like a stall.
- `handleConnection` logs unexpected exceptions at debug level and closes the socket, so on x86 the corruption surfaced only as "connection closed before message completed".
- Diagnostic A/B, same binary config (immix, release-full, full LTO, server on 2 cores, oha `-c 16` for 10 s, 6 runs each):
  - arm64: traps on, 6/6 aborted; traps off (`SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`), 6/6 clean at ~32k req/s.
  - x86_64: traps on, 6/6 had 19–38 failures; traps off, 5/6 clean, and 1 failure in a run where the runner was very slow.
  - Boehm and no-GC builds were clean; a single server core was clean; macOS (18 cores) did not reproduce.
- The variable is read from sbt's environment at link time, not from `NativeConfig`, so a library cannot set it for its users. uni's own Native tests link in debug mode, which already uses conditional yieldpoints.

## Design

Lane: Standard. The fix is a build workaround plus user-facing guidance rather than a code fix, and the upstream report is outward-facing.

- Link the example with conditional yieldpoints: `bench/build.sh` and the CI step that links the example export `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`.
- Guard it in `build.sbt`: `exampleRpcNativeNative/nativeLink` fails in release mode with a clear message when the variable isn't `0`, so nobody benchmarks a binary that corrupts memory.
- Document the workaround with links to the upstream issues: `docs/http/server.md` (Native section), the example README, and the `NativeServer` scaladoc. Drop the note when a Scala Native release fixes the bug.
- Log unexpected exceptions in `handleConnection` at warn instead of debug: one escaping the parser or writer is a bug, and the debug level hid this one. Normal disconnects return `Closed`, not an exception, so this adds no noise.
- Add a NativeServer test with concurrent keep-alive clients (several threads, many requests per connection) that checks every response. It runs in debug mode, so it guards keep-alive handling in the server but can't reproduce this GC bug.
- Remove the temporary diagnostic workflow before merge. Validate by rerunning `rpc-bench.yml` on both arches: 0 failed requests and no stall.
- Post the A/B data and the reproduction steps to scala-native#5160 (only with your approval).

## Alternatives and Why Not?

- **Work around it in NativeServer code** (fewer allocations, fewer `@blocking` calls): the corruption hits any allocation-heavy multithreaded code, so it would only make the bug rarer.
- **Boehm GC for the example**: clean, but needs libgc at runtime and gives up the "links only libc" point of #833.
- **Make `rpc-bench.yml` fail on Native errors**: would catch regressions, but it changes the benchmark beyond diagnosis (out of scope per the request). It can be a follow-up.
- **Wait for the upstream fix**: there is no release with a fix yet, and the benchmark numbers in #833 depend on a working build.

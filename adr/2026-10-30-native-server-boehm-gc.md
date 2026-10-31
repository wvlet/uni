# Build NativeServer apps with the Boehm GC

- Status: Accepted
- Date: 2026-10-30
- Plan: [`plans/2026-10-30-native-server-keepalive.md`](../plans/2026-10-30-native-server-keepalive.md)

## Context

The RPC benchmark from #833 (`examples/rpc-native/bench`, `rpc-bench.yml`) failed on Linux under concurrent keep-alive load. On arm64 the server aborted within a second, and the kernel's slow core dump looked like a stall at ~1.6 req/s. On x86_64 it dropped kept-alive connections mid-request. The cause is not in uni. With several threads allocating at once, Scala Native 0.5.12's own collectors (Immix, the default, and Commix) free or overwrite objects that are still in use: [scala-native#5046](https://github.com/scala-native/scala-native/issues/5046), [#5160](https://github.com/scala-native/scala-native/issues/5160). In NativeServer this showed up as impossible `ArrayIndexOutOfBoundsException`s in the request parser and the ISO-8859-1 encoder, `ArrayStoreException: Invalid array copy`, and garbled responses. `NativeServer` runs every handler on a pool of worker threads, so any concurrent load can trigger it. uni's own Native tests link in debug mode and never hit it.

Measured with the benchmark build (release-full, full LTO), server on 2 cores, oha `-c 16` for 10 s:

- Immix with trap-based yieldpoints (the release default): arm64 aborted in 6/6 runs; x86_64 failed 19–38 requests in 6/6 runs.
- Immix with `SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0`: arm64 was clean, but x86_64 still corrupted memory in 6/6 trials. A worker then segfaulted, and the GC waited for it to reach a safepoint forever (~2 req/s).
- Boehm: 0 failed requests on both arches (22.0k req/s on x86_64, 14.1k on arm64). The Boehm GC also fixed a crash in Wvlet.

## Decision

- **The example builds with `GC.boehm`.** CI and `bench/build.sh` install `libgc-dev`, and the container image copies `libgc.so.1` from the build machine, since `distroless/cc` has none.
- **The docs recommend Boehm for NativeServer apps**: `docs/http/server.md`, the example README, and the `NativeServer` scaladoc, with links to the upstream issues.
- **uni does not change the GC for users.** The GC is the application's `nativeConfig` choice, and a library can't set it. The env-var yieldpoint switch can't be set from a library either: Scala Native reads it from sbt's environment at link time.
- **`handleConnection` logs unexpected exceptions at warn.** Disconnects and malformed requests surface as `ReadResult` values, so an exception there is a bug or memory corruption. At debug level it was hidden as dropped connections.

## Consequences

- Throughput drops: about 14k vs 90k req/s on an M5 Pro, and on Linux runners 22.0k (x86_64) and 14.1k (arm64) req/s with Boehm, where Immix reached ~20k and ~32k in the diagnostic runs. The README's build-mode table was measured with Immix.
- The binary links `libgc` in addition to libc, so the "only libc" claim from #833 no longer holds.
- Revisit when a Scala Native release ships the upstream fix (#5159, "Keep the callers' callee-saved registers visible while a thread is stopped", merged 2026-10-29, unreleased): rerun `rpc-bench.yml` with Immix on both arches, and drop this recommendation if it is clean.

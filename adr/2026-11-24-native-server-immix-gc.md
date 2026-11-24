# Build NativeServer apps with Immix again on Scala Native 0.5.13

- Status: Accepted
- Date: 2026-11-24
- Supersedes: [`2026-10-30-native-server-boehm-gc.md`](2026-10-30-native-server-boehm-gc.md) (its Boehm tuning still applies to apps that choose Boehm)
- Plan: [`plans/2026-11-24-native-immix.md`](../plans/2026-11-24-native-immix.md)

## Context

ADR 2026-10-30 moved `examples/rpc-native` and the NativeServer docs to Boehm, because Scala Native 0.5.12's Immix and Commix corrupted live objects when worker threads allocated at once (scala-native#5046, #5160). Scala Native 0.5.13 (#1178) fixes both, along with about 30 other Immix/Commix stability issues. It also adds `GarbageCollectorConfig`, which sets heap limits at build time. Boehm's global allocation lock was the remaining limit on multi-threaded Native throughput.

`rpc-bench.yml` (50 items, 16 connections, two server cores, separate runs on shared runners) with 0.5.13:

| Runner | Immix | Boehm |
|---|---|---|
| x86_64 | 24.3k req/s (0.99x JVM built-in), p99 3.6 ms, 88 MB, 0 failed | 15.1k req/s (0.98x), p99 4.4 ms, 90 MB, 0 failed |
| arm64 | 26.2k req/s (1.14x), p99 3.1 ms, 88 MB, 0 failed | 22.1k req/s (1.08x), p99 3.9 ms, 89 MB, 0 failed |

On an 18-core M5 Pro at 64 connections, Immix served 65.7k req/s against Boehm's 51.3k, but grew to 266 MB RSS against 79 MB. With `GC_MAXIMUM_HEAP_SIZE=64m` it stayed at 74 MB at 63k–89k req/s.

## Decision

- **The examples build with Immix**, the default. `UNI_NATIVE_GC=boehm|commix` overrides it in `exampleRpcNative`, and `rpc-bench.yml` has a matching `gc` input, so Boehm stays measurable. `build.sh` and the container copy libgc only for Boehm builds. HTTP Garden no longer installs libgc.
- **The docs recommend Scala Native 0.5.13+ with Immix and a heap cap** (`withGarbageCollectorConfig(_.withMaxHeapSize(...))` or `GC_MAXIMUM_HEAP_SIZE`): `docs/http/server.md`, the example README, and the `NativeServer` scaladoc. Boehm stays the advice for 0.5.12.
- **Uni's Boehm tuning stays.** `uni_gc_shim.c` (one marker thread, a 16 MB server heap) finds libgc through `dlsym` and does nothing in an Immix binary. The rpc-native test binary keeps linking Boehm (Immix on Windows), so `BoehmMarkersTest` and `BoehmHeapTest` still cover it, and CI rechecks the 0.5.12 Boehm debug-mode deadlock, whose cause (allocating while holding the park mutex) 0.5.13 removed.
- Uni still does not choose the GC for users, for the reasons in ADR 2026-10-30.

## Consequences

- The example binary and container no longer need libgc.
- Without a heap cap, Immix keeps a much larger heap than Boehm on many-core machines. The docs make the cap the recommended setting, but Uni does not set it.
- A soak run (`rpc-bench.yml`, mode=soak) gates any further change to these defaults.

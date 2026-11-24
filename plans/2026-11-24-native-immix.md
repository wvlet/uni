# Move NativeServer examples back to Immix on Scala Native 0.5.13

## Why
- ADR 2026-10-30 put NativeServer apps on Boehm because Scala Native 0.5.12's Immix/Commix corrupted live objects under concurrent allocation (scala-native#5046, #5160). 0.5.13 (#1178) fixes both.
- Boehm's global allocation lock is the remaining limit on multi-threaded Native throughput (plans/2026-11-19-native-server-throughput.md).

## Change
- `exampleRpcNative` and `exampleHttpGarden` link Immix, the default. `UNI_NATIVE_GC=boehm|commix` overrides it for benchmarks.
- `rpc-bench.yml` gets a `gc` input and installs libgc only for Boehm. `build.sh` copies libgc only for Boehm builds, and the container copies whichever `lib*.so.1` exist.
- The rpc-native test binary stays on Boehm (Immix on Windows), so CI still covers `uni_gc_shim.c` for apps that choose Boehm, and the 0.5.12 Boehm debug-mode deadlock gets rechecked.
- `uni_gc_shim.c` stays: it uses `dlsym` and does nothing without libgc.

## Gate
Change the docs and the ADR only if all of these are clean:
- `rpc-bench.yml` standard, `items=50`, gc=immix and gc=boehm, x86_64 and arm64: 0 failed requests on Immix, with throughput and p99 compared against Boehm.
- `rpc-bench.yml` soak, `items=50`, gc=immix: no failures and no RSS growth.

## Local data (Apple M5 Pro, 64 connections, items=50, `GC_INITIAL_HEAP_SIZE=64m`; noisy, shared machine)
- Boehm: 51.3k req/s, p99 6.9 ms, RSS 79 MB.
- Immix: 65.7k req/s, p99 4.7 ms, RSS 266 MB. With `GC_MAXIMUM_HEAP_SIZE=64m`: 62.7k–89.2k req/s, p99 1.4–5.6 ms, RSS 74 MB.
- 0 failed requests in every run. Immix grows the heap far past the live set unless capped, so the docs should name a heap cap (now settable at build time with `withGarbageCollectorConfig`).

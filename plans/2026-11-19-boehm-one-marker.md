# Run Boehm with one marker thread by default

## Goals

- With Boehm's defaults, the Native server served 24–32k req/s against the JVM's 102–120k on the RPC benchmark. One marker thread alone gives 77–89k at the same ~11 MB RSS. Make that the default instead of a setting users must find.

## Background

- After #981 and #982, allocation is small enough that one marker is the main lever. Boehm starts one marker per core (15 on 18 cores), and a server's small heap makes it collect often, so each collection stops every worker while the markers hand work around.
- ADR 2026-10-30 left GC settings to the app. The user chose to have uni set the marker count (2026-11-19).

## Design

Lane: Standard. It changes runtime behaviour for every uni Native app built with Boehm. The direction was approved on the question card.

- A C constructor in a new `uni_gc_shim.c` calls `GC_set_markers_count(1)` before Scala Native's `GC_init`.
- It looks the function up with `dlsym`, so builds with Immix, Commix, or no GC link unchanged (the curl-shim pattern, ADR 2026-07-06). It does nothing on Windows.
- `GC_MARKERS` still wins, since Boehm reads it at `GC_init` (`GC_NPROCS` doesn't). Needs bdwgc 8.2+; older libgc keeps Boehm's default.
- Docs, the `NativeServer` scaladoc, the example README, the bench, and the ADR now say uni sets one marker. `GC_INITIAL_HEAP_SIZE=64m` stays an option: about 15% more throughput for about 65 MB more RSS.
- Test: the rpc-native example's Native test (a Boehm build) checks that one marker runs when `GC_MARKERS` is unset.

## Alternatives and Why Not?

- **`setenv("GC_MARKERS", "1")` in the constructor.** It also works with a statically linked libgc, but it leaks into child processes.
- **Only for binaries that reference NativeServer.** This needs a build-time switch, and one marker suits the small heaps of most uni Native apps.

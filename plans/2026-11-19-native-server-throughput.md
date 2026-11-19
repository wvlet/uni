# Native server throughput: findings and direction

## Goals

- Bring `NativeServer` throughput under concurrent load closer to the JVM built-in server. After #975 (idle poller) Native stayed flat at about 14k req/s from 1 to 256 connections, while the JVM reached 79k to 126k.
- Decide which optimizations are worth doing, based on a profile rather than guesses. Decide whether Lean (`spec/lean`) can help.

## Background: what the profile shows

Setup: `examples/rpc-native/bench` binary (release-full, full LTO, Boehm GC), `POST /example.rpc.TodoApi/list`, oha on the same Apple M5 Pro. Other processes loaded the machine heavily (load average 7 to 26), so req/s swung up to 3x between identical runs. Comparisons below use interleaved rounds of every config, plus two numbers that noise barely moves: bytes allocated per request and GC count, both from `GC_PRINT_STATS=1`.

- **The bottleneck is Boehm's global allocation lock, not syscalls, scheduling, or the parser.** In a `sample` of the 16-connection run, workers spent about 5,000 samples in `__psynch_mutexwait`, called from `GC_malloc_kind → GC_generic_malloc_many`, against about 100 samples of request work. That function refills each thread's free list under the global GC lock, so the allocation rate itself serializes the workers.
- **Boehm keeps the heap tiny and collects constantly.** The heap stays about 2.5 MB, and a stop-the-world collection runs about every 27 requests (225 to 370 GCs per 10k requests). Each one stops every thread for 6 to 11 ms to mark under 1 MB of live data. With 15 parallel marker threads (the default on 18 cores), handing work between markers costs more than the marking itself.
- **Native allocates about 44 KB per request.** That's 1.18 MB between collections, about 27 requests. The JVM allocates roughly 12 KB per request (JFR estimate). The Native javalib adds its own allocations: `String.toLowerCase` walks Unicode case tables through a closure per call, header bytes are decoded through `CharsetDecoder`, and a regex compiles somewhere on the request path (not in `tokens`: a one-char `split(",")` takes the javalib's fast path). With full LTO, `sample` cannot attribute Native allocations to call sites, and a build without LTO didn't help either.
- **Where the shared code allocates** (JFR on the JVM, same `SocketHttpServer`/`HttpConnectionReader` code): `HttpMultiMap` about 30% (each header `add` rebuilds two immutable maps, lowercases the key, and appends to a `Seq`; `get`/`getAll`/`entries`/`contains` lowercase again), `HttpConnectionReader.latin1` about 8%, msgpack/JSON `toJson` about 13%, the RPC handler and app code about 13%, `serializeHead` about 5%.
- **Per-request syscalls are a minor cost.** `NativeSocketConnection.read` calls `poll` before every `recv`, even when the poller has just reported the socket readable. That's a wasted syscall, but it only matters once the lock is gone.
- **Scala Native 0.5.13 fixes Immix for this workload, at least on macOS arm64.** The fix for concurrent allocation (scala-native#5159) is merged but unreleased. 0.5.12 is still the latest stable release. The bench built against nightly `0.5.13-20261114-143517b-SNAPSHOT` with `GC.immix` served 3.5M requests at 16 and 64 connections with 0 failures, and almost no mutex waits remained (287 samples, down from about 5,000). Untested: Linux x86_64 and arm64, where 0.5.12 failed (ADR 2026-10-30). The #5159 PR also notes that a separate problem with threads stopped in `@blocking` calls is not fixed, and the server spends most of its time in `@blocking` `poll`.

Throughput at 16 connections, interleaved rounds, same session (req/s; CPU per request in µs):

| Server | Round 1 | Round 2 | Round 3 | CPU µs/req | RSS |
|---|---|---|---|---|---|
| Native, Boehm defaults | 12.4k | 10.8k | 10.1k | 230–290 | 13 MB |
| Native, Boehm `GC_INITIAL_HEAP_SIZE=64M GC_MARKERS=1` | 23.9k | 52.0k | 46.0k | 183–362 | 80 MB |
| Native, Immix on 0.5.13 nightly | 67.6k | 68.8k | 38.6k | 135–205 | 28–45 MB |
| JVM built-in | 110k | 109k | – | 45 | 660 MB |

At 64 and 256 connections (one run each): Boehm defaults 9.4k/9.0k, tuned Boehm 31.6k/39.0k, Immix 58.3k/51.8k, JVM 51.9k/51.2k (the JVM run was also slowed by load).

- Each setting alone helps little. Heap 64 MB alone: 6–14k; one marker alone: 9k. Together they give 3–4x.
- Even with Immix, a Native request costs 3–4x the JVM's CPU. That gap is allocation and javalib work per request, not GC locking.

## Design

Lane: Standard. The direction changes the GC recommendation for users and the shared HTTP code path, and the user asked to approve the direction first.

Recommended direction, in this order:

1. **Ship working Boehm settings now.** Set `GC_MARKERS=1` and `GC_INITIAL_HEAP_SIZE=64m` in the rpc-native example (Dockerfile `ENV`, `bench.py`), and recommend them in `docs/http/server.md` and the `NativeServer` scaladoc next to the existing Boehm advice. Expected: about 3x at 16 to 256 connections, at the cost of about 65 MB more RSS. uni does not set the GC for apps (ADR 2026-10-30), so these stay documented settings.
2. **Cut per-request allocation in the shared HTTP path.** This helps every GC, and the JVM too:
   - `HttpMultiMap`: a builder that collects entries in a mutable buffer and builds the map once; lowercase header names with an ASCII-only loop instead of `String.toLowerCase`.
   - `HttpConnectionReader`: build header strings straight from the bytes (Latin-1 is one byte per char) instead of going through `CharsetDecoder`; replace `split` in `tokens` with a manual scan.
   - `NativeSocketConnection.read`: try `recv` with `MSG_DONTWAIT` first, and `poll` only on `EAGAIN`, so `readTimeoutMillis` still holds (the fd is blocking).
   - Find the remaining Native-only allocations (the regex, charset buffers) with a Native measurement after each change. Native call sites can't be sampled, so each change is checked by its own before/after number.
   - Track it by bytes allocated per request (`GC_PRINT_STATS`), which load noise doesn't move. Goal: about halve the 44 KB. It is a goal, not a prediction: the JVM shares above cover only about 12 of the 44 KB.
3. **Switch the recommendation to Immix when Scala Native 0.5.13 is released.** Bump the plugin, then run `rpc-bench.yml` with Immix on Linux x86_64 and arm64 (the arches that failed on 0.5.12), plus a long soak at 256 connections (`--soak`). Drop the Boehm recommendation only if all are clean, and link the upstream `@blocking` issue in the ADR update. Measured here, that is the largest single gain (about 5x over Boehm defaults).
4. **Lean: use it to check a rewrite, not to choose one.** The profile points to GC and allocation, not parser structure (the parser is a few percent of samples), so a fused zero-copy parser isn't justified. If step 2 rewrites header handling, extend the model with the header-map semantics it relies on (case-insensitive lookup, `getAll` value order, list tokens; not key order, which the current immutable `Map` doesn't keep past 4 keys), and let the generated cases check the new code against the model, as `HttpReaderSpecTest` does for framing.

- Out of scope, as the profile doesn't point there: parking a connection while its request is still arriving, and a Windows poller.
- Suggested PR split: step 1 alone (docs, example, bench; small), then step 2 as one PR with before/after bytes per request, then step 3 when 0.5.13 ships.

## Alternatives and Why Not?

- **Depend on a Scala Native 0.5.13 nightly now.** Snapshots aren't reproducible releases, and uni's published artifacts would pin users to them.
- **Have uni set Boehm's settings itself** (a C constructor calling `setenv`, or `GC_expand_hp` through a weak symbol). It would tune the GC for every app that links uni, which ADR 2026-10-30 leaves to the app. It's also fragile: `GC_MARKERS` takes effect only before `GC_init`.
- **A per-thread arena or object pools in the request path.** They work around the allocator instead of allocating less, and Immix makes them unnecessary.
- **Commix (the concurrent-mark Immix).** Same upstream bug class as Immix on 0.5.12, and on 0.5.13 Immix already removes the lock.

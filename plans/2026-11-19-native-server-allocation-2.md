# Native server: cut the HTTP layer's per-request allocation further

## Goals

- Close more of the gap between `NativeServer` and the JVM built-in server on the same machine. Today, at 16 connections: Native with Boehm defaults serves about 20k req/s at 150 µs CPU per request; with `GC_MARKERS=1 GC_INITIAL_HEAP_SIZE=64m`, 74k at 82 µs; the JVM 102k at 45 µs.
- Halve the bytes the HTTP layer allocates per request, without changing what handlers see.

## Background

- Follows `plans/2026-11-19-native-server-throughput.md` (#976, #978, #980 done). Immix still waits for Scala Native 0.5.13, which is not released (0.5.12 is the latest).
- A `sample` profile at Boehm defaults: worker time is almost all waiting on Boehm's global lock, in `GC_generic_malloc_many` (thread-local free-list refills, about 10.5k samples) and `GC_malloc_kind_global` (about 1.6k), against a few hundred samples of request work. Collections hold that lock, so the cost scales with bytes allocated: 18.3 KB per RPC request, 137 collections per 10k requests.
- Boehm serves only small objects (up to about 400 bytes) from per-thread free lists. Any larger array takes the global lock on every allocation. Per request, the one such allocation is `serializeHead`'s 256-char `StringBuilder` (lldb on `GC_malloc_kind_global` while serving).
- A byte-weighted profile (lldb on refills, grouped by caller) attributes the HTTP layer's share as follows:
  - Request header map: about 28% (`HttpMultiMap.Builder` fills two `LinkedHashMap`s and then copies them into two immutable `Map`s and a `Seq` per key)
  - Header name and value strings: about 9%
  - Response head: about 5%
  - Small items: `HttpMethod.values` copied on every parse, `Int` boxing through the reader's `Function` callbacks, and the handler latch in `awaitRx`.
  - The RPC codec (JSON parse, `Weaver.toJson` through msgpack) is about 15%. It is a separate follow-up.

## Design

Lane: Express. It's an internal performance change with no API change and no always-gate area. The one observable difference is noted below.

- **`HttpMultiMap` keeps its entries in insertion-ordered arrays** (lower-case key, original key, values) instead of two immutable maps. Headers number in the tens, so lookup is a linear scan comparing lower-case keys, as Netty's `DefaultHttpHeaders` does for small header sets. The builder fills the arrays directly. `toMap` is built only when called.
  - Observable: `entries` and `keys` now always iterate in insertion order. Before, that held only up to 4 keys, because an immutable `Map` loses order past 4. Lookups are unchanged: case-insensitive, first spelling kept, values in order.
  - `HttpMultiMap(...)`, `fromSeq`, `fromMap`, and `fromMultiMap` go through the builder instead of repeated copying `add`.
- **Common header names reuse a shared string.** When a header name's bytes exactly match a known spelling (`Host`, `host`, `Content-Type`, ...), the reader uses that constant instead of allocating a new string. Original casing is still kept, since only an exact match is reused.
- **The response head is written straight to bytes.** One exact-size array holds the head and the body, instead of a `StringBuilder` (above Boehm's small-object limit), a char copy, and `head ++ body`.
- **No boxing or table copies on the request path.** The reader's callbacks become a small trait with `Int` methods, `HttpMethod` parses without copying `values`, and the version check compares bytes instead of building a string.
- **`awaitRx` skips the latch** when the handler's `Rx` completes on the calling thread (the common case for a synchronous handler). It still waits on a latch otherwise.
- Measured by bytes per request and collections per 10k requests from `GC_PRINT_STATS` (load noise doesn't move these), plus interleaved req/s and CPU per request for Native (defaults and tuned) and the JVM. Before and after numbers go in the PR.
- Tests: `HttpMultiMapTest` gains order and large-map cases. The existing reader, Lean-generated (`HttpReaderSpecTest`), HTTP Garden, and server suites cover the rest, on JVM and Native.

## Alternatives and Why Not?

- **Make uni set `GC_MARKERS=1` itself** (a C constructor in the shim). It's the largest single lever at Boehm defaults (about 3x here), but ADR 2026-10-30 leaves GC settings to the app. It needs its own decision, not a side effect of this PR.
- **Per-thread buffer pools for the response.** They work around the allocator, and Immix makes them unnecessary. Allocating less helps every GC and the JVM too.
- **A streaming msgpack→JSON writer for RPC results.** Worth doing, but it's a codec change with its own tests. It's next.

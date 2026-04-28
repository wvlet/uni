# Weaver: Support self-recursive types in `fromSurface`

Tracking: https://github.com/wvlet/uni/issues/515

## Problem

`Weaver.fromSurface` (added in #513) crashes on self-recursive types with:

```
java.lang.IllegalStateException: Recursive update
  at java.util.concurrent.ConcurrentHashMap.computeIfAbsent
```

Reproducer (wvlet's `DataType` is the canonical case):

```scala
abstract class DataType(val typeName: TypeName, val typeParams: List[DataType])
```

Building `Weaver[DataType]` walks its fields, hits `typeParams: List[DataType]`,
and recursively calls `fromSurface(DataType)` while the outer
`computeIfAbsent` for that key is still on the stack — which Java's
`ConcurrentHashMap` explicitly disallows.

## Why airframe-codec doesn't hit this

`MessageCodecFactory.ofSurface` threads a `seen: Set[Surface]` and returns a
`LazyCodec` placeholder when recursion sees the same surface twice. The
placeholder's `ref` is `lazy val` and looks up the per-factory cache only
when `pack`/`unpack` is called. Crucially that cache is a *plain `Map`
private to the factory instance*, so it is single-threaded — no other
thread ever observes a partial weaver tree.

uni's `Weaver.fromSurface` uses a single global cache, so adapting the
airframe approach has to be more careful about cross-thread visibility.

## Final design

1. **Drop `getOrElseUpdate` / nested `computeIfAbsent`.** Use direct
   `ConcurrentHashMap.get` + `putIfAbsent` so cache writes never nest
   (which is what Java's CHM blocks for recursive computations on the
   same key).
2. **Per-thread pending-build map.** Each top-level `fromSurface`
   accumulates its sub-builds in a `ThreadLocal[LinkedHashMap]`. Entries
   are committed to the shared cache only after the outer-most call
   returns — concurrent readers never observe a partial weaver tree.
3. **`LazyWeaver` placeholder with direct resolution.** When recursion
   re-enters a surface still on the build stack, we return a
   `LazyWeaver`. After that surface's `buildWeaver` finishes, we wire the
   placeholder's target in directly via `resolve(...)` — *not* via cache
   lookup at use time. So visibility to other threads is independent of
   commit order.
4. **Track recursion by `Surface.fullName`** (not the Surface instance).
   `LazySurface` (uni's compile-time recursive-surface placeholder) and
   the corresponding `GenericSurface` have different equality, so a
   Set-of-Surface guard would miss the cycle in the common
   `Node(next: Option[Node])` case.

`WeaverFactory` and the rest of the public surface of `Weaver` are
unchanged: factories don't need a `seen` parameter because recursion is
handled inside `fromSurface` itself.

## Iterations and lessons (from codex review)

The fix went through three drafts. Each round revealed a subtler issue:

1. **Draft 1** — `seen: Set[Surface]` threaded through factories; cache
   committed entry-by-entry.
   - codex: `seen.contains(surface)` misses `LazySurface` because
     `LazySurface` and `GenericSurface` have different case-class
     equality even when they describe the same type.
   - codex: a sub-weaver containing a `LazyWeaver` is published to the
     shared cache mid-build, so a parallel reader can grab it before
     the LazyWeaver's target is in the cache.

2. **Draft 2** — atomic commit at outer-most return; key recursion by
   `fullName` via per-thread pending map.
   - codex: when the outer-most surface is a *wrapper* around the
     recursive type (e.g. `case class Wrap(node: Node)`), insertion-order
     commit publishes Wrap before Node. Wrap's structure embeds
     CaseClassWeaver(Node) inline, which embeds `LazyWeaver("Node")`. A
     parallel reader hits IllegalStateException before Node is published.

3. **Draft 3** (final) — `LazyWeaver` holds a directly-set reference,
   wired in by the outer build before commit. Use-time no longer touches
   the cache, so commit order is irrelevant for correctness.

**Lesson:** when adapting a known design (airframe-codec's `LazyCodec`)
into a different concurrency model (single-instance cache → globally
shared cache), the original cache-lookup pattern stops being safe. The
fix is to *eliminate* the cache lookup at use time, not to engineer the
commit order around it.

## Test

`RecursiveSurfaceWeaverTest` covers:
- self-recursive abstract class with `List[Self]` (the `DataType` shape),
- abstract field embedded in a concrete case class,
- self-recursive case class via `Option`,
- mutually recursive case classes,
- round-trip through the `LazyWeaver` path,
- a 64-task concurrent stress test on the same recursive type — the
  regression test for the cross-thread race that draft 2 still had.

## Out of scope

- Surface caching itself (a separate concern).
- Sealed-trait dispatch on the abstract base — the field stays lossy
  (empty-object fallback) per #511 / #513.

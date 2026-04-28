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

`MessageCodecFactory.ofSurface` (airframe-codec) takes a `seen: Set[Surface]`
parameter. When recursion sees the same surface twice, it returns a
`LazyCodec` placeholder. The placeholder's `ref` is `lazy val`, so the actual
codec lookup happens only when `pack`/`unpack` is called — by which point
the cache has been populated by the outer call. Crucially the cache there is
a plain `Map`, populated *after* `generateObjectSurface` returns — so cache
writes never nest.

## Fix

Mirror the airframe-codec design:

1. **Drop `getOrElseUpdate`** on `ConcurrentHashMap`. Use explicit `get` /
   `putIfAbsent` so cache writes don't nest.
2. **Thread a `seen: Set[Surface]`** through `fromSurface` /
   `buildWeaver` / each `WeaverFactory`. When a recursive request comes in
   for a surface already on the stack, return a `LazyWeaver` placeholder
   instead of recursing.
3. **`LazyWeaver`** holds the surface and resolves to the cached weaver
   lazily on first `pack`/`unpack`.

`fromSurface(surface: Surface)` keeps its public single-arg signature for
back-compat; recursive callers use a private `fromSurface(surface, seen)`.

## Worked sketch

```scala
private def fromSurface(surface: Surface, seen: Set[Surface]): Weaver[?] =
  surfaceWeaverCache.get(surface.fullName) match
    case Some(w) => w
    case None    =>
      if seen.contains(surface) then LazyWeaver(surface)
      else
        val w = buildWeaver(surface, seen + surface)
        surfaceWeaverCache.putIfAbsent(surface.fullName, w).getOrElse(w)
```

`LazyWeaver` defers to `fromSurface(surface, Set.empty)` on first use; by
that point the outer build has populated the cache, so the recursion is
broken safely.

## Test

Add a test that reproduces the crash with an abstract class whose
`typeParams: List[Self]`. Without the fix this throws
`IllegalStateException`; with it, deriving completes and a round-trip on a
non-abstract subtype works.

## Out of scope

- Surface caching itself (a separate concern).
- Sealed-trait dispatch on the abstract base — the field stays lossy
  (empty-object fallback) per #511 / #513.

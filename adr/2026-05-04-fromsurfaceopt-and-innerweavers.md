# 2026-05-04: `Weaver.fromSurfaceOpt` + `innerWeavers` for safe runtime weaver use

PR: https://github.com/wvlet/uni/pull/538 (commit 20dc390 introduces the design)

## Context

`Weaver.fromSurface(surface)` is the runtime, registry-based path used by RPC and
(after this PR) by `RouterHandler` to derive a weaver from a `Surface`. When no
factory matches a surface, `fromSurface` returns `emptyObjectWeaver` — a lossy
fallback that packs as `{}`. It also throws `IllegalArgumentException` for primitive
surfaces with no matching primitive factory branch (e.g. `java.math.BigInteger`).

For the Router → JSON path we needed: "use the weaver if it can encode this type
losslessly, otherwise fall through to the legacy toString-quoted JSON path." A
top-level `eq emptyObjectWeaver` check is not enough — `fromSurface` builds the
fallback *inside* outer composite weavers for shapes like `Seq[Either[A, B]]` or
`case class Foo(d: LocalDate)`, which would silently emit `[{}]` / `{"d":{}}`.

## Decision

Two coupled pieces:

1. **`Weaver.innerWeavers: Seq[Weaver[?]]`** — a default-empty hook on the `Weaver`
   trait. Every composite weaver class (`OptionWeaver`, `SeqWeaver`, `SetWeaver`,
   `MapWeaver`, `ArrayWeaver`, `JavaListWeaver`, `JavaSetWeaver`, `JavaMapWeaver`,
   `CaseClassWeaver`, `LazyWeaver`) overrides this to expose the weavers it
   delegates to.

2. **`Weaver.fromSurfaceOpt(surface): Option[Weaver[?]]`** — wraps `fromSurface` and
   returns `None` when:
   - `fromSurface` throws (no factory matches the surface).
   - The resulting weaver tree contains `emptyObjectWeaver` at any position. The
     check is an identity-based traversal that reads `innerWeavers` and uses an
     `IdentityHashMap` to short-circuit cycles introduced by `LazyWeaver` for
     self-recursive types.

Callers (today: `RouterHandler.returnWeavers`) treat `None` as "fall through to the
no-weaver path" — preserves the legacy toString-quoted JSON behavior for unsupported
types instead of silently degrading to `{}`.

## Why not a Surface-side predicate?

We considered an `isFullySupported(surface): Boolean` that would mirror
`fromSurface`'s factory matching logic at the Surface level (without building a
weaver). Rejected because:

- It duplicates the factory matching code, with high drift risk: any new factory
  added to `fromSurface` must remember to also extend `isFullySupported`. The
  `innerWeavers`-based walker has the inverse coupling — new composite weavers
  declare what they delegate to, and the walker just reads it.
- It would need its own cycle handling for self-recursive case classes; the weaver
  tree already has cycles broken by `LazyWeaver`, so traversing the tree is
  cheaper.
- It can't catch the "throw" case (`BigInteger`-style) cleanly — there's no
  surface-level signal that says "no factory handles this."

## Why an instance method on `Weaver` and not pattern-matching on weaver classes?

Pattern matching would require `fromSurfaceOpt` to know about every concrete weaver
type. `innerWeavers` puts the responsibility on each composite weaver to declare its
component weavers, which scales with the inheritance of the framework rather than
with a central matcher.

The default `Seq.empty` makes adding new leaf weavers (primitives, custom givens) a
no-op for traversal. New composite weavers — collection or case-class shapes — must
override the method, but that's a one-liner and easy to forget-test (the weaver tree
walker silently treats them as opaque, which would surface as a missed
`emptyObjectWeaver` detection in tests).

## Worked examples (commit 20dc390)

- `Weaver.fromSurfaceOpt(Surface.of[Either[String, Int]])` → `None` (top-level
  fallback)
- `Weaver.fromSurfaceOpt(Surface.of[Seq[Either[String, Int]]])` → `None` (fallback
  inside `SeqWeaver`)
- `Weaver.fromSurfaceOpt(Surface.of[case class Foo(e: Either[String, Int])])` →
  `None` (fallback inside `CaseClassWeaver.fieldWeavers`)
- `Weaver.fromSurfaceOpt(Surface.of[Greeting])` → `Some(weaver)` (case class with
  fully-supported `String` field)
- `Weaver.fromSurfaceOpt(Surface.of[BigInteger])` → `None` (`fromSurface` throws,
  caught and converted)

## Consequences

- `RouterHandler` (and any future runtime weaver consumer) can decide between
  weaver-aware and toString-fallback paths *per route* without expanding
  `fromSurface`'s factory list and without reproducing factory logic.
- New composite weaver classes added to the framework must override
  `innerWeavers`; missing overrides cause `fromSurfaceOpt` to incorrectly return
  `Some` for trees that contain hidden fallbacks. Tests that exercise composite
  shapes against `fromSurfaceOpt` are the primary safeguard — see
  `WeaverTest.scala` "fromSurfaceOpt" cases.
- The pattern is also a hook for future "is this weaver lossless?" checks beyond
  the empty fallback — e.g. detecting `LazyWeaver` cycles that won't terminate, or
  custom user-registered fallbacks. The traversal API is generic enough.

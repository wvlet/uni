# Weaver: derive through non-sealed abstract-class fields

Issue: https://github.com/wvlet/uni/issues/511

## Problem

`Weaver.of[T]` errors at compile time when a field type is a non-sealed
abstract class with no in-scope `Weaver`. The macro can derive case classes
and sealed traits, but for arbitrary abstract classes the compiler can't
enumerate concrete subtypes of an open hierarchy.

```scala
abstract class Animal(val name: String)
case class Dog(name: String, breed: String) extends Animal(name) derives Weaver
case class Cat(name: String, color: String) extends Animal(name) derives Weaver
case class Owner(name: String, pet: Animal)

Weaver.of[Owner]
// compile error: No Weaver found for field 'pet' of type Animal.
```

## Decision: match airframe-codec's behavior

Airframe-codec already handles this case (Scala 3) by relying purely on
Surface — abstract types get a bare `GenericSurface(cls)` with no params and
no `objectFactory`, so the resulting `ObjectMapCodec` packs as `{}` and
unpacks as null. Lossy by design, but `Weaver.of[Owner]` succeeds and the
non-abstract fields round-trip normally. No `subclassesOf` API is added.

uni mirrors this:

1. **Macro change** in
   `uni/src/main/scala/wvlet/uni/weaver/WeaverDerivation.scala`: when a case
   class field's type is a non-sealed abstract class or trait *and* no
   `Weaver[T]` is in scope, instead of erroring, emit:
   ```scala
   Weaver.fromSurface(Surface.of[t]).asInstanceOf[Weaver[t]]
   ```
   For other unresolved types (typos, missing imports), the strict
   compile-time error stays.

2. **Surface fallback** in `uni/src/main/scala/wvlet/uni/weaver/Weaver.scala`:
   `Weaver.fromSurface` previously threw on any non-instantiable Surface.
   Add a final-fallback factory that, for surfaces with no `objectFactory`,
   returns an empty-object weaver — pack as `{}`, unpack as null. Matches
   airframe-codec's `ObjectMapCodec(surface, Seq.empty)` behavior.

That's the whole change. No new public API; no subclass registration; no
runtime reflection; no platform-specific code. Users who need typed
round-trip for an abstract field provide their own `given Weaver[Animal]`.

## Why this is the right shape

- **Parity with airframe**: identical observable behavior for the wvlet
  `Catalog.TableDef` / `DataType` case (the original motivation in #511).
- **Minimal API surface**: zero new public types or methods.
- **Strict-by-default for typos**: the macro still errors loudly when the
  field's type is anything other than a non-sealed abstract / trait without
  a Weaver — only the open-hierarchy escape hatch silently degrades.
- **Cross-platform**: shared code only; no `Class.getModifiers`, no
  `MODULE$` reflection, nothing that breaks on Scala.js / Native.

## Earlier dead end

Initial revisions added a `Weaver.subclassesOf[A]` registration API
(plus `SubclassEntry`, canonical-name dedup, `MODULE$` recovery, etc.) —
~250 lines of surface area for the same use case. Per design feedback this
was overcomplicated; airframe-surface (Scala 3) solves the underlying
problem without a dedicated method, and uni should follow that pattern.
The PR was rewritten to the minimal change above.

## Files touched

- `uni/src/main/scala/wvlet/uni/weaver/Weaver.scala`
  (+ `emptyObjectFallbackFactory` in `fromSurface`)
- `uni/src/main/scala/wvlet/uni/weaver/WeaverDerivation.scala`
  (abstract-field auto-fallback)
- `uni/src/test/scala/wvlet/uni/weaver/AbstractClassWeaverTest.scala` (new,
  small; verifies derivation succeeds and non-abstract fields still round-trip)

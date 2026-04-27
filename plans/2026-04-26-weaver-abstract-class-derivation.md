# Weaver: derive through non-sealed abstract-class fields

Issue: https://github.com/wvlet/uni/issues/511

## Problem

`Weaver.of[T]` errors at compile time when a field type is a non-sealed abstract
class with no in-scope `Weaver`. The macro can derive case classes and sealed
traits, but not arbitrary abstract classes — there's no way for the compiler to
enumerate concrete subtypes of an open hierarchy.

```scala
abstract class Animal(val name: String)
case class Dog(name: String, breed: String) extends Animal(name)
case class Cat(name: String, color: String) extends Animal(name)
case class Owner(name: String, pet: Animal)

Weaver.of[Owner]
// compile error: No Weaver found for field 'pet' of type Animal.
//                Make sure a Weaver instance is available in scope.
```

The user can side-step this with a hand-written `given Weaver[Animal]`, but
constructing one today requires re-implementing the SealedTraitWeaver dispatch
logic by hand.

## Decision

Make the existing `SealedTraitWeaver` available for **non-sealed abstract**
classes too, by exposing a public factory that takes an explicit list of
`(subclass, Weaver)` pairs. The strict compile-time default is preserved — users
opt in by writing one `given` per abstract class:

```scala
given Weaver[Animal] = Weaver.subclassesOf[Animal](
  classOf[Dog] -> Weaver.of[Dog],
  classOf[Cat] -> Weaver.of[Cat]
)

case class Owner(name: String, pet: Animal) derives Weaver
// works: macro finds the in-scope given
```

### Why this approach

- **Predictable**: same wire format as sealed traits (`@type` discriminator), so
  data round-trips through `JSON ↔ MsgPack` identically.
- **Strict by default**: `Weaver.of[T]` keeps failing loudly when no Weaver is
  in scope — no silent fallback that loses type info.
- **Reuses the existing `SealedTraitWeaver`**: zero new pack/unpack code paths.
- **Composable**: each subclass Weaver is itself derivable, so users only
  enumerate the direct concrete children, not the recursive structure.

### Why not a runtime-reflection fallback (issue option 1)

Considered and rejected for the initial fix:

1. `Surface.of[Animal]` for an abstract class produces a bare `GenericSurface`
   with no params and no `objectFactory` (CompileTimeSurfaceFactory:528 skips
   abstract types when building the constructor factory). So a runtime
   reflection path can't recover Animal's params from the Surface graph alone.
2. Reconstructing the concrete subclass on `unpack` still needs a
   class-name → Weaver registry. Whether that registry is built from
   `Class.forName` + Java reflection or from explicit user registration, the
   user has to point us at the subclasses somewhere.
3. JVM-only reflection breaks the cross-platform story for a feature that's
   solvable in shared code.

If a true opaque round-trip (`Map[String, Any]`) is needed later, it's a
separate, additive opt-in (`Weaver.opaqueOf[A]`) that doesn't conflict with
this PR.

### Why not relax the macro to auto-fall-back

Auto-fallback would mean `Weaver.of[Owner]` silently compiles even when no
`given Weaver[Animal]` is in scope, then fails at runtime — or worse, produces
a value that doesn't round-trip. Strict compile-time errors are a deliberate
design choice in uni; they should stay loud.

## Implementation

1. **`Weaver.subclassesOf[A]`** in `uni/src/main/scala/wvlet/uni/weaver/Weaver.scala`:
   ```scala
   def subclassesOf[A](
       subclassWeavers: (Class[? <: A], Weaver[? <: A])*
   ): Weaver[A]
   ```
   Builds the `Map[String, (Weaver[? <: A], Option[A])]` that
   `SealedTraitWeaver` already accepts. Singleton detection: if the class is
   a Scala module (`MODULE$` field), record its singleton instance; otherwise
   `None`. Discriminator name = `Class.getSimpleName.stripSuffix("$")` to match
   how SealedTraitWeaver derives names from `getClass.getSimpleName`.

2. **Improved macro error message** in
   `uni/src/main/scala/wvlet/uni/weaver/WeaverDerivation.scala`:
   When a field type is an abstract class (or trait, but unsealed), point the
   user at `Weaver.subclassesOf`:
   > No Weaver found for field 'pet' of type Animal. Animal is abstract; either
   > seal it (and add `derives Weaver`) or define a given via
   > `Weaver.subclassesOf[Animal](classOf[Dog] -> Weaver.of[Dog], ...)`.

3. **Tests** in `uni/src/test/scala/wvlet/uni/weaver/AbstractClassWeaverTest.scala`:
   - Round-trip Owner with Dog/Cat through MsgPack and JSON.
   - Custom discriminator field name still works.
   - Unregistered subclass at pack time → clear error.
   - Unknown `@type` at unpack time → clear error.
   - Singleton (case object extending abstract class) handled correctly.

## Files touched

- `uni/src/main/scala/wvlet/uni/weaver/Weaver.scala` (+ ~20 lines)
- `uni/src/main/scala/wvlet/uni/weaver/WeaverDerivation.scala` (error message tweak)
- `uni/src/test/scala/wvlet/uni/weaver/AbstractClassWeaverTest.scala` (new)

## Out of scope / follow-ups

- Opaque `Map[String, Any]` round-trip (`Weaver.opaqueOf[A]`) — file separately
  if MessageCodec parity for the truly-unknown case is needed.
- Macro-driven enumeration (`Weaver.subclassesOf[Animal]` with no args, scanning
  reachable subclasses at compile time) — non-trivial and out of scope here.

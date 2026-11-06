# Weaver.fromSurface for enums with parameterized cases

## Goals

- `Weaver.fromSurface` writes the same JSON as `derives Weaver` for an enum with any parameterized case: `{"@type":"Split",...}` for parameterized cases and `{"@type":"Zero"}` for singletons.
- Such enums round-trip through `fromSurface`, including recursion through `Seq`/`Option`, on JVM, JS, and Native.
- Enums whose cases are all singletons keep the string form (`"Zero"`) on both paths.

## Background

- RPC/HTTP code resolves codecs at runtime with `fromSurface`, so today a value encoded on one path can fail to decode on the other (#878, ADR Consequences).
- `EnumSurface` holds only `rawType` and a `String => Option[Any]` lookup, so the runtime has no way to find a case's fields or list the case names.
- The Surface macro already handles enums with a parameterized case differently: they have no `valueOf`, so it builds a name → singleton map from the enum's children.
- Recursive surfaces already work: the macro emits a `LazySurface` for a type it is still generating, and `fromSurface` breaks codec cycles with `LazyWeaver`.

## Design

Lane: Standard. It adds a field to `EnumSurface`, a public uni-core API, and changes the JSON `fromSurface` writes for these enums.

- **Record enum cases in Surface.** `EnumSurface` gets a `cases` list with a default of empty. Each entry is either a singleton case (name and value) or a parameterized case (name and the case class's Surface).
- **Only for enums with a parameterized case.** For all-singleton enums the macro leaves `cases` empty, so their generated code and behavior stay the same. Empty `cases` means "string enum".
- **`fromSurface` reuses `SealedTraitWeaver`.** An `EnumSurface` with cases maps to the same codec the derived path uses. Parameterized cases get `fromSurface(caseSurface)` and singletons get `SingletonWeaver`, so recursion goes through the existing `LazyWeaver`.
- **Case names come from the macro**, not `Surface.name`, so the discriminator matches `productPrefix` like the derived codec.
- Tests compare `fromSurface` output with the derived codec's output byte for byte (singleton, parameterized, recursive through `Seq` and `Option`), check cross-decoding both ways, and check that all-singleton enums still encode as strings. They live in the shared `uni` test tree so they run on all three platforms.
- Update the #878 ADR's Consequences note to say the gap is closed.

## Alternatives and Why Not?

- **A separate `EnumAdtSurface` subclass:** code matching on `EnumSurface` would miss these enums and silently fall back to the lossy empty-object codec.
- **Record cases for every enum:** more generated code for every string enum, and the runtime doesn't need it.
- **Reflect on enum cases at runtime:** not available on JS and Native.

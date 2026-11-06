# Derive Weaver for recursive sum types

## Goals

- `derives Weaver` works for recursive sum types and round-trips through JSON on JVM, JS, and Native. That covers enums with parameterized cases and sealed traits whose children refer back to the trait through `Seq` or `Option`.
- A layout tree like the uni-ui Dock's could then use a derived codec instead of a hand-written one (#877).

## Background

- **The "compiler hang" is a runtime deadlock.** `testOnly` compiled the probe in seconds, then a thread dump showed `Node.derived$Weaver` lazy init → `Split.derived$Weaver` → `Node.derived$Weaver`. Each derived given builds its inner codecs eagerly, so a cycle re-enters a lazy val that is still initializing, and on the JVM that thread waits on itself forever.
- The same cycle hits any self-recursive `derives` (`case class T(next: Option[T]) derives Weaver`). That's why `RecursiveSurfaceWeaverTest` routes recursion through `Weaver.fromSurface` and its `LazyWeaver`.
- **Parameterized enum cases:** derivation treats every Scala 3 enum as a plain string enum (`EnumWeaver`), so `Split(...)` packs as `"Split"` and loses its fields.
- A spike confirmed that a parameterized enum case derived inline finds the enum's own given for `Seq[ENode]`. The compiler even warns "Infinite loop in function body" about the eager self-reference, which matches the deadlock above.

## Design

Lane: Standard. It changes Weaver codec derivation and the JSON shape of enums with parameterized cases (today they lose data, so no working data depends on that shape).

- **Defer inner codecs.** A derived case-class or sum codec resolves its field and child codecs on first use, not while it is built. That breaks every derivation cycle at init time. After first use, the per-call cost is one already-initialized field read.
- **Enums with any parameterized case use the sealed-trait codec:** the same `"@type"` discriminator and flat fields as sealed traits. Singleton cases in such an enum encode as `{"@type":"Row"}`, like case objects. Enums with only singleton cases keep the string form (`"Row"`).
- **Children without a given are derived inline.** Enum cases can't carry `derives`, so their codecs are derived inside the parent's codec. Sealed-trait children without `derives Weaver` get the same treatment instead of today's compile error.
- **The discriminator comes from `productPrefix`, not `getClass.getSimpleName`.** Enum singleton cases are anonymous classes, and `productPrefix` gives the case name on every platform. Non-`Product` values keep the class-name lookup.
- Tests cover the Dock-shaped enum and sealed trait, recursion through `Seq` and `Option`, a self-recursive case class with `derives`, and mixed singleton/parameterized enum cases. They run in the shared `uni` test tree on JVM, JS, and Native. The deadlock regression runs under a timeout so it fails instead of hanging CI.
- Follow-up, out of scope: switch the uni-ui Dock to derived codecs. Its JSON is `{"type":"split"|"group",...}`, so it needs `"type"` as the discriminator field name plus lowercase case names, or a version-2 migration.

## Alternatives and Why Not?

- **Have the macro emit a `lazy val self` and wire recursion by hand:** it only covers self-recursion, not cycles across companions (trait ↔ child given), and adds macro complexity.
- **Route recursive fields through `Weaver.fromSurface`:** a runtime Surface lookup per type, and Surface-driven codecs don't know enum cases with fields.
- **Keep `EnumWeaver` and pack parameterized cases as `[name, fields]`:** a second sum encoding to learn and document, unlike sealed traits.

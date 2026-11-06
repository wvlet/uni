# Derived Weaver for recursive sum types

## Context

`derives Weaver` couldn't handle the uni-ui Dock's layout tree (#877). A recursive sealed trait (`Node` → `Split(children: Seq[Node])`) blocked forever the first time its given was used. The probe reported a compiler hang, but the wait was at runtime. Each derived given built its inner weavers while its own lazy val was still initializing, so `Node`'s initializer reached `Split`'s, which reached `Node`'s again. A self-recursive `case class T(next: Option[T]) derives Weaver` hit the same cycle. Separately, every Scala 3 enum went through the string-only `EnumWeaver`, so an enum case with parameters lost its fields.

## Decision

- `CaseClassWeaver` and `SealedTraitWeaver` take their inner weavers by name and resolve them in a lazy val on first use. Building a derived given no longer touches other givens, so no cycle among givens can re-enter an initializer. After first use the cost is one initialized-field read.
- The macro checks case classes before enums, because a parameterized enum case is a case class that also extends `scala.reflect.Enum`.
- An enum whose cases are all singletons still encodes as its case name (`"Row"`). An enum with any parameterized case uses the sealed-trait format: an `"@type"` discriminator alongside flat fields, with singletons as `{"@type":"Row"}`.
- A child without a given is derived inline, because enum cases can't carry `derives`. Plain classes and generic case classes still fail with "No Weaver found for child type ...".
- Singleton children (case objects and enum cases without parameters) use `SingletonWeaver`, so they need no given of their own.
- The discriminator comes from `productPrefix`. `getClass.getSimpleName` is empty for enum singleton cases, which are anonymous classes.
- `RecursiveDerivedGivenInitTest` (JVM) forces first initialization on a daemon thread with a timeout, so a regression fails instead of hanging CI.

## Alternatives

- A `lazy val self` emitted by the macro for self-references: it covers only self-recursion, not cycles that cross companions (trait ↔ child given).
- Routing recursive fields through `Weaver.fromSurface` and its `LazyWeaver`: it needs a runtime Surface lookup, and Surface-driven codecs don't know enum cases with fields.
- A second sum encoding for enums (`[name, fields]`): one more format to learn, different from sealed traits.

## Consequences

- `Weaver.fromSurface` still treats every enum as a string enum, because `EnumSurface` has no case information. A singleton of an enum with parameterized cases encodes differently on the derived path (`{"@type":"Zero"}`) and the runtime path (`"Zero"`) until Surface records enum cases.
- `CaseClassWeaver` and `SealedTraitWeaver` constructor parameters are by-name. Callers still compile, but binaries compiled against the old constructors need recompiling.
- The uni-ui Dock could move to a derived codec. Its JSON (`{"type":"split",...}`) uses a different discriminator name and lowercase case names, so it would need a config or a format migration.

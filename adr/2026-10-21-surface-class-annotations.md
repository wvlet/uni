# Surface records class-level annotations

## Context

OpenAPI component schemas need the class's `@description`, as sbt-airframe emits it and apps migrating from Airframe (td-llm-proxy's `model.*` types) depend on it. Surface already captured method and constructor-parameter annotations at compile time, but not the class's own. Design note: `plans/2026-10-21-surface-class-annotations.md`.

## Decision

- `Surface` has `annotations`, `findAnnotation`, and `hasAnnotation`, empty by default. Only `GenericSurface` built by `genericTypeWithConstructorFactory` (classes with a public constructor) fills it. Traits, enums, and `RecordSurface` stay empty. `Alias`, `LazySurface`, and `TaggedSurface` forward to the type they wrap.
- `GenericSurface` keeps an auxiliary 4-argument constructor `(rawType, typeArgs, params, objectFactory)`. Code inlined by an earlier `Surface.of` macro in downstream jars calls that signature, so removing it breaks linking even though nothing in this repo calls it directly.
- Class annotations leave out `scala.annotation.internal.*`. `@SourceFile` would otherwise put the build machine's absolute source path into every generated surface, and `@Child` is sealed-hierarchy bookkeeping. Method and parameter extraction keep all annotations, as before.
- A symbol with no readable annotations generates `Seq.empty`, so unannotated classes cost nothing extra.
- Annotations come back in the compiler's order (reverse source order), the same as for parameters. Don't rely on the order.
- `JsonSchema.Options.classDescriptions` defaults to `false`, so MCP tool schemas stay unchanged. Only OpenAPI turns it on. `description` is matched by simple name and its `value`, so Airframe's `@description` also works.

Rejected alternatives:

- A separate `annotationsOf[A]` macro: OpenAPI walks nested surfaces at runtime and has no static type to ask about.
- A field on `RecordSurface`: it is a public case class, and the field would change its `apply`/`copy` signatures with no consumer yet.

## Consequences

- Adding a class-level annotation now changes that class's generated surface code; equality and `surfaceCache` keys stay `fullName`-based.
- Extending annotations to traits or enums later needs no API change, only the macro paths that build those surfaces.

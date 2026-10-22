# Class-level annotations in Surface, and class descriptions in OpenAPI

## Goals

- Emit a component schema's class description in uni's OpenAPI output, as sbt-airframe does. Then a service migrating from Airframe (td-llm-proxy's `doc/openapi.yaml` uses class-level `@description` on its `model.*` types) gets the same document.
- To do that, make Surface record a class's own annotations, the way it already records method and constructor-parameter annotations.
- Keep MCP tool schemas byte-for-byte unchanged.

## Background

- `CompileTimeSurfaceFactory.extractAnnotations` (`CompileTimeSurfaceFactory.scala:961`) reads annotations and their literal arguments at compile time, so no runtime reflection is needed and it works on JVM, Scala.js and Native. It is only called for constructor parameters (`:924`) and methods (`:1440`).
- `Surface` has no `annotations` member. `Parameter` and `MethodSurface` have `annotations`, `findAnnotation(name)` and `hasAnnotation(name)`, and `Annotation.is` matches by simple or full name.
- PR #744 matched the method-level `@description` by simple name and read its `value` through `mcp.description.value` (`private[uni]`), so Airframe's `@description` works while a service keeps both annotations during its migration. This change follows the same rule.
- OpenAPI builds component schemas through `JsonSchema.objectSchemaOf(s, seen, schemaOptions)` (`OpenAPI.scala:318`). MCP calls the same function with the default `JsonSchema.Options()`.
- MiMa is not enforced in the build (`build.sbt:125`), but code compiled by an older uni macro calls `GenericSurface`'s 4-argument constructor. A downstream jar with inlined `Surface.of[...]` must keep linking against the new uni.

## Design

Lane: **Standard**: it changes the core Surface macro and the public `Surface` API.

### Surface API

- Add to `trait Surface`, with the same names as `Parameter` and `MethodSurface`:
  - `def annotations: Seq[Annotation] = Seq.empty`
  - `def findAnnotation(name: String): Option[Annotation]`
  - `def hasAnnotation(name: String): Boolean`
- `GenericSurface` gets a fifth constructor parameter, `annotations: Seq[Annotation] = Seq.empty`, which overrides the trait member.
  - Binary compatibility: keep an auxiliary constructor with the old 4-argument signature that passes `Seq.empty`. Code compiled by an older macro still links. Subclasses (`Alias`, `HigherKindedTypeSurface`, `ArraySurface`, `PrimitiveSurface`, …) keep calling the 4-argument form.
- Forwarding wrappers return their target's annotations: `Alias` and `LazySurface` (`ref.annotations`) and `TaggedSurface` (`base.annotations`). Then `type ModelX = model.X` and recursive types still find the description. `RecordSurface` and the other surfaces keep the empty default. `RecordSurface` is a public case class, so a new field would change its `apply`/`copy` signatures.
- `equals`/`hashCode` stay based on `fullName`, so annotations do not affect surface identity or the `surfaceCache` key.

### Macro

- Only `genericTypeWithConstructorFactory` passes `annotations = extractAnnotations(t.typeSymbol)`. That factory covers case classes and classes with a public constructor, which are the types that become OpenAPI component schemas. Traits, abstract classes, enums and primitives are left out for now. They can be added later without an API change.
- Filter out compiler-internal annotations (`scala.annotation.internal.*`, e.g. `@SourceFile`, `@Child`). Otherwise every surface would carry noise, and `@SourceFile` would put the build machine's absolute source path into the generated code. The filter applies to the class-symbol call only. Method and parameter extraction stay as they are.
- When the filter leaves nothing, emit the `Seq.empty` default instead of `Seq()`, so classes without annotations generate the same code as today.

### Cost

- Runtime memory: one `Annotation` (a name, a full name and a small `Map`) per annotation on an annotated class. Each surface is built once and cached in `Surface.surfaceCache`. Classes without annotations cost nothing.
- Compile time and code size: one extra walk over a class symbol's annotation list per generated surface, which is a small fraction of the existing per-parameter work. Generated code grows only for annotated classes.
- Cross-platform: the macro emits only literals and `Annotation(...)` construction, with no `Class.forName` or reflection, so JVM, Scala.js and Native behave the same. Tests run on all three via the existing surface test suite.

### JsonSchema / OpenAPI

- `JsonSchema.Options` gets `classDescriptions: Boolean = false`, documented next to `formats` and `defaults`.
- When the flag is on, `objectSchemaOf` adds `"description"` from `description.value(c.dealias.findAnnotation("description"))` after `"type"`, following Airframe's `Schema` field order (as Airframe's `OpenAPIGenerator` does). This also covers object schemas that are inlined through `ofSurface`.
- `OpenAPI` sets `classDescriptions = true` in its `schemaOptions`. MCP keeps `Options()`, so MCP tool schemas do not change.
- A property's own `@description` still wins for that property. A `$ref` property is not merged with the class description.

### Tests

- Surface (`AnnotationTest`, run on JVM, JS and Native): a case class with `@description("...")` and a custom annotation exposes both through `Surface.of[X].annotations` and `findAnnotation`, with their literal parameters. An unannotated class has empty `annotations`. No `scala.annotation.internal` entries appear. An alias of an annotated class forwards its annotations.
- JsonSchema: the class description appears only with `classDescriptions = true`. The default `Options()` (MCP) output is unchanged.
- OpenAPI: a component schema carries the class description from both uni's `@description` and a test-local annotation named `description` with a `value` (standing in for Airframe's).

## Alternatives and Why Not?

- **A separate `Surface.annotationsOf[A]` macro or registry.** No Surface API change, but OpenAPI walks nested surfaces it reaches at runtime. It never sees the static type `A`, so it could not ask for that type's annotations.
- **Record annotations on every surface kind (traits, enums, primitives, `RecordSurface`).** More complete, but it widens the macro change and breaks `RecordSurface`'s case-class signature, with no current consumer. The trait default leaves room to add them later.
- **Always emit class descriptions in `JsonSchema`.** Simpler, but it changes MCP tool schemas, and so the token cost and tool surface MCP clients see. The flag keeps MCP unchanged.
- **Keep all annotations, including `scala.annotation.internal.*`.** This matches what methods and parameters do. But on a class symbol these are the compiler's bookkeeping, not user metadata, and `@SourceFile` leaks build paths.

## Out of scope

- MessagePack response content types in OpenAPI (a separate gap).
- Any td-llm-proxy changes.

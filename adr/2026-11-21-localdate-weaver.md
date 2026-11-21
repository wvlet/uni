# LocalDate Weaver on every platform

## Context

`java.time.LocalDate` had a Weaver only in the JVM-only, opt-in `JvmWeaver`. Derivation without that import and `Weaver.fromSurface` did not find it, so a `LocalDate` field fell back to the lossy empty object and packed as `{}`, which could not be read back. As a result uni-parquet could not write case classes with DATE columns or DATE partitions, and MCP rejected tools with `LocalDate` parameters. `LocalDate` exists in shared code on every platform: Scala.js has `scalajs-java-time`, and Scala Native has `sjavatime`.

## Decision

- `PrimitiveWeaver.localDateWeaver` is shared, exported from `Weaver`, and registered in `fromSurface`'s primitive factory, so `derives Weaver`, `summon`, and Surface-based weavers (RPC, HTTP, MCP) agree.
- It packs an ISO-8601 string (`"2026-11-21"`), the format `JvmWeaver` already used. JSON stays readable and needs no extension type.
- Unpacking also accepts an integer as days since 1970-01-01, the form Parquet DATE stores, just as `Instant` also accepts epoch millis.
- `JvmWeaver.localDateWeaver` forwards to the shared weaver, so `import JvmWeaver.given` code keeps compiling. The other `JvmWeaver` java.time types stay JVM-only.
- uni-parquet's `SurfaceSchema` maps `LocalDate` to DATE (INT32). Partition values still read as strings unless `withPartitionType` declares them; the weaver parses the string when reading into a case class.

Rejected alternatives: epoch-day integers on the wire (unreadable JSON, and a change from `JvmWeaver`'s format); a MessagePack extension type (no standard date extension, and JSON would still need a string).

## Consequences

- The wire format changes from `{}` to an ISO string wherever a `LocalDate` was encoded through `fromSurface` or derivation: JSON, RPC, HTTP request mapping, and router results. The old output could not be decoded, so no working client depended on it.
- Router results whose case class has a `LocalDate` field now use the weaver instead of the toString fallback, and MCP accepts such tools.
- `LocalTime`, `LocalDateTime`, and the other `JvmWeaver` types still lack a shared weaver. `LocalTime` → Parquet TIME follows the same pattern.

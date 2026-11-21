# LocalDate Weaver on every platform

## Goals

- `case class Event(dt: LocalDate, ...)` packs `dt` as an ISO-8601 string ("2026-11-21") on JVM, Scala.js, and Scala Native, through both `derives Weaver` and `Weaver.fromSurface`.
- Parquet case-class writes accept `LocalDate` fields as DATE columns, including DATE partition columns, and read them back into case classes.
- The Parquet docs no longer need the "use `dt: String` for case-class partitions" workaround.

## Background

- A `LocalDate` codec already exists, but only as the opt-in `JvmWeaver.localDateWeaver` (JVM only, needs `import JvmWeaver.given`). Neither `Weaver.fromSurface` nor derivation without that import sees it, so the field falls back to the lossy empty object `{}`. Unpacking `{}` back into `LocalDate` fails.
- `LocalDate` itself is cross-platform: uni-core already uses it in shared code, Scala.js gets it from `scalajs-java-time` and Scala Native from `sjavatime` (both already dependencies).
- Parquet is nearly ready: `ValueConverter` already turns an ISO date string into a DATE value, and the reader already gives DATE back as an ISO string for case-class unpacking. Only `SurfaceSchema` has no `LocalDate` → DATE rule.
- Who notices the change:
  - JSON/MessagePack: a `LocalDate` field now encodes as `"2026-11-21"` instead of `{}`. Nobody could round-trip the old output, so this fixes a bug rather than breaking a format.
  - HTTP router: case-class results with a `LocalDate` field were skipped by `fromSurfaceOpt` and went through the toString fallback. They now encode as proper JSON objects.
  - RPC and HTTP request mapping: parameters, bodies, and return values that call `fromSurface` directly also change from `{}` to the ISO string.
  - MCP: tools with `LocalDate` parameters or results were rejected at registration time. Now they are accepted.

## Design

- Lane: Standard. The change is user-visible in the JSON/RPC encoding of a core type.
- Move the `LocalDate` Weaver into shared `PrimitiveWeaver` (string-based: `toString` / `LocalDate.parse`) and register it in `fromSurface`'s primitive factory, so derivation and surface-based lookup agree on all platforms.
- Export it from `Weaver`, like `instantWeaver`, so `Weaver.of[LocalDate]`, `toJson`, and derivation find it without an import.
- Unpacking also accepts an integer as epoch days. This matches Parquet's DATE storage and the timestamp-as-epoch-millis leniency that `Instant` already has.
- Keep `JvmWeaver.localDateWeaver` as a forwarder to the shared given, so existing `import JvmWeaver.given` code compiles unchanged. The other JVM extras (`LocalDateTime`, `ZonedDateTime`, `Path`, …) stay where they are.
- `SurfaceSchema` maps `LocalDate` to DATE (INT32), so case classes get DATE columns and DATE partition folders (`dt=2026-11-21`).
- Reading back: partition values stay strings unless the caller declares a type (`withPartitionType`). Unpacking into a case class works either way, because the Weaver parses the ISO string. The roadmap's next item, typed partition hints, removes the need to declare the type.
- Docs: update `docs/core/weaver.md` (LocalDate becomes a built-in type), `docs/text/parquet.md` and the skill's `references/parquet.md` (case-class partitions can use `LocalDate`), `for-ai-agents.md`, and the `uni` skill. Bump the plugin version.
- Fix statements that will no longer be true: the "no LocalDate Weaver" notes in the parquet-dataset-writer and pure-scala-parquet-reader ADRs, the LocalDate example in the fromSurfaceOpt ADR, and the RouterHandler comment. Add a short ADR on the wire format.
- Tests: Weaver round-trips on all three platforms (`derives`, `fromSurface`, JSON, epoch-day input, a bad string giving an error), one RPC round-trip, Parquet case-class write/read with a `LocalDate` field, and a DATE-partitioned case-class dataset read with and without `withPartitionType`.

## Alternatives and Why Not?

- Pack as epoch-day integer: compact, but JSON becomes unreadable and differs from the existing `JvmWeaver` format.
- Pack with a MessagePack extension type (like `Instant`'s timestamp): there is no standard date extension, so JSON would still need a string.
- Also add `LocalTime` → TIME now: the reader already returns TIME as an ISO string, but it is a second wire-format change; easy follow-up.
- Move every `JvmWeaver` java.time type to shared code: larger wire-format change with no Parquet need today. Can follow separately.

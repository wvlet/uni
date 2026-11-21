# Parquet datasets: widen and evolve nested columns across files

## Goals

- Read a dataset whose files disagree inside a nested column: a struct field that widened from INT32 to INT64, a list of FLOAT that became a list of DOUBLE, a map whose values widened, or a struct that gained a field in newer files. Today any such difference fails with "Column x has different types", so adding one field to a struct makes the whole history unreadable.
- Match what Spark's `mergeSchema` and DuckDB's `union_by_name` do for structs: the dataset struct is the union of the files' fields, and a field missing in a file reads as null.
- Keep errors for changes that lose data or meaning, now naming the nested path (`user.age`) instead of only the top-level column.
- The next step toward a dataset API on par with Spark and DuckDB, after top-level widening (#1058).

## Background

- `buildPlan` unions top-level fields by name. A column that differs between files must be `sameType` (exact nested shape) or pass `widen`, which handles only top-level numeric primitives.
- Top-level columns already evolve: a column some files lack becomes OPTIONAL and reads as an all-null vector (`nullVector`).
- Each file is read with its own schema; `toOutput` maps its vectors onto the dataset schema. Nested values arrive as `StructColumn`, `ListColumn`, and `MapColumn` trees, so a tree walk can rebuild them in the dataset types without re-reading.
- Filters (`RowGroupFilter`) and `withColumns` address top-level columns only, so they are unaffected.

## Design

Lane: Standard. It changes which datasets read and how their schemas look, and it updates the docs and the uni skill.

User flow:

- A pipeline wrote `user: struct<name, age: INT32>` until last month and `user: struct<name, age: INT64, email>` since. `Parquet.dataset(root).schema` now shows `user` as `struct<name, age: INT64, email (optional)>`, and every file reads; old rows have `email = null`.
- `readAs[A]` maps the merged struct onto a case class as usual: an `Option` or defaulted field receives the missing value as null or the default.
- An incompatible change, such as `user.age` STRING in one file and INT64 in another, still fails before any rows are read, naming the nested path, both files, and both types.

Decisions:

- **One recursive merge** replaces `sameType` plus `widen` when files disagree:
  - Primitives: equal, or the existing lossless numeric widening (INT8/16/32 → INT32/INT64/DOUBLE, FLOAT → DOUBLE).
  - Structs (groups without a LIST or MAP annotation): fields union by name in first-seen order; each shared field merges recursively; a field some files lack becomes OPTIONAL.
  - Lists: match by element, ignoring the wrapper names (`list.element`, `list.item`, `bag.array`) and the layout (3-level or legacy 2-level), as the reader already does. The element merges recursively. The dataset keeps the first file's wrapper names, and takes the 3-level layout when files mix 3-level and 2-level lists (a 2-level element counts as REQUIRED).
  - Maps: match by key and value, ignoring the `key_value` wrapper name. Keys and values merge recursively; keys stay REQUIRED.
  - REQUIRED and OPTIONAL merge to OPTIONAL at any depth; REPEATED must match.
  - A missing REPEATED field inside a struct (a legacy repeated primitive) reads as an empty list, as missing top-level repeated columns do today.
- **Always on**, like top-level union and widening: every rule keeps values unchanged, and the old error had no workaround except leaving the whole column out.
- **Conversion happens in `toOutput`**: a file's vector is rebuilt only where its field differs from the dataset field — leaves are widened, struct children are reordered by name with null vectors of the struct vector's own size (inside a list, the element count) for missing fields, and list or map children are converted in place. Files that match the dataset schema pass through without copying.
- **Errors name the path and the file that set each conflicting type**, e.g. `Column user.age has different types in a.parquet (INT64) and b.parquet (BYTE_ARRAY STRING)`.
- **Tests** cover struct field widening, struct evolution (added field, reordered fields, in both file orders), list element and map value widening, lists from different writers (`element` vs `item` vs 2-level), a list of evolving structs with a missing field, `readAs` into a case class with an `Option` field, and the remaining errors (incompatible leaf, REPEATED vs not).
- **Docs**: the "Not covered" and "Everything else must still match exactly" notes in `adr/2026-11-21-parquet-datasets.md`, the "Columns across files" text in `docs/text/parquet.md`, `docs/guide/for-ai-agents.md` if it lists the rules, the skill reference, a plugin version bump, and an ADR with its index entry.

## Alternatives and Why Not?

- **An opt-in option such as `withMergeSchema()`, as Spark defaults it off**: Spark defaults it off for the cost of reading every footer, but Uni already reads every selected footer, and top-level union is already always on.
- **Positional struct matching**: field order changes between writers are common; Spark and DuckDB match by name.

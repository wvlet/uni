# Parquet datasets: widen column types across files

## Goals

- Read a dataset whose files store the same column with a narrower or wider numeric type, such as INT32 in older files and INT64 in newer ones, as Spark's schema merging and DuckDB's `union_by_name` do. Today this fails with "Column x has different types in A and B", so one schema change in a writer makes the whole history unreadable.
- Keep type errors for changes that would lose data or meaning, such as INT64 to INT32, STRING to INT64, or DATE to TIMESTAMP.
- The third step toward a dataset API on par with Spark and DuckDB, after the writer (#1054) and globs and file lists (#1056).

## Background

- `buildPlan` unions the top-level fields of the selected files by name. Two files that disagree on a field's physical or logical type are an error (`sameType`).
- Each file is read with its own schema, and `toOutput` maps the file's vectors onto the dataset schema. Missing columns already become all-null vectors there.
- `RowGroupFilter` compares integer statistics as `Long`. A Double literal against INT32 statistics gives no key, so the row group is kept ("may match"). Filters stay correct on narrow files.

## Design

Lane: Standard. It changes which datasets read successfully and how their schemas look, and it updates the docs and the uni skill.

User flow:

- A user's pipeline wrote `amount` as FLOAT until last month, and as DOUBLE since. `Parquet.dataset(root).schema` now shows `amount` as DOUBLE, and every file reads.
- `readAs[A]`, `readRows`, `readJson`, and `readBatches` see the wide type for every file: a `LongColumn` or `DoubleColumn`, and `Long` or `Double` values.
- A change that can't widen still fails before any rows are read, naming both files and both types as today.

Decisions:

- **Widening rules**, for top-level primitive columns only:
  - INT8, INT16, and INT32 (signed) widen to INT32 or INT64.
  - FLOAT widens to DOUBLE.
  - INT32 (signed) widens to DOUBLE, because every 32-bit integer fits exactly. INT64 does not widen to DOUBLE, because large values would lose precision.
  - Everything else must match exactly, as today. That includes unsigned integers, DECIMAL, dates and timestamps, strings and binary, and nested groups.
- **The dataset type is the widest one** among the selected files, so the schema still depends only on the files a read selects, as the union does today.
- **Conversion happens in `toOutput`**: a narrow file's vector is copied into the wide vector type with the dataset's field, so `getValue` applies the wide logical type. Files that already have the wide type pass through without a copy.
- **Filters need no change**: integer literals compare as `Long` against both widths, and a Double literal on narrow integer statistics keeps the row group.
- **Always on**: there is no option. A widening rule never changes a value, and the error it replaces had no workaround besides leaving the column out.
- **Tests** cover each widening pair through `readAs`, `readRows`, and `readBatches`, files read in both orders, a filter over mixed widths, and the remaining errors (INT64 to DOUBLE, INT64 to INT32 order independence, STRING vs INT64).
- **Docs**: the "Columns across files" bullet in `docs/text/parquet.md`, the skill reference, and the plugin version bump.

## Alternatives and Why Not?

- **An opt-in option**: every rule here is lossless, and Spark and DuckDB widen by default too.
- **Widen INT64 to DOUBLE, as DuckDB does**: values above 2^53 would change silently. Callers who want that can read the column per file.
- **Widen nested fields too**: the vector copy would have to walk lists, maps, and structs. Top-level columns cover the common schema changes, so nested widening can follow if needed.

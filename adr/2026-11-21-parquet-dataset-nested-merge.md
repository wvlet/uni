# Parquet datasets: merge nested column types across files

## Context

Datasets union top-level columns by name and widen top-level numeric columns losslessly
([datasets](2026-11-21-parquet-datasets.md)). A difference anywhere inside a struct, list, or map
still failed with "different types", so adding one field to a struct made a dataset's history
unreadable. Spark's `mergeSchema` and DuckDB's `union_by_name` merge such schemas.

## Decision

- One recursive merge (`ParquetDataset.mergeField`) computes the dataset type of each column:
  - Primitives match, or widen by the top-level rules (signed INT8/16/32 to INT32, INT64, or DOUBLE;
    FLOAT to DOUBLE; never INT64 to DOUBLE).
  - Struct fields union by name in first-seen order. A field some files lack becomes OPTIONAL and
    reads as null, or as an empty list when it is REPEATED.
  - Lists merge their elements, and maps their keys and values, by position. Writers name the
    wrapper groups differently (`list.element`, pyarrow's `list.item`, the legacy 2-level
    `bag.array`), and the reader already ignores those names. When files mix the 3-level and
    2-level layouts, the dataset takes the 3-level one; a 2-level element counts as REQUIRED.
  - REQUIRED and OPTIONAL merge to OPTIONAL at any depth; REPEATED must match.
- Merging is always on, like the top-level union. Every rule leaves values unchanged, and the
  error it replaces had no workaround but leaving the whole column out.
- `toOutput` rebuilds a file's vector only where its field differs from the dataset field
  (`conformField`, which mirrors how `Assembly` builds fields and values). Struct children are
  looked up by name, with null vectors of the struct vector's own size for missing fields, which
  inside a list is the element count. Leaves are copied only when they widen.
- Errors name the nested path and the file that set each conflicting type. The merge records the
  file per nested path when a file widens a type or adds a field; other paths fall back to the
  closest recorded parent.

## Consequences

- Filters and `withColumns` address top-level columns only, so they need no change.
- A legacy 2-level list whose repeated group has one child reads as a 3-level list (Parquet's
  backward-compatibility rule), so such a struct gaining a second field changes the layout. The
  merge handles that as a 3-level vs 2-level mix.
- An opt-out (strict schemas) can be added later as a dataset option if writer bugs hidden by
  null filling become a concern.

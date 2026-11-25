# Parquet row filters compare DECIMAL columns stored as integers

- Status: Accepted
- Date: 2026-11-25
- Plan: `plans/2026-11-25-parquet-decimal-filter.md`
- Amends: [`2026-11-21-parquet-row-filter.md`](2026-11-21-parquet-row-filter.md) (DECIMAL was
  rejected) and [`2026-11-24-parquet-late-materialization.md`](2026-11-24-parquet-late-materialization.md)
  (its "can't compare DECIMAL" consequence)

## Context

TPC-H q1, q6, and q19 test `l_quantity` and `l_discount`, which are DECIMAL(15, 2) stored as INT64.
`ParquetFilter` had no order for DECIMAL, so the wvlet engine kept those conjuncts and q6/q19
decoded every payload column for rows a quantity or discount test would drop. Writers store
DECIMAL with precision up to 9 as INT32, up to 18 as INT64, and above that as a fixed-length or
variable-length byte array.

## Decision

- **INT32 and INT64 decimals compare by unscaled value**, the Long the reader already puts in
  `IntColumn` / `LongColumn`. Statistics use the same order (signed, as the spec defines), so row
  groups are skipped by them and `columnStatistics` now reports these columns' min and max.
- **Literals are exact.** A `BigDecimal` (Scala or Java), an integer, or a decimal string is
  scaled to the column's scale. One with more fractional digits lies between two unscaled
  values, so it becomes the same floor-plus-rest key a sub-unit `Instant` uses for TIMESTAMP:
  `col("d") < BigDecimal("0.055")` on scale 2 keeps 0.05 and drops 0.06. A literal beyond every
  Long is above or below every value.
- **`Double` and `Float` literals are rejected.** `0.1` has no exact binary value, and guessing a
  decimal for it (shortest representation or the exact binary expansion) would make results
  depend on that guess. Callers pass a `BigDecimal` or a string.
- **Byte-array decimals (precision above 18) stay unsupported** and fail at read start as before.
  Comparing them needs a big-endian two's-complement compare per row; no current engine query
  needs it.

## Consequences

- The engine can hand q6's `l_discount` / `l_quantity` and q19's quantity ranges to the
  filter-first steps.
- `columnStatistics` min/max for INT32/INT64 DECIMAL columns change from `None` to `BigDecimal`
  values.

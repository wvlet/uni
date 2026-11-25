# JIT-friendly ColumnVector.take and DECIMAL in Parquet row filters

## Goals

- Filter-first reads (#1189) spend less CPU in `ColumnVector.take`: in the wvlet engine's TPC-H q1
  profile it is ~26% of CPU, because its copy loops run inside constructor arguments, where
  HotSpot can't enter compiled code mid-loop (OSR, "stack not empty at OSR entry point").
- `ParquetFilter` compares DECIMAL columns, so TPC-H q6/q19 `l_quantity` and `l_discount`
  conjuncts can run as filter-first steps.

## Design

Lane: Express — the direction comes from the work item, and the engine optimization loop allows
auto-merge after green CI and review.

- **take.** Bind each picked array (and child vector) to a local before calling the vector's
  constructor, as wvlet #2319 did. The same pattern in `Assembly.gatherLeaf` (BOOLEAN) too.
- **DecimalOrder** for INT32/INT64 DECIMAL: unscaled Long compare. Literal keys reuse the
  TIMESTAMP floor-plus-rest key, so literals finer than the scale compare exactly. Statistics use
  the same order. `Double` literals and byte-array decimals are rejected.
- FLOAT/DOUBLE were already supported by `ParquetFilter`; nothing to add there.
- Tests: INT32 and INT64 decimals with nulls, both read paths, `int32DecimalAsLong` on and off,
  sub-scale literals, out-of-range literals, statistics pruning, rejected literals.
- `FilterProbe` gets q6d/q19d scans with the DECIMAL conjuncts and a first-round time.

## Alternatives and Why Not?

- **Make `pick` a plain method.** Also fixes OSR, but every array type would need its own copy
  (no ClassTag-generic primitive loop without boxing); binding a local keeps the inline loop.
- **Accept Double literals via their shortest decimal string.** Results would depend on that
  conversion; engines hold decimal literals as BigDecimal anyway.

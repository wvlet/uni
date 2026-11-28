# Compare results as bags in uni-test

## Goals

- Let a test check that two sets of rows match in any order, with the rules query results need: NULL equals NULL, floats within a tolerance, numbers equal across types, order checked only when the result is ordered.
- Serve Uni's own Parquet, Iceberg, and Delta tests first: they read datasets whose row order depends on file listing and sort rows by hand before `shouldBe`.
- Give Wvlet the comparison its query oracles (TLP, NoREC, rule ablation, DuckDB differential) build on; those oracles live in Wvlet.

## Background

- About 60 dataset tests sort before comparing (`sortBy(_.id)`, `sortBy(_("id").asInstanceOf[Long])`, `sortBy(_.toString)`). The sort key is noise, and `readRows` maps need casts to sort at all.
- Wvlet's `EngineConformance` compares its engine with DuckDB by sorting rows on a `%.6e` string key and comparing with relative 1e-9 / absolute 1e-12 tolerance. The generic part fits uni-test; Wvlet keeps its value normalization (dates, nested values) and the logic oracles.
- The fuzz reducer keeps a failure while it throws the same exception class, so a mismatch needs its own class to avoid sliding into an unrelated assertion during reduction.

## Design

Lane: Standard — new public uni-test API. Direction set by the user: Uni gets what Parquet and other Uni tests can use; TLP, NoREC, ablation, generators, and DuckDB adapters go in Wvlet.

- **`actual shouldBeInAnyOrder expected`** on any `Iterable`: the common case for dataset tests, with the default rules below. Replaces sort-then-`shouldBe`.
- **`ResultCompare` with `ResultCompareConfig`** in `wvlet.uni.test.result` for the other cases; `ResultCompare.diff` returns an optional readable diff (missing and extra rows, first few of each, counts), `ResultCompare.assertSame` throws it.
  - Rows are any values: case classes, `Seq[Any]`, `Map[String, Any]` from `readRows`, or scalars.
  - Bag by default: same rows with the same multiplicities.
  - `withOrdered`: the order is fixed. `withOrderedBy(key)`: rows must come in key order, and rows with equal keys (ties under ORDER BY) compare as a bag.
  - `withRowCountOnly`: for results whose rows are not determined (LIMIT without a total order, sampling).
  - NULL (`null` or `None`) equals NULL; NaN equals NaN; `-0.0` equals `0.0`.
  - Numbers compare by value across types (`Int` 1 = `Long` 1 = `BigDecimal` 1); when either side is floating, within absolute or relative tolerance (defaults as Wvlet's: 1e-12 and 1e-9; `noFloatTolerance` for exact).
  - Nested values (`Seq`, `Array`, `Map`, case classes of the same type) compare element-wise with the same rules.
  - Tolerance is not transitive, so rows that are not equal exactly are matched one by one within a candidate group (bipartite), not by hashing rounded values.
  - `withNormalize(Any => Any)` maps engine-specific cells (a day count, a `java.sql.Date`) to a common form first.
- **`ResultMismatch extends AssertionFailure`**, so a mismatch inside `fuzzGen` reduces as its own bug.
- **Adopt it in Uni**: rewrite the sort-then-compare checks in a few dataset suites (Iceberg, Delta, Parquet dataset tests) where it removes casts or sort keys; leave sites where the sorted order is itself the point.
- **Docs and ADR**: a section in `docs/core/unitest.md`, the plugin skill's `references/testing.md` (version bump), the `for-ai-agents.md` testing row; a short ADR update in the fuzz ADR naming where the query oracles live and why.
- **Tests**: order, ties under a key, multiplicities, NULL, NaN, tolerance and its non-transitivity, mixed numeric types, nested values, normalize, row-count mode, and the diff text.
- **Follow-up in Wvlet**: `EngineConformance` on top of `ResultCompare`, TLP/NoREC with Wvlet's expression generator, rule ablation over `OptimizerOptions` with `firedRules` as candidates (via the public `Reduce.ddmin`), and a DuckDB differential adapter.

## Alternatives and Why Not?

- **TLP, NoREC, ablation, and a predicate AST in uni-test too (the first draft).** No Uni test uses them, and Wvlet would use its own AST; they stay in Wvlet until a second engine needs them.
- **Round floats to a fixed precision before hashing.** Wrong at rounding boundaries (0.4999999 and 0.5000001 land in different buckets).
- **Sort both sides and compare pairwise.** Needs an ordering over arbitrary values and still misaligns rows whose floats differ slightly.

## Preferences learned
- Context: query-engine oracles (result bags, TLP, NoREC, rule ablation). Chose: engine-independent versions in uni-test. User chose: only what Uni's own tests (Parquet etc.) can use goes in Uni — the result comparison; TLP/NoREC/ablation/generators in Wvlet. Principle: add a testing utility to Uni only when Uni's own tests use it.

# Fuzz testing in uni-test, with a corpus that replays on every run

## Context

`forAll` draws about 100 well-formed values per run and keeps nothing between runs. Parsers fail
on inputs that are almost valid: a truncated string, a length field near `Int.MaxValue`, a reserved
byte. Query engines such as Wvlet need fuzzing too (DuckDB runs SQLsmith-style generation and
SQLancer-style logic oracles). An earlier design note planned a separate `uni-fuzzer` module with
coverage guidance; this ADR records the first step that shipped.

## Decision

- `fuzz(target, config)(bytes => ...)` and `fuzzString` live in uni-test (`FuzzCheck`, mixed into
  `UniTest` like `PropertyCheck`), with `FuzzConfig`, `ByteMutator`, and `Reduce` in
  `wvlet.uni.test.fuzz`. A fuzz target is an ordinary test, so it runs in `sbt test` on JVM, JS, and
  Native with no extra task or module.
- Pure Scala, no libFuzzer, AFL, or Jazzer bridge: those need native instrumentation or a JVM agent
  that do not exist on Scala.js and Scala Native.
- A run replays seeds (in code, every platform) and `<corpusDir>/<target>/` files (where a file
  system exists), then mutates for `iterations` (default 1000) or a time budget. The replay is the
  regression suite: a crash file copied into the corpus is checked on every test run.
- Only exceptions accepted by `withExpectedCases` are rejections. Any other exception, including
  `OutOfMemoryError` and `StackOverflowError`, and a failed assertion, is a bug. Skip/pending/cancel
  still propagate as test control.
- On failure the input is reduced (ddmin, then zeroing bytes) while it fails with the same exception
  class, not any exception, so reduction does not slide to a different bug. It is written as
  `crash-<FNV-1a hash>` plus a `.report` (seed, crash id, stack). FNV-1a instead of SHA-1 because
  `MessageDigest` is missing on Scala.js and Native. The crash id (exception class plus first frame
  outside the framework) follows shiguredo/fuzzer and prepares for deduplication in long runs.
- `UNI_FUZZ_SECONDS`, `UNI_FUZZ_ITERATIONS`, and `UNI_FUZZ_SEED` override the budget and seed, so
  nightly jobs and replays need no code change.

## Consequences

- The first targets (MessagePack unpacking, MessagePack to JSON, JSON parsing) found four bugs in
  their first minutes: `0xC1` threw `MatchError` in `unpackValue` and both JSON writers; a bin 32
  length near `Int.MaxValue` overflowed `hasCapacity` and allocated 2 GB; an unterminated JSON
  string read past the end of the input (on Scala.js an unchecked read in optimized builds).
  `JSONScanner` now reads through a bounded `byteAt` that returns 0 past the end.
- No coverage feedback yet: the pool is the seeds and corpus, so deep states depend on good seeds and
  dictionaries. Planned next: target-reported features, a tree reducer, recursive AST generators,
  and query-engine oracles (result bags, TLP, NoREC).
- No hang detection: a target that loops forever blocks the test. A timeout needs a thread, which
  Scala.js lacks.

## Update: typed targets, slow inputs, nightly runs

- **`fuzzGen` decodes bytes with a `Gen`.** The generator gets a `scala.util.Random` whose bits
  come from the input bytes (`ByteRandom`), with 0 past the end, as Hypothesis and libFuzzer's
  `FuzzedDataProvider` do. Mutation, reduction, and corpus files stay byte-based, so typed targets
  need no second engine and no `Shrink`: lowering bytes toward 0 shortens lists and lowers numbers.
  Shrinking is coarser than `Shrink` (`chooseNum` maps 8 bytes through `%`), which is acceptable for
  a fuzzer that reports a value instead of proving minimality. A corpus file replays only while its
  generator is unchanged.
- **Reduction lowers bytes by binary search** instead of only zeroing them, then runs ddmin again.
  Zeroing a length byte usually stops the failure; lowering it finds the shortest length that
  still fails.
- **Slow inputs, not hangs.** `withSlowInputMillis` times each input after it finishes and fails
  only when a second run is also slow, so JIT and GC pauses do not count. It catches quadratic
  behavior on every platform; a true hang still needs a thread and stays out of scope.
- **Nightly job.** `.github/workflows/fuzz.yml` runs the targets in `uni/src/test/scala/wvlet/uni/fuzz/`
  for two minutes each with a fresh seed and uploads `target/fuzz/` on failure. It is not a required
  check.
- The new targets (YAML, CSV, TSV, cron, Markdown, and round trips over generated records and
  strings) found: content after `...` crashing the YAML parser; a lone `\r` making `writeStream`
  add a document; one-empty-field records written as blank lines; an out-of-range timestamp
  throwing `DateTimeException`; and unpaired surrogates in JSON strings written raw, which UTF-8
  cannot encode. `quoteJSONString` now escapes them as `\uXXXX`.

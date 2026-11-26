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

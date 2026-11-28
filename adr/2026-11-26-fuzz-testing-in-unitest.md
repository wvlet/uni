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
- **Nightly job.** `.github/workflows/fuzz.yml` runs every `*FuzzTest` suite of the `uni` module
  for two minutes each with a fresh seed and uploads `target/fuzz/` on failure. It is not a required
  check.
- The new targets (YAML, CSV, TSV, cron, Markdown, and round trips over generated records and
  strings) found: content after `...` crashing the YAML parser; a lone `\r` making `writeStream`
  add a document; one-empty-field records written as blank lines; an out-of-range timestamp
  throwing `DateTimeException`; and unpaired surrogates in JSON strings written raw, which UTF-8
  cannot encode. `quoteJSONString` now escapes them as `\uXXXX`.

## Update: recursive generators and round trips over JSON trees

- **`Gen.recursive(leaf, maxDepth)(branch)`** bounds a value by a branch budget (the generator
  size) and a depth cap, and halves the size for children so wide lists of subtrees stay small. A
  node is a leaf when `nextBoolean()` is false, so zero bytes give a leaf and reduction under
  `fuzzGen` shrinks trees toward leaves. `Gen.lzy` covers mutually recursive generators.
- Round trips over generated JSON trees found that `YAMLFormatter` was a lossy pretty-printer:
  empty objects were dropped, empty arrays and `|` blocks without a final line break changed value,
  and strings like `true` or `123` were left plain. It now delegates to the writer that
  `YAMLDocument` already uses (`YAMLNode.fromJSON`), so there is one set of quoting rules. That
  writer now also quotes keys that would not read back as strings (`"200":`, which OpenAPI tools
  read as a number) and YAML 1.1 booleans (`yes`, `no`, `on`, `off`), and writes block keys such
  as `/users/{id}` plain. The resolver merges only a plain `<<`, so a quoted `"<<"` key stays an
  ordinary key. This matters for `OpenAPI.toYAML`, where `security: []` means no authentication.
- Scala.js prints whole doubles without `.0`, so `JSONDouble(1e12).toJSON` read back as a
  `JSONLong` there only; `toJSON` now adds `.0`. Running the same targets on every platform is
  what caught it.

## Update: feature guidance without coverage

- Plain Scala on three platforms has no coverage instrumentation, so the runner uses features as a
  stand-in: each expected-exception class with its message (digits removed, so positions do not
  multiply features) and each `fuzzFeature(label)` call. An input with an unseen feature joins the
  pool (at most 1,024, then random replacement), and half of the mutations start from the 8 most
  recent such inputs, which keeps the search at the frontier.
- On a 4-byte keyword, guidance finds a match in about 5,000 to 100,000 inputs depending on the
  seed; unguided mutation does not find it in 200,000. Rejection messages work only as well as
  they differ: two positions that report the same message look like one state.

## Update: decoders, protocol readers, and split invariance

- New targets cover the Snappy, LZ4, LZ4 frame, zstd, gzip, DEFLATE, and Brotli decoders
  (`CodecException` only, plus compressor round trips), the HTTP/1.1 request reader, WebSocket
  frames, server-sent events, the terminal escape parser, the command-line tokenizer, and the
  cloud storage XML reader. Suites live next to the code they test when they need package-private
  access (`wvlet.uni.http`), so the nightly job selects `*FuzzTest` rather than one package.
- Incremental readers get a second oracle besides "no unexpected exception": the same bytes fed in
  one read, byte by byte, and in reads of 3 and 7 bytes must decode to the same events. The escape
  parser compares its byte input with the same input decoded as text.
- The codecs survived minutes of fuzzing; they already had corruption tests. The new targets found
  three bugs elsewhere: `&#;` in an XML response threw `NumberFormatException`; a line at the SSE
  length limit failed when a read ended between its CR and LF; and the command-line tokenizer
  dropped a line holding only a control character (`trim` strips it) and the character after an
  unknown escape inside double quotes (`"\q"`).

## Update: the Parquet reader

- `ParquetFuzzTest` lives in `wvlet.uni.cloud.parquet`, not `wvlet.uni.fuzz`, because the page
  decoders it calls are `private[parquet]`. The nightly `*FuzzTest` pattern from #1211 picks it up.
- Five targets, with `ParquetException` as the only accepted rejection: whole files (writer output
  under each codec plus small Apache and DuckDB fixtures, read with `readRows` and with `readAs`
  through `VectorUnpacker`); the footer alone, framed around a real file's pages so its offsets
  still point somewhere; page values (`ValueDecoder` for every encoding and type, including
  dictionary indices past a dictionary); and RLE/bit-packed levels. Page seeds are pages cut out
  of the seed files, so DELTA and BYTE_STREAM_SPLIT bodies come from the fixtures; a separate test
  checks that every encoding has a seed, since a silent seed failure would only exercise the
  first check.
- A round trip writes `fuzzGen` schemas (primitives and lists, maps, and structs nested through
  `Gen.recursive`, each optional or required) with random codecs, dictionary settings, and page
  sizes, and reads the rows back. It also checks that `VectorUnpacker` gives the values
  `RowPacker` packs, the two paths `readAs` picks between. This target accepts no exception at
  all: with `ParquetException` accepted, a writer that rejected every row would pass.
- Bugs found: a DELTA_BYTE_ARRAY or DELTA_LENGTH_BYTE_ARRAY page on a non-binary column threw
  `ClassCastException`; and a page header could claim any uncompressed size, which every codec
  allocated before decoding (2 GB from a 50-byte page). `Codecs.checkUncompressedSize` now bounds
  the claim by each format's largest expansion (Snappy 32x, LZ4 256x, DEFLATE 1032x, zstd 32768x)
  before a buffer is borrowed. Brotli can expand past any useful bound, so it stays unbounded.
  On Scala.js only, a corrupt gzip page escaped as a JavaScript `Error` from Node's zlib;
  `Gzip.decompress` now throws `IOException` there, as `java.util.zip` does on the JVM and Native.
- Not bounded: value and level counts. An RLE run encodes a million nulls in a few bytes, so a
  page's count cannot be checked against its size; the batch limit (`MaxArrayLength`) caps it.

## Update: text and tree reduction

- **`fuzzString` reduces by lines, then tokens, then bytes, and never lowers bytes.** Byte ddmin on
  text cuts through keywords, so most candidates fail with a different exception and are rejected;
  whole lines and tokens keep candidates lexically valid. Lowering would turn the identifiers that
  remain into NUL bytes. The passes split bytes, not decoded text, so input that is not valid UTF-8
  reduces byte for byte and the crash file replays exactly. Binary `fuzz` targets keep ddmin plus
  lowering.
- **`fuzzGen` takes an optional `Shrink`, reversing "typed targets need no `Shrink`" above.** Byte
  reduction cannot hoist a subtree: dropping bytes shifts every later draw, so a failure deep in a
  generated tree keeps its ancestors. `Shrink.tree(children, withChildren, local)` shrinks the
  decoded value (hoist a child, drop children, shrink in place, recurse) with the same failure rule
  as bytes: the same exception class, expected exceptions pass, a slow input runs twice.
- **The shrunk value is reported, not saved.** A `Gen` has no inverse, so the crash file, `.report`,
  and crash_id stay tied to the reduced bytes; the message adds `shrunk value:` and its exception.
- **`shrink` is an explicit parameter defaulting to `Shrink.none`, not a `using`.** The derived
  givens for `List`, `Option`, and tuples would otherwise switch value shrinking on for existing
  targets.
- **All passes share one step budget** (`maxReduceSteps`, at most 30 for a slow input), counted in
  the runner's predicate.
- `forAll` keeps its own shrink loop, which counts any exception as a failure.

## Update: result comparison, and where query oracles live

- **`ResultCompare` and `shouldBeInAnyOrder`** (`wvlet.uni.test.result`) compare results the way query
  results compare: in any order with the same multiplicities unless the config says ordered, NULL
  equals NULL, numbers by value across types, floats within 1e-12 absolute or 1e-9 relative (the
  bounds Wvlet's conformance tests use). Under `withOrderedBy(key)`, rows with equal keys (ties
  under ORDER BY) compare as a bag. `withRowCountOnly` covers results that are not determined.
- **Tolerance is not transitive**, so rows are not hashed by rounded values or sorted: exact
  matches pair up by a canonical hash, and the rest pair up within groups of the same shape by a
  bipartite matching.
- **`ResultMismatch` is its own `AssertionFailure` subclass**, because the reducer keeps a failure
  only while the exception class matches; a plain `AssertionFailure` would let a mismatch slide
  into any other failed assertion while reducing.
- **The logic oracles (TLP, NoREC), optimizer-rule ablation, predicate generators, and DuckDB
  adapters live in Wvlet, not uni-test.** Uni has no query engine, and Wvlet's own expression AST
  and `OptimizerOptions` are what those oracles drive. A testing utility joins uni-test when Uni's
  own tests use it: dataset tests (Parquet, Iceberg, Delta) read rows in file-listing order and
  sorted them by hand before `shouldBe`. `Reduce.ddmin` is already public for ablation.

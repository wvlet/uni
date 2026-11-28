# Check the HTTP reader against the Lean model on fuzz inputs

## Goals

- `HttpReaderFuzzTest` compares the reader's full outcomes with `spec/lean/UniSpec/HttpReader.lean` on every mutated input, not only on the checked-in `HttpReaderSpecCases`.
- Success measure: `python3 spec/lean/mutants/mutate_http_reader.py --fuzz` kills the 10 mutants that survive today (four limit off-by-ones, chunked-twice, chunked-on-http10, chunk-data-without-crlf, bare-cr-before-request-line, obs-fold, trailing-ws-kept), when the oracle is built.
- The fuzz seeds are the generated cases, so the model check on the seeds alone repeats `HttpReaderSpecTest`. The PR also reports the score of a one-off run seeded with a few plain requests only, to show what new inputs add.
- Lean stays dev-only (`adr/2026-11-18-lean-specs.md`): no sbt project, artifact, or required CI job needs Lean.

## Background

- Today fuzzing checks only properties that hold without the model: no exception, same outcomes for every read size, the no-smuggling rule (ported by hand to `HttpSmuggleModel.scala`), and three RFC 9112 rules.
- The 10 survivors change outcomes only where those properties say nothing: a limit boundary, a rejected header the reader now accepts, or a header value that differs. Only the model's exact outcomes catch them.
- Porting all of `HttpReader.lean` (818 lines) to Scala would be a second hand-written model to keep in sync. Running the Lean model itself avoids that.
- The fuzz runner calls the check once per input, thousands of times per target, so starting a process per input is too slow.

## Design

Lane: Express — test and dev tooling only; no change to library code or its behaviour, and no always-gate area.

- A new Lean executable, `http-reader-oracle`, in `spec/lean`. It takes the reader limits as arguments, reads one hex-encoded input per stdin line, and prints `readAll`'s outcomes for it on one stdout line: each outcome's text, hex-encoded since bodies hold any byte, separated by spaces. It runs until stdin closes.
- The outcome-to-text function moves from `GenHttpReaderCases.lean` into the model, so the generator and the oracle print the same outcome text.
- The oracle joins Lake's default targets, so the `Lean spec` PR job compiles it whenever `spec/lean` changes.
- `HttpReaderFuzzTest` starts the oracle once per run and compares the reader's one-read outcomes with the model's on every input. A mismatch fails like any fuzz failure, so the runner reduces the input and saves it to the crash corpus.
- Bodies compare as Latin-1 bytes, as the model reads them; the spec test's UTF-8 decoding would differ on any fuzzed byte above 0x7F.
- An oracle that dies or answers `error` is not a reader bug: the test stops asking it, finishes the run, and then fails with the oracle's error, so the runner never reduces or saves an input for it.
- Finding the oracle: `UNI_LEAN_HTTP_ORACLE` names the executable; otherwise the test looks for the default Lake output (`spec/lean/.lake/build/bin/http-reader-oracle`), relative to the repository root, the directory sbt runs tests in (the corpus path relies on it too). The skip message names the absolute path it looked at.
- Skipping cleanly: with no executable found, or on Scala Native, the test logs once that the model check is skipped and runs the other checks as today. When `UNI_LEAN_HTTP_ORACLE` is set but the file is missing, the test fails, so a job that meant to use the oracle cannot pass silently without it.
- The process bridge is a small generic helper in the test sources (start once, send a hex line, read a hex line, close at the end), so the MsgPack, Rx, or DOM models can reuse it later. This PR wires only the HTTP reader.
- `mutate_http_reader.py --fuzz` builds the oracle with `lake build http-reader-oracle` first and sets `UNI_LEAN_HTTP_ORACLE`, so the mutant score always includes the model check.
- The nightly `.github/workflows/fuzz.yml` job (not a required check) installs elan, builds only the oracle, and sets `UNI_LEAN_HTTP_ORACLE`, so the long sessions check the model. That adds about a minute per night, so no toolchain cache. Pull-request test jobs stay without Lean.
- Docs: `spec/lean/README.md` and the ADR get a short paragraph on the oracle; tests are the fuzz test itself plus the mutant score reported in the PR.

## Alternatives and Why Not?

- **Port `HttpReader.lean` to Scala, as `HttpSmuggleModel.scala` did.** A second model to keep in sync by hand, and a port's bugs hide the reader's.
- **Generate more cases from Lean instead.** Fixed inputs are what the survivors already slip past; fuzzing finds new inputs.
- **Build the oracle from sbt when it is missing.** Makes `./sbt test` depend on Lean being installed and adds minutes to test startup.
- **Run the oracle on Scala Native too.** Native's process support adds risk for no new coverage, since the model is the same and the JVM run already compares it.

# Check the HTTP reader for request smuggling on every fuzz input

## Goals

- Make `HttpReaderFuzzTest` catch framing bugs, not only crashes and read-size dependence: on every fuzz input, the reader must not frame a kept-alive request differently from any lenient parser in the Lean `HttpSmuggle` family.
- Measure the target with the planted bugs in `mutate_http_reader.py`, so we know which bug classes fuzzing finds on its own.

## Background

- Today the fuzz target checks two things: no exception, and the same outcomes for every read size. A reader that accepts a bare LF or lets the last `Content-Length` win passes both, since it is wrong the same way for every split. So the framing mutants in `mutate_http_reader.py` (18 planted bugs) should mostly survive it; only `HttpReaderSpecTest` (fixed Lean-generated cases) is known to kill them. The PR measures both.
- `HttpSmuggle.lean` defines 64 lenient head parsers (bare-LF lines, last/first duplicate `Content-Length`, TE or CL wins, whitespace before the colon, obs-fold, trailing whitespace) and proves `no_smuggling`: when the reader accepts a request and keeps the connection open, every member rejects it or finds the same head length and framing.
- That rule is checked today only on the generated cases and their one-edit variants. Fuzzing can check it on arbitrary mutated inputs.

## Design

Lane: Express — tests and a test script only; no change to the reader or any public API.

- **Port the family to Scala, in the test.** A direct transcription of `interp` from `HttpSmuggle.lean`, with its own small helpers (not the reader's), as the model requires so a shared-helper bug can't make both sides agree.
- **Observe the reader's framing without hooks.** Feed the input one byte per read and call `readRequest` with every body streamed: it returns right after the head, so the bytes pulled are the head length, and the stream's length (−1 for chunked) is the framing. A request without a body returns the same way with length 0.
- **The fuzz body checks the rule** for the first request of each input, next to the existing read-size check. A disagreement fails with the member's options, both framings, and the input.
- **Plus three RFC 9112 rules that hold for any input**, checked on every accepted request: `Connection: close` ends the connection, at most one Host field, no control bytes in the target. The first scoring run showed these mutants are not framing disagreements, so the family cannot see them; each rule is one line on the reader's output.
- **Mutant script gets a `--fuzz` mode** that runs `HttpReaderFuzzTest` (fixed `UNI_FUZZ_SEED`) instead of the spec test, so the score is the fuzz target's own.
- **Seeds stay as they are**, plus a measured variant in the PR: how many mutants the fuzzer kills when seeded only with the plain (non-smuggling) cases, i.e. whether mutation finds the smuggling inputs by itself.
- Limit off-by-one, chunked-body, and HTTP/1.0 mutants are out of reach of both (they need the full `HttpReader.lean` outcomes); the PR reports them as survivors for the Lean differential item rather than adding more oracles here.

## Alternatives and Why Not?

- **Expose head length from the reader (`private[http]` counter).** Works, but touches the hot path and the exact text the mutant script patches; the streaming trick needs no reader change.
- **Run the Lean model itself on fuzz inputs.** That is the separate "Check Scala code against Lean models on fuzz inputs" item, which needs a Lean runtime bridge.

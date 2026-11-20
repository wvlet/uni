# Lean specs: the next candidates (recoverWith, continuous flatMap/merge, parFlatMap, MessagePack timestamps)

## Goals

- Cover the four "Next candidates" in `adr/2026-11-18-lean-specs.md` with Lean models whose generated cases UniTest replays on JVM, JS, and Native.
- Find places where `RxRunner` and `OffsetPacker`/`OffsetUnpacker` disagree with their stated rules, as `RxRecover` and `RxLive` did, and fix the small ones.

## Background

- `RxLive.lean` drives live `RxVar`s with a script of `set`/`stop`/`fail`/`cancel` actions. `RxVar` delivers synchronously, so a script gives one event order on every platform. It covers only default mode.
- `recoverWith` (`RecoverWithOp`): on an input error that `f` accepts, it cancels the previous recovery stream and subscribes to `f(e)`, forwarding all of its events. It has no completion tracker, unlike `transformRx`.
  - It never cancels the input. An error that does not end the input (`var.map(parse)` throwing on one value) leaves the var live, so later input values interleave with the recovery stream's, and the sink can get two `OnCompletion`s.
  - RPC server streaming relies on `recoverWith` ending a stream after an `rpc-error` event (`adr/2026-11-06-rpc-server-streaming.md`), so a completion rule here must keep that working.
- Continuous mode (`runContinuously`, used by DOM rendering): `FanIn` (merge, parFlatMap) treats errors and `Stop` as transient there. `RxRecover.lean` covers continuous mode only for map/filter/take/recover.
- `parFlatMap` (`ParFlatMapOp` over `FanIn`): at most `parallelism` nested streams run; further input values wait in a FIFO queue, and a nested stream that ends hands its permit to the next waiting value.
  - Its event order depends on thread scheduling only when nested streams emit from other threads. With nested `RxVar`s, everything runs on the test thread, so the permit queue is deterministic.
  - In continuous mode a nested error neither ends the slot nor releases the permit (`FanIn.error` skips `end` when continuous), so a failed nested var holds its permit forever and queued values never start.
- MessagePack: `packTimestampEpochSecond` normalizes `(sec, nanos)` with `floorDiv`/`floorMod`, then picks timestamp 32, 64, or 96. `MsgPack.lean` leaves floats and timestamps out. Floats are written as a format byte plus the IEEE bits, big-endian. `unpackDouble` widens a float32, and `unpackFloat` narrows a float64.
  - The JVM writes raw bits (`floatToRawIntBits`); JS and Native use `floatToIntBits`, which turns every NaN into the canonical NaN. So a NaN payload is written differently per platform.

## Design

Lane: **Standard** — new models whose cases may expose `RxRunner` bugs, and the fix for one of them (the parFlatMap permit) is a behaviour choice.

- **Two PRs.** The Rx items and MessagePack share nothing. This PR does the Rx items; a second PR, from `origin/main` and in this same session, does MessagePack.
- **PR 1 extends the live-var model; it adds no new generator.**
  - It adds `recoverWith`, `merge`, and `parFlatMap`, plus a mode flag (default or continuous) on `flatMap`, `merge`, and `parFlatMap` scripts.
  - `GenRxLiveCases` writes the new cases into the existing `RxLiveSpecCases.scala`, and `RxLiveSpecTest` gains the matching pipelines. The CI job and the lakefile stay as they are.
  - If `RxLive.lean` grows past ~900 lines, `merge` and `parFlatMap` move to a sibling module, `UniSpec/RxLiveFanIn.lean`, that reuses its script runner.
- **Rules each operator's theorems state:**
  - `recoverWith`, over `var.map` that throws on one value (an error that leaves the input live): values before the error pass through; an error that `f` rejects, or a throwing `f`, reaches the sink as `OnError`; nothing reaches the sink after `cancel`. What happens to input events after a recovery is question 1 below.
  - `flatMap` in continuous mode: the switch rule still holds (a replaced nested var is silent); a nested error does not end the subscription, so later input values still switch.
  - `merge` over two vars: in default mode it completes once both vars stopped, and the first unrecovered error stops it, so the other var is silent afterwards. In continuous mode errors are transient: both vars keep reaching the sink.
  - `parFlatMap` (n = 1 and 2): at most n nested vars are subscribed at any point (a failed one counts until it is cancelled); queued input values start in input order, each when a running nested var ends; the operator completes exactly when the input and every started nested var ended and nothing is queued.
- **parFlatMap is modeled, not dropped.** The rules above don't depend on thread timing. They state the permit handoff, which `FanIn` alone does not express. Ordering across threads stays out: with asynchronous nested streams, only the rules above hold, and the model does not claim more.
- **Bugs the cases expose** are fixed here when the fix is small and the rule is clear, and the ADR is amended (`2026-10-27-rx-merge-parflatmap.md` for merge/parFlatMap, `2026-10-14-rx-completion-semantics.md` for recoverWith). Two need a rule first:
  - **Question 1, `recoverWith` after a recovery.** Proposed: in default mode an accepted error ends the input, as `transformRx` already treats it. The input is cancelled, and the operator completes when the recovery stream does, which is what RPC streaming expects. In continuous mode the input stays live, and its next value cancels the recovery stream and switches back. The alternative keeps today's interleaving and records it in the ADR.
  - **Question 2, the `parFlatMap` permit in continuous mode.** Proposed: a nested error cancels that nested stream and hands its permit to the next queued value. The operator keeps running, and the permit bound still holds. The alternative keeps today's code (a failed nested stream holds its permit for good) and records that in the ADR.
- **PR 2 (MessagePack) adds to `MsgPack.lean` and its generator.**
  - Timestamps: the model takes `(sec, nanos)` with any integer nanos and states the normalization, the chosen format, and the bytes. Theorems: decoding returns the normalized pair; the format is the shortest of 32/64/96 that fits.
  - Floats: an IEEE bit pattern goes in as a natural number, so Lean needs no float arithmetic. Cases cover -0.0, the infinities, NaN payloads, and subnormals, read back both as the same width and through widening/narrowing. Theorem: `0xca`/`0xcb` plus 4/8 big-endian bytes, read back to the same bits.
  - JS and Native switch to the raw-bits functions, so a NaN payload round-trips on every platform as on the JVM. If a platform lacks them, the model canonicalizes NaN instead and the ADR records the split.
  - Timestamps take `Int` nanos, as `packTimestampEpochSecond` does. Cases include seconds near `Long` bounds where `addExact` throws, so the overflow is covered as an error case.
- **Docs each PR updates:** the README's "Covers" cells and its "Changing a model" bullet on what the models leave out, the ADR's RxLive bullet ("It leaves out continuous mode, `recoverWith`, and `parFlatMap`") and the MsgPack bullet, and "Next candidates", from which each PR drops only the items it covers.
- **Coordination with #1007:** the README table gets no new row from these PRs. Whichever PR merges second resolves the shared "Next candidates" line.

## Alternatives and Why Not?

- Drop `parFlatMap` as #1008 did: with nested vars the permit queue is deterministic and has a real rule (the handoff) that is not yet written down anywhere.
- One PR for all four: MessagePack review has nothing to do with Rx completion rules, and a fix in one would hold back the other.
- A new generator and cases file for the new Rx operators: it would repeat the script runner, the case format, and the replay harness.

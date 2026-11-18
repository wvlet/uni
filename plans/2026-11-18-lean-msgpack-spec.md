# Lean model of MessagePack integer and header encoding

## Goals

- Extend the Lean spec pilot (#945, `adr/2026-11-18-lean-specs.md`) to MessagePack, as the ADR's listed follow-up.
- Pin the exact bytes `OffsetPacker` writes for integers and for str/bin/array/map/ext headers. Today `RoundTripTest` checks round-trips only, so a wrong but self-consistent format choice (e.g. `uint16` where `uint8` fits) would pass.

## Background

- The MessagePack spec lists several valid formats per value and says encoders SHOULD use the shortest one. `OffsetPacker` picks one with if-chains per type (`packLong`, `packRawStringHeader`, `packExtTypeHeader`, …).
- Uni's choices beyond "shortest": a non-negative integer uses `uint*`, not `int*`; binary has no fix format, so the shortest is `bin8`; ext of length 1/2/4/8/16 uses `fixext*`.
- The Rx pilot set the pattern: a Lean model with proofs, a generator writing a scalafmt-formatted Scala file, and a UniTest suite replaying it on JVM, JS, and Native.

## Design

Lane: Express — tests and spec only, no change to library code or published artifacts, and no always-gate area. The user asked for it directly ("try message pack as well").

- **Model** (`UniSpec/MsgPack.lean`): an encoder from an integer in `[-2^63, 2^64)`, or from a header kind and length, to a list of bytes, plus a decoder.
- **Theorems:**
  - decoding an encoding gives back the value and consumes exactly its bytes;
  - every emitted byte is below 256;
  - the chosen format is no longer than any other format the spec allows for that value, so the choice is the shortest;
  - non-negative integers never use an `int*` format.
- **Generated cases** (`MsgPackSpecCases.scala`): value → expected hex for every format boundary (`±2^k` and `±2^k - 1` around each format), plus a few interior values.
- **Replay** (`MsgPackSpecTest`): packs each value with `OffsetPacker` and compares the hex, then unpacks with `OffsetUnpacker` and compares the value. Values above `Long.MaxValue` go through `packBigInteger`.
  - Lengths are limited to `Int` range, which is what the Scala API takes.
- **CI:** the existing `Lean spec` job builds the new model and also checks the new generated file. Its path filter adds that file.
- **Out of scope:** floats, timestamps, payload bytes, and `unpackValue` dispatch.

## Alternatives and Why Not?

- **Model whole `Value` trees.** More than the header logic needs; payloads are plain byte copies.
- **Hand-written byte vectors.** They would restate the encoder's own reasoning; the Lean model proves the shortest-format property independently.

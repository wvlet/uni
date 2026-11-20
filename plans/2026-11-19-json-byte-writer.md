# Native: write RPC JSON straight to bytes

## Goals

- Cut the CPU the Native server spends turning RPC results into JSON. After #984, Native rendered JSON into a `StringBuilder` and then copied it char by char into bytes; `charAt` and `append` showed up in the profile.

## Design

Lane: Express — internal, and the output bytes are the same.

- `JsonByteWriter` renders msgpack straight into a reusable byte buffer on Native and Scala.js (`Utf8.manualAscii`). ASCII strings are escaped from their msgpack bytes; other text goes through `Value.appendJsonString`, so the bytes equal `toJson(...).getBytes(UTF_8)`.
- The JVM keeps the `StringBuilder` path with `getBytes`, which HotSpot intrinsifies.
- One writer per thread, in `Weaver`'s existing scratch slot, dropped once it grows past 1 MB.
- Tests: every `MsgPackJsonTest` case also runs through the byte writer, twice, so reuse is covered.

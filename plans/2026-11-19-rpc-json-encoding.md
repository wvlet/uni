# Native server: cut the RPC codec's per-request allocation

## Goals

- A realistic RPC response (50 items, 3.4 KB of JSON) ran at 1.4–4.6k req/s on the Native server, against 74–86k on the JVM: 462 KB allocated per request, and about ten arrays above Boehm's ~400-byte per-thread limit, each taking its global lock.
- Cut that without slowing the JVM, whose standard String/UTF-8 conversions HotSpot intrinsifies.

## Background

- Follows #981 (HTTP layer). `Weaver.toJson` (every RPC result) packed the value into a 10-byte msgpack buffer that doubled as it grew, copied it out, unpacked it into a `Value` tree, and rendered a string per node.
- Byte-weighted profiles (lldb on Boehm refills) also found per-call closures in `require` (msgpack buffer writes) and `Parameter.get`, a regex per `Accept-Encoding` item (`split(';')`), field names re-encoded on every pack, and `Long.toString` per number.

## Design

Lane: Express — internal performance change; JSON output is byte-for-byte the same (equivalence tests against `Value.toJson`).

- **Streaming msgpack→JSON writer** (`MsgPackJson`) replaces `unpackValue.toJson`, sized from the msgpack length. One documented difference: duplicate map keys, which no Weaver packs, are all written.
- **A per-thread packer for `toJson`**, read in place; reentrant calls get their own, and a buffer grown past 1 MB is dropped.
- **Platform-specific UTF-8**: the JVM keeps `String(bytes, UTF_8)` / `getBytes(UTF_8)`; Native and Scala.js convert ASCII byte for char. Hand-written ASCII loops elsewhere (packer, JSON writer, number digits) run only where `Utf8.manualAscii` is true.
- Smaller cuts: pre-encoded field names, no values array unless `None` fields may be omitted, `Parameter.get` without `Option.map`, an inline `check` for buffer bounds, msgpack strings decoded in place, and `Accept-Encoding` parsed with `indexOf`.
- Tests: msgpack→JSON equivalence for every value type, the scratch packer's reentrancy and large values, `Accept-Encoding` q-values, plus the existing suites on JVM, Native, and JS.

## Alternatives and Why Not?

- **Write JSON straight to UTF-8 bytes for RPC responses.** Saves two more copies, but changes the response's content class; a follow-up if needed.
- **Hand-written ASCII loops everywhere.** They'd replace HotSpot intrinsics on the JVM.

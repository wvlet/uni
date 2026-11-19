# Use UniTest property checks inside Uni's own test suite

## Goals

- Dogfood `forAll` / `Gen` / `Arbitrary` from uni-test on Uni's own code, so the property-check API gets real use beyond its self-tests.
- Catch edge cases that example-based tests miss in code whose contract is a law: encode/decode, parse/format, and serialize/deserialize roundtrips.
- Cover helpers that have no dedicated tests today (`Base64`, `URLEncoder`).

## Background

- uni-test ships a dependency-free property checker: `forAll` is mixed into every `UniTest`, with `Gen`, `Arbitrary` (including `Arbitrary.derived` for case classes and enums), shrinking, and seed replay.
- Only four test files in `uni` use it today: msgpack `RoundTripTest`/`ValueTest`, `ULIDTest`, `CrockfordBase32Test`.
- Roundtrip-style code is spread across `wvlet.uni.util`, `wvlet.uni.json`, and `wvlet.uni.weaver`; its tests are hand-picked examples.
- All candidate code is pure Scala and cross-built, so the new properties run on JVM, Scala.js, and Native without platform-specific code.

## Design

- Lane: **Express** — tests only, no change to production code or public API, touches no always-gate area.
- Add properties next to the existing example tests (same test class or a sibling `*PropertyTest`), keeping the examples as documentation.
- Targets, in priority order:
  - `util` codecs: `Base64` bytes/string roundtrip and output alphabet; `URLEncoder` `decode(encode(s)) == s` and encoded output uses only unreserved chars and `%XX`; `Base62` 128-bit roundtrip; `UUIDv7` `toString`/`toBase62` roundtrips on values built by `newUUIDv7(ts)` from a seeded generator (parsers reject non-v7 bits).
  - `util` units: `DataSize`, `ElapsedTime`, `Count` — `parse(x.toString)` matches `x` within the `%.2f` display precision (relative error at most 0.005 of the succinct unit), `mostSuccinct*` keeps the value, ordering agrees with the underlying number. Domain: non-negative, finite values.
  - JSON: a small generator of `JSONValue` trees; `JSON.parse(v.toJSON) == v` and `JSON.parse(JSON.format(v)) == v`; `quoteJSONString` survives arbitrary Unicode and control characters. The generator emits only finite, non-integral doubles: JSON has no NaN/Infinity, and on Scala.js `1.0.toString == "1"` parses back as a long — both are format limits, not bugs.
  - Weaver: small case classes and enums with `Arbitrary.derived` (fields: Int, Long, String, Boolean, Option, List, nested case classes, plus a finite Double); `fromJson(toJson(x)) == x` and `unweave(weave(x)) == x` over MessagePack, where doubles include the infinities.
- Shared generators (e.g. a `JSONValue` Gen) live in test sources only; nothing is added to uni-test's public API unless a gap shows up — then it becomes a separate PR.
- Keep the default sample count, so CI time stays flat; failures report the seed for replay.
- If a property finds a real bug: fix it in this PR when the fix is small and local; otherwise mark the property `pending` with the counterexample and open a follow-up issue.
- Out of scope for this PR, listed as follow-ups: `HttpMultiMap`, cron parsing, `YAMLFormatter`, `DisplayWidth`, `IOPath` normalization.

## Alternatives and Why Not?

- Add ScalaCheck as a test dependency — Uni already has its own checker; dogfooding it is the point.
- Convert existing example tests to properties — examples document specific tricky inputs; properties add to them rather than replace them.
- Cover every module at once — a larger diff is harder to review; start with the clear roundtrip laws and expand in follow-ups.

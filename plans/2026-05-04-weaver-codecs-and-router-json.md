# 2026-05-04: Fix Weaver codecs for ULID/ElapsedTime (#536) and Router @Endpoint JSON encoding (#537)

PR: https://github.com/wvlet/uni/pull/538

## Problems

Both issues block downstream wvlet's airframe → uni migration (wvlet/wvlet#1662).

### #536 — Weaver lacks ULID / ElapsedTime codecs
`Weaver.fromSurface` ships built-ins for `Instant`, `UUID`, `URI`, etc., but not for
uni's own `wvlet.uni.util.ULID` / `wvlet.uni.util.ElapsedTime`. They fell through to
`CaseClassWeaver` and failed at runtime:

- `ULID` is `final class ULID(private val ulid: String)` — Surface couldn't read its
  private field, so `pack` got `null` and tried to `packString(null)` → NPE.
- `ElapsedTime(value: Double, unit: TimeUnit)` decoded fine on the encode side, but the
  client decode reported "Null value not allowed for non-optional field 'unit'" — the
  case-class projection didn't line up with what was on the wire.

### #537 — `RouterHandler` falls back to `value.toString` for case classes
`ResponseConverter.toResponse(Any)` doesn't have access to the method surface, so for
case-class returns from `@Endpoint` controllers it produced
`"Greeting(message=world)"` instead of `{"message":"world"}`. RPC routing
(`MethodCodec.encodeResult`) was already weaver-correct because it has `returnWeaver`
in scope; the `Router` path didn't.

## Decisions

### #536 — encoding shape
- `ULID` → its 26-char Crockford-Base32 string. Mirrors the existing `UUID` codec.
- `ElapsedTime` → its `toString` output (e.g. `5.00ms`), which `ElapsedTime.parse`
  accepts directly. Round-trip safe; succinct unit normalization happens via
  `convertToMostSuccinctTimeUnit` on construction.

Wired into `Weaver.fromSurface`'s `primitiveFactory` so they take precedence over the
case-class fallback. Exported from the `Weaver` companion alongside `uuidWeaver` etc.

### #537 — wiring Weaver into the Router path
- Pre-compute `Weaver.fromSurface(returnType)` per route in `RouterHandler`, not
  per-request. Same shape as `MethodCodec`'s `paramWeavers` / `returnWeaver`.
- **Peel `Rx[_]` and `Option[_]` from the surface before deriving the weaver**, since
  `ResponseConverter` already unwraps these (Rx → flatMap; Option None → 204
  noContent). The weaver is for the inner value the client actually sees.
- Add a new overload `ResponseConverter.toResponse(result, returnWeaver)` rather than
  changing the existing `toResponse(result)` signature. Old callers (incl. existing
  tests) keep their toString-fallback behavior.
- In the weaver-aware path, treat shape-special types (`Response`, `null`, `Unit`,
  `String`, `JSONValue`, `Array[Byte]`, `Rx`, `Option`) as before; everything else
  (case classes, `Seq`, `Map`, etc.) goes through the weaver as JSON.

## Worked example

```scala
case class Greeting(message: String)

class HelloController:
  @Endpoint(HttpMethod.GET, "/hello")
  def hello: Greeting = Greeting("world")

  @Endpoint(HttpMethod.GET, "/maybe")
  def maybe: Option[Greeting] = Some(Greeting("some"))

  @Endpoint(HttpMethod.GET, "/none")
  def none: Option[Greeting] = None

  @Endpoint(HttpMethod.GET, "/rx")
  def rx: Rx[Greeting] = Rx.single(Greeting("reactive"))
```

After the fix:
- `GET /hello` → 200 `{"message":"world"}`
- `GET /maybe` → 200 `{"message":"some"}`
- `GET /rx`    → 200 `{"message":"reactive"}`
- `GET /none`  → 204 (preserved noContent semantics)

## Test coverage

- `AdditionalTypeWeaverTest` — ULID + ElapsedTime msgpack/JSON round-trips,
  `Weaver.fromSurface` derivation, invalid-string error paths.
- `NettyServerTest` — end-to-end test with all four return shapes above.

## Files touched
- `uni/src/main/scala/wvlet/uni/weaver/Weaver.scala` — register ULID/ElapsedTime in
  primitive factory; export. Adds `fromSurfaceOpt` and `innerWeavers` for nested-fallback
  detection.
- `uni/src/main/scala/wvlet/uni/weaver/codec/PrimitiveWeaver.scala` — `ulidWeaver` /
  `elapsedTimeWeaver` givens; promotes `stringBasedWeaver` from `JvmWeaver`.
- `uni/.jvm/src/main/scala/wvlet/uni/weaver/codec/JvmWeaver.scala` — uses the shared helper.
- `uni/src/main/scala/wvlet/uni/weaver/CollectionWeaver.scala` — overrides `innerWeavers`
  on each composite weaver class.
- `uni/src/main/scala/wvlet/uni/weaver/codec/CaseClassWeaver.scala` — exposes field weavers
  via `innerWeavers`.
- `uni/src/main/scala/wvlet/uni/util/ElapsedTime.scala` — extends parse regex to accept
  exponent form (so `Double.toString` round-trips).
- `uni/src/main/scala/wvlet/uni/http/router/ResponseConverter.scala` — weaver-aware
  overload; weaver-throw fallback to no-weaver path (preserves `application/json`).
- `uni-netty/src/main/scala/wvlet/uni/http/netty/RouterHandler.scala` — pre-compute
  `returnWeavers` (IdentityHashMap), peel `Rx[_]` once + `Option` recursively, skip
  weaver derivation for `Response` / primitive returns.
- Tests: `AdditionalTypeWeaverTest.scala`, `WeaverTest.scala`, `NettyServerTest.scala`.

## Review iteration learnings

This PR went through ~10 rounds of automated review (Gemini + codex) before settling.
Notable findings that reshaped the design:

1. **Rx peeling alignment**: initial recursive Rx peel didn't match
   `ResponseConverter`'s single-level Rx unwrap. Now: peel exactly one Rx at the top,
   then any number of Options.
2. **Empty-fallback detection**: `Weaver.fromSurface` returns
   `emptyObjectWeaver` for unsupported types (`Either`, `LocalDate`, etc.), and the
   fallback can also be embedded *inside* composite weavers
   (`Seq[Either]`, `case class Foo(d: LocalDate)`). Added `Weaver.fromSurfaceOpt` that
   walks the weaver tree via a new `innerWeavers` accessor and returns `None` if the
   fallback appears at any position. RouterHandler uses this to skip caching weavers
   that would silently produce `[{}]` / `{"d":{}}`.
3. **Wire-format stability for primitives**: weaving `Int`/`Boolean` returns produces
   bare JSON scalars (`42`, `true`) but pre-PR Router emitted toString-quoted strings
   (`"42"`, `"true"`). Skip weaver derivation when `surface.isPrimitive` to preserve
   wire format for clients that consume the legacy form.
4. **Opaque wrapper types**: `final class UserId(private val v: String)` matches the
   case-class factory but `CaseClassWeaver.pack` throws (Surface can't read the private
   field). Catch in `ResponseConverter` and fall through to the no-weaver
   `application/json` toString-quoted path, not `text/plain`.
5. **`fromSurface` can throw**: `BigInteger` is `isPrimitive=true` but absent from
   `primitiveFactory`, so `fromSurface` throws. `fromSurfaceOpt` catches and returns
   `None` — otherwise composite types containing such fields would fail
   handler construction.
6. **ElapsedTime precision**: `et.toString` is `%.2f`-formatted (lossy). Encode using
   `${et.value}${unit}` instead, and extend `ElapsedTime.parse` to accept the
   exponent suffix (`1.0E-9ns`) so `Double.toString` round-trips losslessly. Decision
   point: chose JSON-string wire shape over structured `{value, unit}` map to avoid a
   wire-format change for direct `ElapsedTime` returns from `@Endpoint` methods.

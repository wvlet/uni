# Close uni gaps blocking the Airframe → uni migration

## Goals

- td-llm-proxy cannot move its HTTP stack from Airframe to uni 2026.1.28: three gaps were confirmed with reproducers (Scala 3.9.0, Java 25). Close them in uni so the migration only needs a thin adapter, not a custom transport or router.
- Each fix keeps uni's secure defaults ([ADR: HTTP server secure defaults](../adr/2026-10-14-http-server-secure-defaults.md)).

## Background

1. **`Router.of[C]` fails when an endpoint returns `Response`.** `Response` has a `status: HttpStatus` field. `HttpStatus` is an enum with a parameterized case (`Other(code)`), so Scala does not synthesize `valueOf`/`values` on its companion. The Scala 3 Surface macro's enum branch (`CompileTimeSurfaceFactory.scala:713-718`) assumes every `scala.reflect.Enum` has `valueOf` and calls `sys.error(...)` otherwise. Any surface that reaches such an enum fails, and `Response` endpoints are documented as supported.
2. **No request decompression in uni-netty.** The pipeline has `HttpContentCompressor` (responses) but no decoder, so `Content-Encoding: gzip|deflate` bodies reach handlers still compressed. Airframe-http-netty decodes gzip/deflate (and `x-` aliases) by default, caps the decoded size of compressed requests only (`withMaxDecompressedRequestSize`, default 64 MiB), and rejects before any app filter or auth runs: malformed → plain-text 400, over the cap → plain-text 413, connection closed. The proxy relies on this, with a 128 MiB cap.
3. **Controller exceptions never reach outer filters.** `RouterHandler.handleRequest` catches a synchronous exception and returns a 500 `Response`, so an `RxHttpFilter` added via `withFilter` sees a successful 500, not the exception, and cannot classify it (e.g. `IllegalArgumentException` → 400). An exception raised *inside* a returned `Rx` already propagates as `OnError`, so today's behavior also depends on whether the controller fails synchronously or asynchronously. `NettyRequestHandler.runRxResponse` already renders any `OnError` with `HttpServerSecurity.errorResponse(e, detailedErrors)`, the secure generic 500.

## Design

Lane: **Standard** — fixes 2 and 3 touch security boundaries (pre-auth decompression limits, server error rendering).

Three independent PRs from `main`, smallest first:

1. **`fix/surface-enum-without-valueof`** (uni core)
   - In the enum branch, look up `valueOf` first; when the companion has none, build `EnumSurface` with a string lookup that returns `None` instead of aborting compilation. The type is still an enum, and parameterized enums cannot be decoded from a bare string anyway.
   - Tests: `Surface.of` an enum with a parameterized case (uni `Scala3EnumTest`), `Surface.of[Response]`, and a uni-netty test with a `Response`-returning `@Endpoint` served end to end (mirrors `ResponseSurfaceCompileFailure.scala`).
2. **`feature/netty-request-decompression`** (uni-netty), a port of Airframe's `NettyRequestDecompression`
   - `NettyServerConfig`: `requestDecompression: Boolean = true`, `maxDecompressedRequestSize: Int = 64 MiB` with `withRequestDecompression` / `noRequestDecompression` / `withMaxDecompressedRequestSize(n)` (`require(n > 0)`).
   - Pipeline: a per-channel `HttpContentDecompressor` limited to `gzip`, `x-gzip`, `deflate`, `x-deflate` (other encodings such as `br`/`zstd` pass through raw, so behavior never depends on optional native codecs), followed by a limiter that counts decoded bytes of compressed requests only. Both sit right before `HttpObjectAggregator`, so they run before the request reaches `NettyRequestHandler`, any `RxHttpFilter`, or the host check.
   - Rejection: `413` "Decompressed request body exceeds N bytes" or `400` "Malformed compressed request body", `text/plain; charset=utf-8`, `Connection: close`, auto-read off so the rest of the body is not inflated, then close.
   - `maxContentLength` still bounds the aggregated (decoded) body, as in Airframe; docs state that a large decoded cap also needs a large enough `maxContentLength`.
   - Tests mirroring the probe: gzip, x-gzip, deflate (zlib and raw) echo the decoded UTF-8 body; uncompressed unchanged; over-cap → 413 + close, and the filter/handler is never invoked; malformed → 400; `noRequestDecompression` passes bytes through; an uncompressed body above the decompression cap is accepted.
3. **`fix/router-handler-propagate-errors`** (uni-netty)
   - `RouterHandler` returns `Rx.exception(e)` for controller/provider failures instead of a 500 `Response`. `HttpRequestMappingException` stays a local 400 (the router's own classification of bad input) and a missing route stays 404.
   - Outer `RxHttpFilter`s (both `NettyServerConfig.withFilter` and the router's own `@Filter`) can now `recover` the original exception. If nobody recovers, `NettyRequestHandler` renders the secure 500 exactly as today, honoring `NettyServerConfig.withDetailedErrors`.
   - `RouterHandler.withDetailedErrors` no longer has an effect, because the handler no longer renders 500s. Deprecate it, pointing to `NettyServerConfig.withDetailedErrors`. Update `docs/http/server.md` and the secure-defaults ADR.
   - Tests: the probe's error-filter case (filter maps `IllegalArgumentException` → 400 "classified failure"); without a filter the body is still the generic `Internal Server Error`; with `withDetailedErrors(true)` on the server it shows the message; parameter mapping errors remain 400.

## Alternatives and Why Not?

- **Opt-in error propagation (`RouterHandler.withErrorPropagation`)** keeps current behavior for anyone calling `handler.handle(...)` directly and checking for a 500 `Response`. Not recommended: async failures already propagate, so the default would stay inconsistent, and the Netty server's rendering of `OnError` is already the secure 500. It is the fallback if a silent behavior change is unacceptable.
- **Single PR for all three:** fewer CI cycles, but mixes a core macro fix with two transport changes that need separate security review.
- **Fix 1 by adding `valueOf` to `HttpStatus`:** fixes only this enum; any user enum with a parameterized case would still break the macro.
- **Decode with `java.util.zip` after aggregation:** simpler, but the aggregator would cap the compressed size, not the decoded size, and the whole compressed body would be buffered before any limit applies. Netty's streaming decoder plus a counting limiter stops inflating as soon as the cap is crossed.

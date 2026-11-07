# Generate stream calls in sbt-uni client codegen

## Goals

- `uniHttpClients` should write `rpc.stream(...)` for streaming methods, so a generated client delivers every event, not just the first. Today the uni-harness `ChatApiClient` is hand-written for this reason.
- Keep the wire format and the server's negotiation (ADR 2026-11-06) unchanged.

## Background

- Streaming is chosen by the client (`Accept: text/event-stream`). A trait method returning `Rx[A]` can't tell the generator whether it returns one async value or a stream. ChatApi uses `Rx[A]` for all four methods, only one of which streams.
- `ServiceScanner` reads compiled classes with **Java reflection**, so it sees a real trait type but not a type alias or a Scala annotation.
- The scanner also erases generics (`Rx[ChatEvent]` → `Rx`, `Seq[User]` → `Seq`), so today any generic or `Rx`-returning method generates code that doesn't compile. A stream method needs its element type.
- sbt-uni runs the generator from a pinned uni 2026.1.21, because sbt's Scala 3.8 metabuild can't read 3.9 TASTy.
- airframe-grpc uses `RxStream[A]` as the return type for server streaming, and as an argument type for client streaming.

## Design

Lane: Standard. This adds a new public type (`RxStream[A]`) and changes the codegen output.

- **Marker: the return type `RxStream[A]`**, following airframe-grpc. `trait RxStream[+A] extends Rx[A]` lives in `wvlet.uni.rx`, and a service method returning `RxStream[A]` is server-streaming. The trait declares this, so the contract lives in the API type. An earlier revision used an `@rpcStream` annotation; the user rejected it.
- **Implementing:** `RxStream(rx)` wraps any `Rx`, e.g. `RxStream(Rx.sequence(...).map(...))`. It is a pass-through operator, so it runs exactly like the Rx it wraps. Rx operators keep returning `Rx`, so the wrap comes last.
- **Server unchanged:** the RPC router and client already accept any `Rx` subtype (`isAssignableFrom`), and the dispatcher keeps negotiating on `Accept`. A generic JSON client of an `RxStream` method still gets only the first value.
- **Scanner:** read generic signatures (`getGenericReturnType` / parameter types) so `Rx[ChatEvent]`, `Seq[User]` etc. render with their type arguments.
- **Async client:** `RxStream[A]` methods call `rpcClient.stream[A]` and return `RxStream[A]`, mirroring the trait. Methods returning `Rx[A]` return `Rx[A]` through `call` (not `Rx[Rx[A]]`).
- **Sync client:** there is no sync stream API, so `RxStream` methods are left out, with a one-line comment pointing to `AsyncClient`. `Rx[A]` methods return `A` through `call`.
- **Fork the generator:** `uniHttpClients` forks uni's new `HttpCodeGeneratorMain` on the app's classpath, as `uniOpenAPI` does. The plugin's in-process uni is pinned to 2026.1.21 (adr/2026-09-03-sbt-uni-in-process-uni-pin.md), so an in-process generator would never pick up these changes.
- **Out of scope:** client streaming (`RxStream` arguments) and stream cancellation. No OpenAPI change: OpenAPI documents only `@Endpoint` REST routes, and those don't stream.
- **Docs/example:** `docs/http/rpc.md` (Streaming Results), `for-ai-agents.md`, the uni skill's `references/rpc.md` plus a plugin version bump, and `ChatApi.streamMessage` returning `RxStream[ChatEvent]`. Update the streaming ADR's Consequences line.
- Tests: `RxStream` running like its input, scanner generics, generator output for `RxStream`/`Rx` methods in the async and sync clients, the forked main, and the sbt-uni scripted test compiling a generated stream client.

## Alternatives and Why Not?

- **`@rpcStream` Java annotation (first revision).** It worked, but it puts the contract in an annotation instead of the type.
- **Generated `StreamClient` beside `AsyncClient`.** No API change, but the trait wouldn't say which methods stream.
- **Scala `StaticAnnotation` like `rpcIgnore`.** Invisible to the reflection scanner; would need a TASTy reader dependency in uni.
- **Stream every `Rx[A]` method.** No marker needed, but ChatApi's unary `Rx` methods would pay SSE cost and lose retry.
- **`type RxStream[A] = Rx[A]` alias.** It needs no wrapping, but it erases, so neither reflection nor Surface could see it.
- **List stream methods in the sbt setting.** Keeps traits clean, but splits the API contract from the trait and OpenAPI can't see it.
- **Dispatcher streams marked methods without negotiation.** Breaks generic JSON clients and the old-server check; no clear gain.

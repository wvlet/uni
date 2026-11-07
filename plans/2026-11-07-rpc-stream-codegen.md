# Generate stream calls in sbt-uni client codegen

## Goals

- `uniHttpClients` should write `rpc.stream(...)` for streaming methods, so a generated client delivers every event, not just the first. Today the uni-harness `ChatApiClient` is hand-written for this reason.
- Keep the wire format and the server's negotiation (ADR 2026-11-06) unchanged.

## Background

- Streaming is chosen by the client (`Accept: text/event-stream`); a trait method returning `Rx[A]` can't tell the generator whether it is a one-shot async value or a stream. ChatApi uses `Rx[A]` for all four methods, only one of which streams.
- `ServiceScanner` reads compiled classes with **Java reflection**. A Scala `StaticAnnotation` (like `rpcIgnore`) is invisible there; only a Java annotation with `RUNTIME` retention is.
- The scanner also erases generics (`Rx[ChatEvent]` → `Rx`, `Seq[User]` → `Seq`), so today any generic or `Rx`-returning method generates code that doesn't compile. A stream method needs its element type.
- sbt-uni runs the generator from a pinned uni 2026.1.21, because sbt's Scala 3.8 metabuild can't read 3.9 TASTy.
- Precedent for a Java annotation in shared (cross-platform) sources: `uni-core/.../surface/secret.java`. Scala.js/Native only read its signature.

## Design

Lane: Standard — new public API (an annotation) and changed codegen output.

- **Marker:** `@rpcStream`, a `RUNTIME`-retained Java annotation in `wvlet.uni.http.rpc`, allowed on methods returning `Rx[A]`. Java (not Scala) so both the reflection scanner and Surface see it.
- **Server unchanged:** the dispatcher keeps negotiating on `Accept`; the marker is metadata for codegen only. A generic JSON client of a marked method still gets the first value.
- **Scanner:** read generic signatures (`getGenericReturnType` / parameter types) so `Rx[ChatEvent]`, `Seq[User]` etc. render with type args; record `isStream` per method.
- **Async client:** `Rx[A]`-returning methods return `Rx[A]` (not `Rx[Rx[A]]`); marked ones call `rpcClient.stream[A]`, others `call[A]`.
- **Sync client:** there is no sync stream API, so marked methods are left out with a one-line comment pointing to `AsyncClient`. Unmarked `Rx[A]` methods return `A` via `call`.
- **Fork the generator:** `uniHttpClients` forks uni's new `HttpCodeGeneratorMain` on the app's classpath, as `uniOpenAPI` does. The plugin's in-process uni is pinned to 2026.1.21 (adr/2026-09-03-sbt-uni-in-process-uni-pin.md), so an in-process generator would never pick up these changes.
- **No OpenAPI change:** OpenAPI documents only `@Endpoint` REST routes, which don't stream; RPC methods aren't in it.
- **Validation:** `@rpcStream` on a non-`Rx` method fails codegen with a clear error.
- **Docs/example:** `docs/http/rpc.md` (Streaming Results), `for-ai-agents.md`, the uni skill's `references/rpc.md` + plugin version bump; mark `ChatApi.streamMessage` with `@rpcStream`. Update the ADR's Consequences line.
- Tests: scanner generics + marker, generator output for marked/unmarked async and sync, the forked main, and the sbt-uni scripted test compiling a generated stream client.

## Alternatives and Why Not?

- **Scala `StaticAnnotation` like `rpcIgnore`.** Invisible to the reflection scanner; would need a TASTy reader dependency in uni.
- **Stream every `Rx[A]` method.** No marker needed, but ChatApi's unary `Rx` methods would pay SSE cost and lose retry.
- **Distinct return type (e.g. `RxStream[A]`).** Visible to reflection, but changes service trait types and the dispatcher; a type alias would erase away.
- **List stream methods in the sbt setting.** Keeps traits clean, but splits the API contract from the trait and OpenAPI can't see it.
- **Dispatcher streams marked methods without negotiation.** Breaks generic JSON clients and the old-server check; no clear gain.

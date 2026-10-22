# Close the remaining uni gaps blocking the Airframe → uni migration

## Goals

- td-llm-proxy's Airframe → uni migration is paused until uni closes five more gaps, found in the 2026.1.29 sources after #730/#731/#732 shipped (see [the first gaps note](2026-10-21-airframe-migration-gaps.md); not redone here). Close all five, including OpenAPI generation, so the proxy can drop Airframe completely.
- The proxy should only need configuration and a thin adapter, not a custom router, body decoder, logger, or thread-local context.
- Each change keeps uni's secure defaults ([ADR: HTTP server secure defaults](../adr/2026-10-14-http-server-secure-defaults.md)).

## Background

1. **`@Endpoint` is matched by simple name.** `RouterMacros.extractRoutes` (`RouterMacros.scala:68`) calls `ms.findAnnotation("Endpoint")`, and `Annotation.is` matches either the simple or the full name. The proxy keeps Airframe's `@Endpoint` on its API traits during the transition, because `doc/openapi.yaml` is generated from them. When the Airframe annotation comes first, `find` returns it, its `method` is not a uni `HttpMethod`, and the route is dropped with no error. The same happens to a uni `@Endpoint` whose `method` or `path` cannot be read.
2. **No typed request-body binding.** `HttpRequestMapper.bindParameter` binds only `Request`, path, query, and default values. A `def messages(request: MessagesRequest, httpRequest: Request)` endpoint fails with "Required parameter 'request' is missing". Decoding must honor custom `given Weaver`s, because the proxy decodes with lenient hand-written codecs (`RequestCodec`, `ContentBlockCodec`). `Weaver.fromSurface`, which `RouterHandler` uses for return values, builds weavers at runtime and cannot see `given`s. So the weavers have to be summoned at compile time, where `Router.of[C]` is expanded.
3. **No HTTP access log.** Airframe's `HttpLogger` writes one log map per request (`http_server`) and one per SSE stream (`http_stream`) to a file or Fluentd. Operations and billing reconciliation read these logs. uni has `FileLogHandler` (rotation and gzip, in uni-core) and `AsyncHandler` (JVM), but nothing that builds per-request entries.
4. **No server-side request attributes.** The proxy's auth filter resolves an identity that controllers need. Airframe passes it through `RPCContext`. In uni the only carrier is `Request`, and its only open-ended field is `headers`, which clients can set. A thread-local adapter works today only because `RouterHandler` calls controllers synchronously, and it would break on any async hop.
5. **No OpenAPI generation.** uni has the parts (Surface, `mcp.JsonSchema` (private), `YAMLFormatter`), but no generator, and no annotations for summaries, tags, or response schemas. This is a new feature surface, not a gap fix.

Proxy-side defaults (docs only, no code change). The proxy will override uni's defaults: `maxContentLength` 64 KB, a 30 s request read timeout that also closes idle keep-alive connections, and `maxStreamingConnections` 256 (503 beyond). `docs/http/server.md` should list these together as "settings a public-facing API server usually tunes".

## Design

Lane: **Standard**: new public API (body binding, attributes, access log), and items 2 and 4 touch security boundaries (untrusted body decoding, identity propagation).

Five PRs from `main`, smallest first.

1. **`fix/endpoint-annotation-full-name`** (uni)
   - Match `wvlet.uni.http.router.Endpoint` by full name, so foreign `@Endpoint`s are ignored whatever their order.
   - Fail loudly: a uni `@Endpoint` whose `method` or `path` cannot be read throws `IllegalArgumentException` naming the method when the router is built, instead of silently dropping the route.
   - Docs: the "settings a public-facing server usually tunes" section in `docs/http/server.md` (the defaults above).
   - Tests: a method with a test-local `@Endpoint` annotation (same simple name, different package) placed before and after uni's still gets its route; a method with only the foreign annotation gets no route.
2. **`feature/request-attributes`** (uni + uni-netty)
   - Add `attributes: RequestAttributes = RequestAttributes.empty` to `Request`. It is an immutable map keyed by typed keys: `RequestAttribute[A](name)`, `request.withAttribute(key, value)`, `request.attribute(key): Option[A]`. `toString` prints names only, so logging a request does not leak values. Keys compare by identity, so two libraries cannot collide by choosing the same name.
   - Server-only: a request decoded by the server always starts with empty attributes. Attributes are never read from or written to headers, and HTTP clients ignore them. A filter sets them with `next(request.withAttribute(...))`. Because `Request` is immutable, they stay scoped to that request.
   - Controllers read them through their `Request` parameter, or directly: a parameter of type `RequestAttributes` is bound like `Request`.
   - uni-netty sets one built-in attribute, `RequestAttribute.RemoteAddress` (`host:port`), for the access log and for auth.
   - Tests: a filter sets an identity and the controller sees it; a header with the same name has no effect; concurrent requests do not see each other's attributes. RPC (`RxRouter`) methods take no `Request` parameter today, so exposing attributes there is a follow-up.
3. **`feature/router-body-binding`** (uni + uni-netty)
   - In the `Router.of[C]` / `buildRouter` macro, for each `@Endpoint` parameter that is not a primitive, `Option` of a primitive, `Request`, or `RequestAttributes`, try `Expr.summon[Weaver[P]]`. The summoned weaver (or `None`) is stored on the `Route` as `paramWeavers`. `None` falls back to `Weaver.fromSurface` at runtime. Custom codecs are found in `P`'s implicit scope (companion) or the imports at the `Router.of` call site.
   - Binding order: `Request` / `RequestAttributes` → path → query → body → default. Body rules follow Airframe: if exactly one parameter is left to bind from the body, the whole body decodes into it. If several are left, the body must be a JSON object and each is read from the field of its name.
   - Content types: JSON (or none); MessagePack when `Content-Type` is `application/msgpack` or `application/x-msgpack`, since Weaver supports both. Anything else → `415`.
   - Malformed input (parse failure, wrong shape, missing required field) → `HttpRequestMappingException` → `400` with a generic message ("Invalid request body for parameter 'request'"). The body is never echoed back. An empty body with an `Option` or default parameter → `None` or the default.
   - Tests: case-class body; a custom `given Weaver` that accepts a shape the derived weaver rejects; multi-parameter body; path + query + body together; malformed JSON → 400 through the Netty server; msgpack body; unsupported content type → 415.
4. **`feature/http-access-log`** (uni + uni-netty)
   - `HttpAccessLogFilter(config)` is an `RxHttpFilter` added outermost with `NettyServerConfig.withFilter`. After #732 it sees controller exceptions as `OnError`, logs them, and re-raises them, so rendering is unchanged.
   - Entry (`Map[String, Any]`, Airframe-compatible names): `time`, `method`, `path`, `query` (optional), `status_code`, `response_time_ms`, `request_size`, `response_size`, `remote_address` (from attributes), `request_header`/`response_header` (lower-cased), `exception_class`/`exception_message`. Headers are redacted by default: `authorization`, `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key` are logged as `[redacted]`; `withRedactedHeaders` / `withExcludedHeaders` configure this. An `extraEntries: (Request, Option[Response]) => Map[String, Any]` hook lets the app add fields such as the user id from attributes.
   - SSE: for an event-stream response the filter wraps `response.events`. The `http_server` entry is written at the start; an `http_stream` entry is written when the stream ends (completion, error, or cancellation), with `event_count`, `stream_bytes`, `stream_duration_ms`, and any exception.
   - Sink: `trait HttpAccessLogWriter { def write(entry: Map[String, Any]): Unit; def close(): Unit }`. Built in: `HttpAccessLogWriter.jsonFile(FileLogHandlerConfig)` (JSON lines, rotation and gzip, async on the JVM) and `inMemory` for tests. Fluentd is a user-supplied writer of a few lines; uni adds no Fluentd client dependency.
   - Known limitation (same as Airframe): requests rejected before filters run (decompression 413/400, read timeout, host check) are not logged.
   - Tests: entry fields for 200/404/500; redaction; exception logged and still rendered as a generic 500; SSE stream entry with counts after completion and after client disconnect; JSON-file writer round-trip.
5. **`feature/openapi-generator`** (uni)
   - `@Endpoint` gains `description: String = ""` (the proxy's endpoint descriptions). Existing call sites still compile.
   - `OpenAPI.of(router, OpenAPIConfig(title, version))` builds an OpenAPI 3.0.3 document as a `JSONValue`; `.toYAML` (via `YAMLFormatter`) and `.toJSON` render it. Config: `withTitle`, `withVersion`, `withSchemaPackagePrefix` (strips a package prefix from schema names, as Airframe does: `model.MessagesRequest`), `withErrorResponses` (default 400 and 500 as shared `components/responses`).
   - Mapping, kept structurally close to Airframe's output so the diff of `doc/openapi.yaml` stays reviewable: path `:id` → `{id}`; `operationId` and `summary` = method name (a duplicate `operationId` throws); `description` from the annotation; `tags` = controller simple name; path and query parameters from PR 3's binding rules; body parameters → `requestBody` for `application/json` and `application/x-msgpack`; the `200` schema comes from the return type, with `Rx`/`Option` peeled, and a `Response` or SSE return becomes a generic response with no schema.
   - Schemas: move `mcp.JsonSchema`'s Surface → JSON Schema mapping into a shared internal helper used by both MCP and OpenAPI. OpenAPI emits case classes as `$ref`s into `components/schemas`; MCP output is unchanged.
   - Generation: `OpenAPI.of` works from any `main` or test. `sbt-uni` also gets an `uniOpenAPI` task like `sbt-airframe`'s: settings `uniOpenAPIRouters` (Scala object members, or objects, that return a `Router`), `uniOpenAPITitle`, `uniOpenAPIVersion`, `uniOpenAPISchemaPackagePrefix`, `uniOpenAPIFormat`, and `uniOpenAPITargetDir`. It writes `openapi.yaml` by forking a JVM that runs a small uni generator main on the project's runtime classpath, so the build never loads app classes. Documented in `docs/http/openapi.md`.
   - Tests: golden YAML for a controller covering path, query, and body parameters, nested case classes, `Option`/`Seq`/`Map`, enums, `Rx` returns, and SSE; a duplicate `operationId` fails; MCP schema tests still pass after the move.

## Alternatives and Why Not?

- **Body weavers from `Weaver.fromSurface` only:** no macro change, but custom `given` codecs are silently ignored, which is the proxy's exact need. Runtime registration (`Weaver.register[A]`) would add global mutable state and depend on initialization order.
- **Attributes as a `ThreadLocal` / `RPCContext` clone:** no change to `Request`, but the context is lost on any async hop (`Rx` on another thread, SSE pool), and it is harder to reason about than a value carried on the request. Adding a defaulted field to the `Request` case class keeps constructor calls source-compatible; only positional `case Request(a, b, c, d, e, f)` patterns break. We accept that.
- **Attributes as `Map[String, Any]`:** simpler, but untyped and collision-prone between libraries.
- **Access logging inside `NettyRequestHandler`:** it would also see pre-filter rejections and exact wire bytes, but it ties logging to Netty, and the per-request map would duplicate what a filter already sees. It could be added later as a hook if the gap matters.
- **OpenAPI deferred to its own plan:** this would ship the cut-over sooner, but the proxy would keep an Airframe build-only module just for docs. Rejected at review; it is in this round as the last PR.
- **sbt task that loads app classes in the build's classloader (as `sbt-airframe` does):** no fork, but fragile on sbt 2.x (Scala 3 metabuild vs. app Scala version, cached classloaders). Forking the generator main costs about a second and avoids that.
- **Fail loudly on any foreign `@Endpoint`:** stricter, but it would forbid exactly the dual-annotation transition the proxy needs.

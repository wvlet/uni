# Telemetry: built-in OpenTelemetry-shaped tracing, no SDK dependency

## Context

Users want what `dd-java-agent` gives them, traces with no metric code, and they want to report to Datadog. A JVM agent covers the JVM libraries it knows. It can't see Uni's built-in socket server, the Native and Node servers, or RPC method names, and Scala Native and Scala.js have no agent at all. Uni already has the choke points tracing needs: every server runs the `RxHttpFilter` chain, the router and `RPCDispatcher` resolve the method before calling it, and the sync and async HTTP clients are the only paths for outgoing calls. Design note: `plans/2026-11-21-telemetry.md` (3 PRs; this ADR records PR 1 and the direction for the rest).

## Decision

- **`wvlet.uni.telemetry` in `uni`, not uni-core.** `TraceContext`, `Span`, `SpanData`, `SpanExporter`, and `Telemetry`. HTTP, RPC, and the exporters are in `uni`, and uni-ui depends on `uni`, so nothing below needs it.
- **No OpenTelemetry SDK dependency.** The API is small and shaped like OpenTelemetry: span kinds, an Unset/Ok/Error status, `String | Long | Double | Boolean` attributes, and semantic-convention attribute names (`http.request.method`, `http.route`, `rpc.service`, `server.address`). Backends receive OTLP (PR 2), which reaches Datadog through the Datadog Agent's OTLP receiver. Uni ships no vendor-specific code.
- **Disabled is free.** `Telemetry()` with no exporter is disabled. `TelemetryFilter` passes requests through, and the client skips span creation; no ids are generated.
- **W3C Trace Context only.** `traceparent` is parsed strictly. A malformed header is ignored, as the spec requires. `tracestate` is carried unchanged. Datadog's own headers are not read: `dd-java-agent` writes `traceparent` when `tracecontext` is in its propagation styles.
- **Sampling at the root, then the parent's flag.** `sampleRatio` applies only to new traces. An unsampled context still propagates.
- **The current span lives in a thread-local.** `TelemetryFilter` makes the server span current while `next.handle` runs its synchronous part, which covers RPC and `@Endpoint` methods that return a value. The client takes its parent from the current span, or from an explicit `traceparent` header on the request. `Telemetry.withSpan`/`withContext` hand a span over by hand, and the request's `TelemetryFilter.CurrentSpan` attribute carries it with the request.
- **Telemetry has its own request attribute, filled where `recordRPCCall` runs.** RPC spans are named `service/method`; `@Endpoint` spans are named `METHOD /route/:template`. Naming therefore doesn't depend on `HttpAccessLogFilter`.
- **One client span per logical call.** Retries and redirects are `http.request.resend` events, with `http.request.resend_count` on the span. The final response sets the status. Client spans fail on 4xx and 5xx, server spans on 5xx only. Streaming and SSE spans end when the stream ends, fails, or is cancelled.
- **Opt in per client, and propagate to every host it calls.** `HttpClientConfig.withTelemetry`/`noTelemetry`. Telemetry is already chosen per client, so an LLM vendor client sends `traceparent` only if it is configured with telemetry.
- **Never record secrets by default.** The query string, URL user info, and RPC arguments are left out of spans. An exception records its type and message, not its stack trace.
- **An exporter can't fail a request.** A failing `exportSpan` is logged and dropped. `exportSpan` runs on the thread that ended the span; I/O exporters buffer (PR 2).

## Alternatives

- **Depend on the OpenTelemetry Java SDK.** JVM only, a large dependency tree, against Uni's minimal-dependency rule. A separate bridge module to `GlobalOpenTelemetry` can come later for teams that run an agent.
- **Only document the JVM agents.** They miss Uni's servers, RPC names, and every non-JVM platform.
- **A Datadog-specific exporter.** It ties Uni to one vendor, and OTLP already reaches Datadog.
- **Context propagation through every Rx operator.** It touches all of uni-core's Rx for a case, async continuations, that explicit hand-off covers for now.
- **A span per retry attempt.** It is more precise, but a call's latency and outcome are what dashboards show, and a span per call keeps traces small.

## Consequences

- A JVM agent and Uni don't see each other's current span: they share a trace id through `traceparent`, but some spans read as siblings. A bridge module would fix that.
- WebSocket upgrades bypass server filters, so they get no span.
- Async code that leaves the handler thread loses the current span unless it passes it on.
- PR 2 adds the OTLP/HTTP JSON exporter and HTTP/RPC duration metrics; PR 3 adds `UiTelemetry` for uni-ui on top of `AgentRegistry`. Each gets an addendum here.

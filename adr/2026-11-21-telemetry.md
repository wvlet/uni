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

## Addendum (2026-11-21): OTLP export and duration metrics (PR 2)

- **`OtlpExporter` in `wvlet.uni.telemetry.otlp`, one class for spans and metrics.** It buffers spans, aggregates histograms, and posts OTLP/HTTP JSON to `/v1/traces` and `/v1/metrics` on an `RxTimeProvider` timer through Uni's async HTTP client, so it runs on the JVM, Native, Node.js, and browsers. `Telemetry.withOtlp(config)` sets it as both exporters. `Telemetry.shutdown(): Rx[Unit]` stops the timer and emits once the last send is done, so JVM and Native services can `.await` it in a shutdown hook; `close()` starts the same without waiting, since Scala.js can't block. Its own client has telemetry off, so a flush never traces itself.
- **JSON, not protobuf.** OTLP/HTTP servers accept both, including the Datadog Agent's receiver and the Collector. JSON needs only Uni's JSON AST. 64-bit integers are strings, ids are hex, enums are integers, as the OTLP JSON mapping requires.
- **The resource travels with the data.** `SpanData.resource` and `MetricExporter.recordHistogram(resource, ...)` carry `service.name`, so one exporter needs no copy of the service's settings and can serve several `Telemetry` instances.
- **Delta histograms.** `HistogramAggregator.collect()` returns the values since the previous collect and starts over. Datadog reads delta histograms directly, and a restart never produces a negative rate. Buckets are upper-inclusive, with the OpenTelemetry advice boundaries for HTTP durations in seconds.
- **Metrics are measured for every request, sampled or not.** `TelemetryFilter.ServerCall` and `HttpClientTrace` time the call with `nanoTime` and record on end, once. Attributes are low-cardinality only: method, route (or RPC service and method), status code, error type, and for clients the server address and port, never the URL.
- **Metrics only is a mode.** A `Telemetry` with a metric exporter and no span exporter records durations, starts no traces, and its clients send no `traceparent`. Spans of a root request have an invalid all-zero context, which `startSpan` ignores as a parent.
- **Failures are logged once per outage and dropped.** The exporter warns when sends start failing and logs once when a response arrives again. Recovery is judged by a delivered response, not by stream completion. Past `maxQueueSize` buffered spans, new ones are dropped and counted.
- Alternatives: a periodic reader owned by `Telemetry` (a value type with no lifecycle, and `copy` would duplicate timers); cumulative temporality (needs per-series start times kept forever, and Datadog prefers delta); deriving metrics from spans (wrong under sampling).

## Addendum (2026-11-21): UI telemetry (PR 3)

- **`UiTelemetry` in uni-ui (`wvlet.uni.ui.telemetry`) reads `AgentRegistry`, not the DOM.** Events carry each component's kind and stable ref, the names agents use, so they survive markup changes. Its sources:
  - `ui.change`: committed changes from `AgentRegistry.record`, which skips canceled ones and hidden components.
  - `ui.press`: one capturing `click` listener that resolves the nearest bound element. Presses aren't state writes, so `ChangeGate` doesn't see them. The source is the agent's `ChangeSource.withSource` scope when set (an agent's `press` is a `click()`), else `ChangeSource.of(event)`, where `detail == 0` marks a keyboard click.
  - `ui.open` / `ui.close`: for dialogs and the palette, whose bindings exist only while open, from new `AgentRegistry.watchMounts` events. An unmount is confirmed a tick later, so a re-render that remounts the binding isn't a close. A menu's binding stays on its trigger, so menus (`openStateKinds`) report from committed changes to their `open` key instead, app-made ones included, so every open gets its close. `uninstall` closes out open dialogs and tab views, and hiding the page reports the tab views so far.
  - `ui.view`: tab dwell time, measured between changes and on unmount.
  - `command.run`: a new `AgentRegistry.reportCommand` call in `CommandPalette.run`, after the close is accepted, by `Command.id`.
- **Off unless installed, values off unless asked for.** Nothing is recorded before `install` or after `uninstall`, so consent stays with the app. Values are sent only for `valueKinds`, by default tabs, whose values are labels. Secret fields go through the registry's redaction. App-made (`Programmatic`) changes are left out by default, since they say nothing about use.
- **Merging, not one event per keystroke.** Changes to the same ref and key within `mergeWindowMillis` (1s), or in one pointer gesture, send one event with the last value.
- **Sampling is per page session**, decided at install, with a random session id that is never tied to a person.
- **The browser posts to a same-origin relay, not to OTLP.** `UiTelemetrySink.relay()` uses `fetch`, flushing on `visibilitychange`/`pagehide`. `sendBeacon` can't send `application/json` cross-origin, and the Datadog Agent's OTLP port neither accepts a page's origin nor should face the internet. Batches are split into requests of at most 48 KiB of UTF-8 and 200 events, the relay's limits. `keepalive` is set while the requests of one flush stay within 60 KiB, under the 64 KiB the browser allows in flight; later requests go without it.
- **`UiTelemetryRelay` (in `uni`) is an untrusted input boundary.** It accepts only `POST` with `Content-Type: application/json`, which browsers send cross-site only after a preflight it doesn't answer, so another site can't post from its visitors' browsers. An `Origin` header, when present, must name the request's `Host`. Bodies are at most 64 KiB with at most 200 events. Names must be `ui.*` or `command.*`, keys lower-case `[a-z0-9._]`, values scalars with strings cut to 256 characters. It adds `session.id` (hex only) and the user agent, and drops anything else. It doesn't authenticate or rate-limit; apps put their own filters before it. Events become `Telemetry.emitEvent`, which `OtlpExporter` sends to `/v1/logs` as INFO records with `eventName` and an `event.name` attribute.
- **Datadog RUM through its own SDK.** `UiTelemetrySink.datadogRum()` calls `addAction("ui.press button:save", context)` on `window.DD_RUM` (or an npm `datadogRum` object passed in) with no new dependency, and does nothing when the SDK is absent.
- Alternatives: DOM autocapture with selectors (names break with markup and leak text); posting OTLP from the browser (blocked by CORS, and it would expose the collector); Beacon API (no JSON content type cross-origin, no response).
- Follow-up: Dock panels report no view time, because the Dock binding exposes only its maximized panel.


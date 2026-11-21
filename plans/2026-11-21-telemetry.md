# Telemetry: traces and metrics from Uni's own layers, and UI usage events

## Goals

- Services built on Uni report traces and metrics to Datadog (or any OpenTelemetry backend) without writing metric code.
- It works on every platform Uni runs on: JVM, Scala Native, and Node/browser. Bytecode agents only cover the JVM.
- Spans carry Uni's own meaning: `UserService.getUser` rather than `POST /rpc/...`, plus server and client latency.
- uni-ui apps learn which features and components people use without hand-written tracking calls.
- Teams that already run `dd-java-agent` or the OpenTelemetry Java agent can keep it. Until the `GlobalOpenTelemetry` bridge (a follow-up) exists, the two share a trace id through `traceparent`, but their parent links don't join.

## Background

- A JVM agent instruments libraries it recognizes (Netty, `java.net.http`, JDBC). It can't see Uni's socket server, Native server, Node server, RPC method names, or MCP/LLM calls. Scala Native and Scala.js have no agent mechanism at all.
- Uni already has the choke points such instrumentation needs:
  - every server runs the `RxHttpFilter` chain, which is where `HttpAccessLogFilter` measures each request;
  - the router and `RPCDispatcher` already report the resolved method through `HttpAccessLogFilter.recordRPCCall`;
  - the sync and async HTTP clients are the only entry points for outgoing calls (`send`, `sendStreaming`, `sendSSE`), each with its own retry and redirect loop.
- WebSocket upgrades skip server filters, so WebSocket sessions get no spans in this plan.
- Datadog accepts OTLP: the Datadog Agent's OTLP receiver (ports 4317/4318), or Datadog's OTel Collector distribution. If Uni speaks OTLP, it needs no Datadog-specific code.
- OTLP/HTTP accepts JSON bodies, so a pure-Scala exporter on Uni's HTTP client and JSON support needs no protobuf or OpenTelemetry SDK dependency.
- uni-ui already knows what each component is. `AgentRegistry` holds every bound component's kind, label, and stable ref. Every app-state write goes through `ChangeGate` with a reason and a `ChangeSource` (Pointer, Keyboard, Programmatic, Agent). Secret and hidden marks already redact values and subtrees.
- Rx is asynchronous and has no ambient context, so a thread-local "current span" is lost across async boundaries.

## Design

Lane: Standard. This adds a new public API surface and ships over three PRs. The user approved the direction ("1-4 looks good. go ahead").

### Delivery

- **PR 1:**
  - JVM agent guide (step 1).
  - The `wvlet.uni.telemetry` API.
  - W3C `traceparent` propagation (step 2).
  - Server, client, and RPC spans sent to a pluggable exporter.
  - Plus this note and the ADR.
- **PR 2:**
  - The OTLP/HTTP JSON exporter for traces and metrics, batched with a background flush.
  - HTTP and RPC duration metrics.
  - A Datadog setup page.
- **PR 3:**
  - `UiTelemetry` in uni-ui.
  - The OTLP sink (reusing PR 2) and the Datadog RUM bridge.
- **Follow-ups, out of scope:**
  - a `GlobalOpenTelemetry` bridge for the JVM, which needs the OTel API as a dependency and so goes in its own small module;
  - MCP/LLM GenAI spans;
  - Design lifecycle spans;
  - Rx and Fiber context propagation beyond what PR 1 covers.

### User flow (backend)

- Today, with no code changes: add `-javaagent:` (dd-java-agent or the OTel agent) and set the service name. The docs page covers what it sees, what it misses, and how to reach Datadog.
- With Uni telemetry:
  - bind a `Telemetry` backend, e.g. `Telemetry.otlp("http://localhost:4318").withServiceName("orders")` (PR 2);
  - add `TelemetryFilter` to the server, as the outermost filter next to the access log;
  - opt a client in with `HttpClientConfig.withTelemetry(telemetry)`, which is turned off again with `noTelemetry()`;
  - each request then produces a server span named by the RPC method or route, and each outgoing call from an opted-in client produces a client span with a `traceparent` header.
- It worked when traces show up in Datadog APM under the service name. To turn telemetry off, remove the filter (server spans) or call `noTelemetry()` (client spans).

### Decisions

- **Off by default and close to free.** With no backend bound, `Telemetry.noop` creates no spans and allocates nothing per request. Only the `traceparent` header passes through unchanged.
- **The API lives in `uni` (`wvlet.uni.telemetry`), not uni-core.** HTTP, RPC, and the exporter are in `uni`, and uni-ui depends on `uni`. Nothing in uni-core needs it yet.
- **A small API, not a full OTel SDK:**
  - `TraceContext` (trace id, span id, sampled flag; parses and formats `traceparent`);
  - `Span` (attributes, status, end);
  - `Telemetry` (start a span, record a metric);
  - `SpanExporter` (receives ended spans).
  - Attribute names follow OTel semantic conventions (`http.request.method`, `http.route`, `http.response.status_code`, `rpc.service`, `rpc.method`, `server.address`).
- **Explicit context first, then a thread-local current span.**
  - The server filter stores the span in a request attribute and makes it current while the handler's synchronous part runs. That covers RPC methods that return plain values, which is most of them.
  - The client takes its parent from the current span, unless the request carries an explicit `traceparent` header.
  - `Telemetry.withContext(ctx) { ... }` covers manual hand-offs. Continuations inside an `Rx` don't inherit the current span yet; the docs list this as a limitation.
- **RPC naming shares the call site with the access log, not its state.** Telemetry gets its own `RequestAttribute`, filled where `recordRPCCall` runs today, so it works with or without `HttpAccessLogFilter`. The span name becomes `rpc.service/method`, and the route template takes over when no RPC applies. Arguments are never put on spans.
- **One client span per logical call**, covering its retries and redirects. Each attempt becomes a span event, and the last attempt's status is the span's status. Streaming and SSE calls end their span when the stream ends.
- **Traces are sampled at the root.** A configurable ratio applies when no incoming `traceparent` exists. Otherwise the caller's sampled flag is followed.
- **The exporter is a plug.** PR 1 ships an in-memory exporter (for tests) and a log exporter. OTLP arrives in PR 2, so PR 1 stays reviewable.

### User flow (UI, PR 3)

- The app calls `UiTelemetry.install(sink, policy)` once.
- Components with an agent binding then report:
  - `ui.press` for presses, through one delegated listener that resolves the nearest bound element;
  - `ui.change` for committed changes only (the registry also sees canceled ones), merged per gesture so one drag is one event, and debounced for text input; each carries reason and source;
  - `ui.open` / `ui.close` for dialogs, menus, and the palette;
  - `command.run` for palette commands;
  - `ui.view` with dwell time for tabs, and for Dock panels once the Dock reports the active tab. Its binding exposes only the maximized panel today, so PR 3 adds an active-panel signal.
- Events carry kind, ref, reason, and source. Values are opt-in per component. Secret and hidden marks always apply.
- Events are batched and flushed with `fetch(..., { keepalive: true })` on `visibilitychange`, with sampling and a random session id.
- The OTLP sink posts to a same-origin relay route served by the app's Uni server (`UiTelemetryRelay`), which forwards to the collector. A browser can't post OTLP JSON straight to the Datadog Agent: `sendBeacon` can't set `application/json` cross-origin, and the agent's OTLP port isn't meant to face the public.
- Sinks:
  - OTLP logs, linked to backend traces through `traceparent` on Uni RPC calls;
  - `DatadogRum`, which calls `window.DD_RUM.addAction` through `js.Dynamic` when the RUM SDK is on the page, with no new dependency;
  - a function sink for anything else (PostHog, Amplitude).
- Consent belongs to the app: nothing is sent before `install`, and `uninstall()` stops sending and drops queued events.

## Alternatives and Why Not?

- **Depend on the OpenTelemetry Java SDK.** JVM only, a large dependency tree, against the minimal-dependency rule. A bridge module can come later for teams that run an agent.
- **Only document the agents.** They miss Uni's own servers, RPC names, and every non-JVM platform.
- **Datadog-specific exporter (dd-trace protocol).** It ties Uni to one vendor. OTLP reaches Datadog through its own agent anyway.
- **Full Rx context propagation in PR 1.** It touches every operator in uni-core's Rx. Thread-local plus explicit context covers the common RPC case at a fraction of the risk.
- **DOM autocapture for UI (every click, CSS selectors).** Event names break when markup changes and leak text. Bindings already give stable refs and kinds.

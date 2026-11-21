---
description: Report traces and HTTP duration metrics from Uni services to Datadog or any OpenTelemetry backend, with a JVM agent or with Uni's built-in OTLP tracing on JVM, Scala Native, and Scala.js.
---

# Telemetry

Uni services can report traces to Datadog, or to any backend that speaks
OpenTelemetry, in two ways:

- **Attach a JVM agent.** You change nothing in the code, but it works only on the
  JVM and only for libraries the agent knows.
- **Turn on Uni's built-in tracing.** Uni's own HTTP server, router, RPC
  dispatcher, and HTTP client then create spans on every platform: JVM, Scala
  Native, and Scala.js.

The two work together: both read and write the W3C
[`traceparent`](https://www.w3.org/TR/trace-context/) header, so a request that
crosses services stays in one trace.

## JVM agents

[Datadog's Java tracer](https://docs.datadoghq.com/tracing/trace_collection/automatic_instrumentation/dd_libraries/java/)
(`dd-java-agent`) and the
[OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
instrument the JVM at load time, with no code changes.

### dd-java-agent

Run a [Datadog Agent](https://docs.datadoghq.com/agent/) next to the service and
start the JVM with the tracer:

```bash
java -javaagent:/path/to/dd-java-agent.jar \
  -Ddd.service=orders -Ddd.env=prod -Ddd.version=1.2.0 \
  -jar orders.jar
```

The tracer sends traces to the Datadog Agent on `localhost:8126`. To make sure it
reads and writes W3C headers, so that Uni's own spans and services without the
agent join the same trace, list `tracecontext` in its propagation styles:

```bash
-Ddd.trace.propagation.style=tracecontext,datadog
```

### OpenTelemetry Java agent

The OpenTelemetry agent sends OTLP. A Datadog Agent with its
[OTLP receiver](https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/)
turned on accepts it:

```yaml
# datadog.yaml
otlp_config:
  receiver:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
```

```bash
java -javaagent:/path/to/opentelemetry-javaagent.jar \
  -Dotel.service.name=orders \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -jar orders.jar
```

The same OTLP stream also works with an OpenTelemetry Collector, Grafana Tempo,
Honeycomb, and other OTLP backends.

### What an agent sees in a Uni service

| Part of Uni | Seen by a JVM agent? |
|-------------|----------------------|
| `uni-netty` server | Yes, as Netty HTTP spans named by method and path |
| HTTP client on the JVM (`java.net.http`) | Yes |
| JDBC, AWS SDK, and other libraries the agent supports | Yes |
| The built-in socket server (`Http.server` on the JVM without `uni-netty`) | No |
| RPC method names (`UserService/getUser`) | No: they show up as `POST /UserService/getUser` |
| Scala Native and Scala.js services | No: there is no agent for them |

Uni's built-in tracing covers the rows marked No.

## Built-in tracing

A `Telemetry` instance starts spans and hands them to a `SpanExporter`. Without
an exporter it is disabled, and the server filter and HTTP client skip it at
no cost.

```scala
import wvlet.uni.http.{Http, TelemetryFilter}
import wvlet.uni.telemetry.Telemetry
import wvlet.uni.telemetry.otlp.OtlpConfig

val telemetry = Telemetry()
  .withServiceName("orders")
  .withOtlp(OtlpConfig().withEndpoint("http://localhost:4318"))
```

`withOtlp` sends spans and metrics as OTLP/HTTP JSON to `/v1/traces` and
`/v1/metrics` under the endpoint. Port 4318 is where the Datadog Agent's
[OTLP receiver](#opentelemetry-java-agent) and the OpenTelemetry Collector
listen, so the `datadog.yaml` above works unchanged. Spans are buffered and sent
every 5 seconds (`withFlushIntervalMillis`), in batches of up to 512
(`withMaxBatchSize`). Past 4,096 buffered spans (`withMaxQueueSize`), new spans
are dropped and the count is logged. Call `telemetry.close()` at shutdown to
stop the timer and send what is left.

The exporter works on the JVM, Scala Native, and Node.js. In a browser, the
endpoint must accept the page's origin, which the Datadog Agent does not.

To print spans while developing, use `.withExporter(SpanExporter.log())`
instead.

### Server spans

Add `TelemetryFilter` to the server as the outermost filter. Each request becomes
a server span, and a request with a `traceparent` header continues the caller's
trace:

```scala
import wvlet.uni.http.rpc.{RPCHandler, RPCRouter}

trait OrderApi:
  def getOrder(id: Int): String

class OrderApiImpl extends OrderApi:
  def getOrder(id: Int): String = s"order-${id}"

val server = Http
  .server
  .withPort(8080)
  .withFilter(TelemetryFilter(telemetry))
  .withRxHandler(RPCHandler(RPCRouter.of[OrderApi](OrderApiImpl())))
```

How a span is named:

- An RPC call is named after its method, e.g. `OrderApi/getOrder`, with
  `rpc.system`, `rpc.service`, and `rpc.method` attributes.
- An `@Endpoint` method is named after its route template, e.g.
  `GET /orders/:id`, with an `http.route` attribute.
- Any other request is named after its HTTP method.

Attributes follow the OpenTelemetry
[HTTP](https://opentelemetry.io/docs/specs/semconv/http/http-spans/) and
[RPC](https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans/) semantic
conventions:

- `http.request.method`, `url.path`, `server.address`, and
  `http.response.status_code`.
- A 5xx response marks the span as failed.
- The query string and RPC arguments are never recorded, since they often carry
  tokens or personal data.

### Client spans

`withTelemetry` on an HTTP client traces each call. Every call it makes sends a
`traceparent` header:

```scala
val client = Http.client.withTelemetry(telemetry).newSyncClient
```

How client spans work:

- One call is one span. Retries and redirects are recorded as
  `http.request.resend` events, and the span takes the final response's status.
- A call made while a server span is running becomes that span's child, so a
  request through several services reads as one trace.
- `url.full` leaves out the query string and any user info in the URL.

### Metrics

With an OTLP exporter, the server filter and HTTP client also record duration
histograms, following the OpenTelemetry
[HTTP metrics](https://opentelemetry.io/docs/specs/semconv/http/http-metrics/)
conventions:

| Metric | Unit | Attributes |
|--------|------|------------|
| `http.server.request.duration` | s | `http.request.method`, `http.route` (for `@Endpoint` methods), `rpc.service` and `rpc.method` (for RPC calls), `http.response.status_code`, `error.type` |
| `http.client.request.duration` | s | `http.request.method`, `server.address`, `server.port`, `http.response.status_code`, `error.type` |

What is recorded:

- Durations are measured for every request, including traces that aren't
  sampled.
- Histograms use delta temporality, which Datadog reads directly.
- To record metrics without traces, set only a metric exporter, e.g.
  `Telemetry().withMetricExporter(OtlpExporter(config))`. No `traceparent` is
  sent then.
- Your own measurements go through `telemetry.recordHistogram(instrument, value, attributes)`.

### Spans in your own code

`Telemetry.trace` wraps a block in a span. It records an exception and rethrows it:

```scala
import wvlet.uni.telemetry.Telemetry

def reindex(): Int = telemetry.trace("reindex") { span =>
  span.setAttribute("index.shards", 4)
  42
}
```

Inside a traced request, `Telemetry.currentSpan` returns the server span, and so
does the request's `TelemetryFilter.CurrentSpan` attribute.

### Limits

- **The current span lives on the thread.** It is set while a handler runs its
  synchronous part, which covers RPC and `@Endpoint` methods that return a plain
  value. Work that continues in an `Rx` callback on another thread doesn't see it.
  Pass the span on explicitly there, or wrap the code in
  `Telemetry.withSpan(span) { ... }`.
- **WebSocket upgrades don't run server filters**, so WebSocket sessions get no
  span.
- **A JVM agent and Uni share trace ids, not parent links.** They don't see each
  other's current span. The `traceparent` header still keeps both sets of spans
  in one trace, but some spans may show as siblings instead of parent and child.
- **A failed send is logged once and dropped.** Telemetry never blocks or fails
  a request. The HTTP client's usual retries for 5xx and 429 still apply.
- To send spans somewhere other than OTLP, implement `SpanExporter.exportSpan`,
  which runs on the thread that ended the span. An exporter that does I/O should
  buffer spans and send them from its own thread, as `OtlpExporter` does.

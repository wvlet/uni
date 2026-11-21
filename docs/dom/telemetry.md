---
description: Find out which uni-ui components and features people use, with no tracking code in the app. UiTelemetry records presses, changes, dialogs, tabs, and commands by stable component refs, and sends them to a Uni server, Datadog RUM, or your own analytics.
---

# UI Telemetry

`UiTelemetry` records which components and features people use, so the app needs no
hand-written tracking calls. It reads the same component registry as [`UiAgent`](./agents).
Each event therefore names its component by kind and stable ref, such as `button:save`, rather
than by a CSS selector that breaks when the markup changes.

```scala
import wvlet.uni.ui.telemetry.{UiTelemetry, UiTelemetrySink}

val uiTelemetry = UiTelemetry.install(UiTelemetrySink.relay())
```

Nothing is recorded before `install` or after `uninstall()`. If the app needs consent, ask for
it first and install afterwards.

## Events

| Event | When | Attributes |
|-------|------|------------|
| `ui.press` | A button or menu item is clicked, or pressed with Enter or Space | `ui.kind`, `ui.ref`, `ui.source` (`pointer` or `keyboard`) |
| `ui.change` | A component commits a change through `onChange` | `ui.kind`, `ui.ref`, `ui.key`, `ui.reason`, `ui.source` (`pointer`, `keyboard`, or `agent`), and `ui.value` for `valueKinds` |
| `ui.open` / `ui.close` | A dialog, menu, or command palette mounts or unmounts | `ui.kind`, `ui.ref`, and `duration.ms` on close |
| `ui.view` | A tab stops being selected | `ui.ref`, `duration.ms`, and the tab's label as `ui.value` |
| `command.run` | A command palette runs a command | `command.id`, `ui.source` |

What gets recorded and how:

- **Stable names.** Refs come from `UiAgent.ref("name")` when the app sets one, else from the
  kind and label. Commands are named by their `Command` id.
- **Merged bursts.** A pointer drag, or typing into one field, becomes one `ui.change` with the
  last value. Changes to the same component and key within `mergeWindowMillis` (1 second) merge.
- **Only what people and agents do.** Changes the app's own code makes are left out, unless
  `withProgrammaticChanges(true)` is set.
- **Hidden parts stay hidden.** Components inside `UiAgent.hidden`, `hidden`, `inert`, or
  `aria-hidden="true"` are left out, as they are for agents.
- **No values by default.** Values can be what people typed or picked, so only tab labels are
  recorded. `withValueKinds("tabs", "select")` adds more kinds. Fields marked `UiAgent.secret`,
  and password fields, are always redacted.

## Configuration

```scala
import wvlet.uni.ui.telemetry.UiTelemetryConfig

val sampled = UiTelemetryConfig(UiTelemetrySink.relay())
  .withSampleRatio(0.25)              // a quarter of page sessions record anything
  .withValueKinds("tabs", "select")
  .withAttribute("app.version", "1.4.0")
  .install()
```

How batches are sent:

- Events are batched and sent every 5 seconds (`withFlushIntervalMillis`), and right away when
  the page is hidden or closed.
- Each page session gets a random `sessionId`, which is sent with each batch. Nothing ties it to
  a person.
- `uiTelemetry.flush()` sends the queue now.

## Sinks

### A Uni server, then OTLP

`UiTelemetrySink.relay()` posts batches with `fetch` and `keepalive` to
`/_uni/telemetry/ui` on the page's own server. The last batch is delivered even while the page
closes. On the server, `UiTelemetryRelay` passes each event to the service's `Telemetry`. With
`withOtlp`, the events go to the OTLP `/v1/logs` endpoint of the Datadog Agent or an OpenTelemetry
Collector, next to the server's traces and metrics:

```scala skip
import wvlet.uni.http.{Http, UiTelemetryRelay}
import wvlet.uni.telemetry.Telemetry
import wvlet.uni.telemetry.otlp.OtlpConfig

val telemetry = Telemetry().withServiceName("web").withOtlp(OtlpConfig())

Http.server.withFilter(UiTelemetryRelay(telemetry)).withRxHandler(app).start()
```

The browser can't post OTLP to the Datadog Agent itself: the Agent doesn't accept a page's
origin and shouldn't face the internet. The relay is the only endpoint that does.

Anyone who can load the page can reach that endpoint, so the relay accepts only what
`UiTelemetry` sends:

- At most 64 KiB and 200 events per request.
- Event names under `ui.` or `command.`.
- Lower-case attribute keys, string values cut to 256 characters, and no nested values.

For a public page, put authentication or rate limiting in front of it.

### Datadog RUM

If the page already loads the
[Datadog browser SDK](https://docs.datadoghq.com/real_user_monitoring/browser/),
`UiTelemetrySink.datadogRum()` reports each event as a RUM custom action. The action is named
after the event and its ref, for example `ui.press button:save`, and the attributes go in its
context. RUM's own click tracking names actions from the text on the page; these names come
from the component's ref instead.

```scala
val rum = UiTelemetry.install(UiTelemetrySink.datadogRum())
```

By default it uses the `window.DD_RUM` global of the CDN script. If you import the SDK from
npm, pass the `datadogRum` object instead. If the SDK isn't on the page, nothing is sent.

### Your own

`UiTelemetrySink(batch => ...)` hands each batch to any analytics SDK, and
`UiTelemetrySink.all(a, b)` sends to several sinks.

## Limits

- **Only bound components are seen.** That covers uni-ui components that agents can see.
  A plain `div` with an `onclick` handler isn't recorded.
- **Dock panels report no view time yet.** The Dock reports only its maximized panel, not
  which tab is active.

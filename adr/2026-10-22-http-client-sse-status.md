# HTTP client SSE: status-aware streaming and event-handler dispatch

## Context

Upstream LLM provider streaming, the first consumer being the td-llm-proxy migration from Airframe, needs three things from the HTTP client:

- events delivered as they arrive;
- the upstream status, headers, and error body, so the caller can classify a failure;
- exactly one request, because a retry or replay sends a second billable completion.

Through 2026.1.30, `HttpAsyncChannel.sendStreaming` returned only body chunks. As a result, `sendSSE` could not call `onConnect` and completed normally on a 401. Sync `send` buffered every body and never used `Request.eventHandler`. Airframe's client returns from sync `send` at the response headers and delivers event-stream bodies to the handler on a background thread, and consumers rely on that.

## Decision

- `HttpAsyncChannel` gains `sendStreaming(request, config, onResponse)`.
  - `onResponse` receives the status and headers (no body) before the first chunk.
  - A non-2xx status fails the stream with `HttpException.fromResponse(...)`, which carries the fully read error body.
  - The default implementation is built on `send` (buffered, one chunk), so third-party channels keep compiling and stay correct.
  - JVM and Fetch override it to stream.
  - The two-argument `sendStreaming` is unchanged in meaning.
- `sendSSE` is built on the new overload: `onConnect`, `onEvent`, `onError` (including the non-2xx `HttpException`), and `onCompletion`. It is never retried, whatever the client's retry setting.
- Sync `send` with an event handler:
  - The JVM channel leaves a 2xx `text/event-stream` body unread and exposes it as `Response.events`.
  - `DefaultHttpSyncClient` dispatches the events to the handler after retry and redirect resolution, so `onConnect` fires once, for the final response. It then returns the response with `events` cleared, because the body can be read only once.
  - Non-2xx responses keep the existing behavior: an `HttpException` with status and body, and no handler callbacks.
  - Without a handler, SSE bodies are still buffered.
- `ServerSentEventParser.feed(Array[Byte])` carries an incomplete trailing UTF-8 sequence over to the next chunk. `parseStream` creates a new parser per subscription.

## Consequences

- JVM streaming (`JavaHttpChannelHelper.streamChunks`) starts reading only after the subscriber is registered. It relies on `RxVar.foreachEvent` registering the subscriber before emitting the current value (`None`). Starting the reader earlier, as the old two-argument `sendStreaming` did, drops chunks that arrive before subscription.
- Chunks go through the `RxVar` as `Option[Array[Byte]]`, never as parsed events. `RxVar.set` skips a value equal to the current one, so two identical consecutive events would be lost; arrays compare by reference.
- `Rx.queue` (`RxQueue`) was not used: `RxRunner` re-polls an `RxSource`'s `next` in a synchronous loop.
- Tests of async streams must wait for a terminal event (`RxRunner` plus a bounded queue), not use `Rx.toSeq`, which can return before the stream completes.
- Cancelling an SSE subscription does not abort an in-flight JVM body read; the reader stops at end of stream. Abort-on-cancel and `Last-Event-ID` reconnect are open follow-ups.
- Native curl and Electron use the buffered default.

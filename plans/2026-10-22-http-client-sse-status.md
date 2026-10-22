# Status-aware SSE streaming in the HTTP client

## Goals

Make Uni's HTTP client usable as an upstream SSE client for LLM provider streaming (the td-llm-proxy Airframe-to-Uni migration):

- Stream provider events as they arrive, without buffering the whole completion.
- Expose the upstream status, headers, and error body so a caller can classify a failed stream.
- Never issue a second request (a second completion request is billable).
- Keep the request's `ServerSentEventHandler` lifecycle: `onConnect`, `onEvent`, `onError`, `onCompletion`.
- Keep non-streaming `send` behavior unchanged.

## Background

Verified against 2026.1.30 with a loopback JDK server:

1. Sync `send(Request.get(url).withEventHandler(h))` returns 200 but never calls `h.onEvent`: `JavaHttpChannel.send` buffers the body with `readAllBytes`, and nothing reads `request.eventHandler`.
2. Async `sendSSE` never calls `onConnect`: it is built on `HttpAsyncChannel.sendStreaming`, which returns only body chunks. The code documents this limitation.
3. Async `sendSSE` completes normally for an upstream `401 application/json`. The status and the error body are lost.

Other observations:

- `sendSSE` never calls `onCompletion`.
- `JavaHttpAsyncChannel.sendStreaming` starts the request when called, not when subscribed. It publishes chunks through an `Rx.variable`, so chunks that arrive before the subscriber attaches are dropped.
- Each chunk is decoded with `String(chunk, "UTF-8")`. A multi-byte character split across two chunks is corrupted.
- Airframe's `JavaHttpClientChannel` (the proxy's current stack) returns from sync `send` once the headers arrive. For an event-stream response it calls `onConnect` and reads the events on a background thread. `FireworksService` relies on this. It checks a non-200 status and body itself and does its terminal bookkeeping in `onCompletion`/`onError`.

## Design

Lane: **Standard**. This adds a public channel API and new sync behavior when an event handler is set. The launch prompt authorizes the fix and the PR (no merge).

### Channel API

Add a status-aware overload to `HttpAsyncChannel`:

```scala
def sendStreaming(
    request: HttpRequest,
    config: HttpClientConfig,
    onResponse: HttpResponse => Unit
): Rx[Array[Byte]]
```

- `onResponse` receives the status and headers (no body) once, before any chunk.
- On a non-2xx status, the channel reads the bounded error body and fails the stream with `HttpException.fromResponse(responseWithBody)`. No chunk is emitted.
- The default implementation is built on `send` (buffered, single chunk). Existing third-party channels therefore still compile and behave correctly; they just don't stream.
- Overrides that stream for real:
  - JVM (`JavaHttpAsyncChannel`): the request starts on subscription, not on call, so no chunk is lost.
  - JS (`FetchChannel`): the Fetch API exposes status and headers before the body.
- Native curl and Electron use the default; they already buffered.

### Async `sendSSE`

- Built on the new overload. `onResponse` is wired to `handler.onConnect`.
- Calls `onEvent` for each event, `onError` on failure (including the non-2xx `HttpException`), and `onCompletion` at the end of the stream.
- No retry, as before. A stream that has started cannot be replayed without a second billable request.

### Sync `send` with an event handler (Airframe-compatible)

- When `request.eventHandler` is set and the response is a 2xx `text/event-stream`, `JavaHttpChannel` leaves the body unread. It returns a header-only `Response` whose `events` reads and parses the body when subscribed.
- `DefaultHttpSyncClient.send` resolves retries and redirects as before. The streaming response is 2xx, so it is never retried. Then, for a request with a handler, the client:
  - calls `onConnect(response)` once with the final response;
  - for a streamed event response, runs `response.events` on a blocking thread, calling `onEvent`, then `onCompletion` or `onError`;
  - for a buffered 2xx `text/event-stream` body (platforms that don't stream), parses the body and dispatches the same callbacks before returning.
- `send` returns at the response headers. A non-2xx or non-SSE response is fully buffered and returned as before; the caller classifies the status and body, as `FireworksService` does.
- Without an event handler nothing changes: SSE bodies are still buffered into `content`.
- `noRetry` keeps retries off. Event dispatch happens after the retry loop, so a retried attempt never reaches the handler.

### Shared parsing

`ServerSentEventParser` gets a helper that turns `Rx[Array[Byte]]` into `Rx[ServerSentEvent]`:

- It carries an incomplete trailing UTF-8 sequence over to the next chunk.
- It flushes the final event at end of stream.

## Tests

Add `uni/.jvm/src/test/scala/wvlet/uni/http/HttpClientSSETest.scala`. It uses a loopback `com.sun.net.httpserver` server with a `CountDownLatch` body barrier, waits on terminal signals through `RxRunner` and a bounded queue (not `Rx.toSeq`), and counts requests to the server.

- Sync with handler: `onConnect(200)`, every event (including a multi-byte character split across chunks), `onCompletion`. `send` returns before the body is released.
- Sync with a handler and a 401 JSON response: the response keeps its status and body; no `onEvent`; one request.
- Async `sendSSE` with a 401 event-stream response: `onConnect(401)`, `onError`, and the stream fails with `HttpException`.
- Async `sendSSE` with a 401 JSON response: fails with `HttpException`, whose `statusCode` is 401 and whose content carries the error body; one request with `noRetry` and with default retry.
- Async `sendSSE` success: events, then `onCompletion`; events sent before subscription are not lost.
- A parser unit test for UTF-8 split across chunks (shared, cross-platform).

## Alternatives and why not

- **Return `Rx[HttpResponse]` with `events` from the async channel.** This shape is more general but needs a multi-value Rx source per response, which requires more new machinery. It also does not match the existing chunk-based `sendStreaming` that `sendSSE` builds on.
- **Change the existing `sendStreaming` to fail on non-2xx.** This is a silent behavior change for current byte-stream callers, and it still cannot deliver headers.
- **Dispatch sync events inside the channel (as in Airframe).** `onConnect` would then fire for every retried or redirected attempt. Dispatching in the client fires once, for the final response.

## Out of scope

- Server-side SSE cancellation. Client-side cancellation does not abort an in-flight body read; the reader stops at end of stream.
- Retry or reconnect (`Last-Event-ID`) for SSE.
- The broader proxy migration.

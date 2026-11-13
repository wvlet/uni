# Abort JVM and Native streaming requests on client cancel

## Goals

- When a client on the JVM or Scala Native cancels the `Rx` from `rpc.stream` or `sendSSE` (unsubscribe, `take(n)`), the HTTP request is aborted, so the server sees the connection close and cancels the method's `Rx`. #883 did this for fetch, Electron, and Netty.
- Close the known limitation in ADR 2026-11-06.

## Background

- Streaming RPC uses the async client: `sendSSE` → `HttpAsyncChannel.sendStreaming(request, config, onResponse)`. The sync `JavaHttpChannel` / `CurlChannel` only stream to an `ServerSentEventHandler`, which has no cancel path, so the work is in the async channels.
- JVM: `JavaHttpAsyncChannel` already streams. It reads the body on `RxScheduler.blocking`, but nothing stops the read on cancel. A `started` flag is also shared across subscriptions, so a second subscription never sends a request.
- Native: `CurlAsyncChannel` doesn't stream at all. It falls back to the default, which buffers the whole body with `curl_easy_perform`, so a streamed RPC on Native returns nothing until the server stream ends, and an endless stream never returns. There is nothing to abort until the client streams.
- #847 (`fix/curl-perform-blocking`) is already on `main`. Its constraint still holds: while `curl_easy_perform` is `@blocking`, curl callbacks may touch only malloc'd memory, never the GC heap.
- The Native server already cancels the SSE `Rx` when the client disconnects (NativeHttpServer), and so does Netty (test in NettyServerTest).

## Design

Lane: Standard. Native streaming is new client behaviour (chunks arrive as they are received instead of once at the end) and needs new libcurl bindings.

**Flow (app author):** nothing changes in call sites. On the JVM and Native, cancelling `rpc.stream(...)` now stops the server stream within one network round trip, as on JS. On Native, events now arrive as the server sends them.

**JVM (`JavaHttpAsyncChannel`)**
- Each subscription sends its own request, the same way `FetchChannel.streamResponse` does (`Rx.single(()).flatMap`), which also fixes the shared `started` flag.
- `onCancel` cancels the pending `sendAsync` future, closes the body `InputStream`, and interrupts the reader if it is blocked in `read`. An error caused by the cancel is not delivered.

**Native (`CurlAsyncChannel`)**
- Give it a real `sendStreaming` that drives one easy handle through the libcurl **multi** interface on a `RxScheduler.blocking` thread: `curl_multi_perform`, drain the received bytes into the `Rx`, then wait in `curl_multi_poll` (`@blocking`, 100 ms timeout).
- Callbacks still write only into malloc'd buffers, and Scala code drains them between polls, so the #847 GC constraint holds.
- `onCancel` sets a flag. The loop sees it within one poll interval, removes the handle, and cleans up, which closes the connection.
- `onResponse` gets called once the final header block is complete. A non-2xx response is read fully and fails with `HttpException`, as on the JVM.
- Both `sendStreaming` overloads use it. Plain `send` keeps `curl_easy_perform`.

**Tests and docs**
- JVM: a NettyServerTest case using the JVM async client, the way NodeServerTest's cancel test does: an endless `onCancel`-tagged stream, cancel after the first event, then assert that the server cancelled.
- Native: the same case against `NativeServer`, plus a check that chunks arrive before the stream ends.
- Update ADR 2026-11-06 (Consequences) and the Native bullet in `docs/http/client.md` ("delivers the whole body as a single chunk").

## Alternatives and Why Not?

- **Run `curl_easy_perform` on a thread and abort from the write/progress callback.** The callback would have to read a cancel flag and hand chunks to Scala while `@blocking`. That means either touching the GC heap (unsafe after #847) or adding C-side locking. The multi loop keeps all Scala work outside curl.
- **`CURLOPT_XFERINFOFUNCTION` returning non-zero on cancel.** Same problem: it only aborts, and it still needs a streaming path to deliver chunks.
- **Leave Native buffered and only document it.** Streaming RPC on Native would stay unusable for long or endless streams, and a cancel would still have nothing to abort.

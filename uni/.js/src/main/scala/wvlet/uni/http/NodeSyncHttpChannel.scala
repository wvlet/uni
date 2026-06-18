/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.http

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.*

/**
  * Synchronous HTTP channel for Node-compatible runtimes, implemented via `worker_threads` +
  * `Atomics.wait` on a `SharedArrayBuffer`. JavaScript has no native sync HTTP API; this is the
  * modern "wait until a worker signals" pattern that production libraries (e.g. `sync-fetch`) use
  * internally. Verified on Node.js, Bun, and Deno.
  *
  * Flow:
  *   - Main thread allocates a `SharedArrayBuffer`, spawns a `new Worker(code, { eval: true })`,
  *     and calls `Atomics.wait` to block.
  *   - Worker performs an async `fetch`, encodes the response into the shared buffer, then
  *     `Atomics.notify`s the main thread.
  *   - Main thread decodes the response and returns it synchronously.
  *
  * Limitations:
  *   - Requires a Node-compatible runtime (`worker_threads`, `SharedArrayBuffer`, `Atomics`).
  *     Browser sync HTTP is unrecoverable (sync XHR is deprecated and `worker_threads` doesn't
  *     exist on the web). `JSHttpChannelFactory.newChannel` detects browsers and throws before
  *     constructing this channel.
  *   - Response size is capped by the `SharedArrayBuffer` (`HttpClientConfig.maxResponseBytes`,
  *     default 16 MB). Larger responses surface as `HttpException` from the worker.
  */
class NodeSyncHttpChannel extends HttpChannel:

  import NodeSyncHttpChannel.*

  override def send(request: HttpRequest, config: HttpClientConfig): HttpResponse =
    val maxResponseBytes = config.maxResponseBytes
    val sab       = js.Dynamic.newInstance(SharedArrayBufferCtor)(HeaderBytes + maxResponseBytes)
    val stateWord = Int32Array(sab.asInstanceOf[ArrayBuffer], OffsetState, 1)

    // A single overall request timeout. `fetch`'s AbortController can't separate connect from
    // read, so mirror JavaHttpChannel (which applies readTimeoutMillis as the request timeout):
    // prefer readTimeoutMillis, fall back to connectTimeoutMillis, 0 means no timeout.
    val effectiveTimeoutMillis =
      (
        if config.readTimeoutMillis > 0 then
          config.readTimeoutMillis
        else
          config.connectTimeoutMillis
      ).toInt

    val requestData = js
      .Dynamic
      .literal(
        method = request.method.name,
        uri = request.fullUri,
        headers =
          request
            .wireHeaders
            .entries
            .map { case (k, v) =>
              js.Array(k, v)
            }
            .toJSArray,
        body =
          if request.content.isEmpty then
            js.undefined
          else
            // toTypedArray yields a fresh Int8Array backed by its own ArrayBuffer (not the
            // shared one); the worker's `fetch` accepts any ArrayBufferView as a body.
            request.content.toContentBytes.toTypedArray
        ,
        sab = sab,
        timeoutMillis = effectiveTimeoutMillis,
        maxResponseBytes = maxResponseBytes
      )

    val worker =
      js.Dynamic
        .newInstance(workerThreads.Worker)(
          WorkerScript,
          js.Dynamic.literal(eval = true, workerData = requestData)
        )

    // Cap the wait with a safety timeout so a crashed or hung worker can't block the main
    // thread forever. The worker aborts its fetch at effectiveTimeoutMillis, so give it a
    // grace period on top of that before we give up. 0 means no timeout was configured, so
    // wait forever (`js.undefined`).
    val waitTimeoutMillis =
      if effectiveTimeoutMillis > 0 then
        effectiveTimeoutMillis + WorkerGraceMillis
      else
        0
    val waitTimeout: js.Any =
      if waitTimeoutMillis > 0 then
        waitTimeoutMillis
      else
        js.undefined

    try
      // Block until the worker stores a non-zero value at stateWord[0]. `applyDynamic` is
      // needed because plain `.wait(...)` resolves to `java.lang.Object.wait` on Scala 3.
      val waitResult = js
        .Dynamic
        .global
        .Atomics
        .applyDynamic("wait")(stateWord, 0, StatePending, waitTimeout)
        .asInstanceOf[String]
      if waitResult == "timed-out" then
        throw HttpException.connectionFailed(
          s"sync HTTP request timed out after ${waitTimeoutMillis} ms"
        )

      val state = stateWord(0)

      // DataView over the shared buffer for reading the small header (status, lengths).
      val view = DataView(sab.asInstanceOf[ArrayBuffer])

      if state == StateError then
        val errLen = view.getInt32(OffsetStatusOrErrLen, littleEndian = true)
        throw HttpException.connectionFailed(String(readBytes(sab, HeaderBytes, errLen), "UTF-8"))

      if state != StateSuccess then
        throw HttpException.connectionFailed(s"worker returned unexpected state ${state}")

      val status         = view.getInt32(OffsetStatusOrErrLen, littleEndian = true)
      val headersJsonLen = view.getInt32(OffsetHeadersLen, littleEndian = true)
      val bodyLen        = view.getInt32(OffsetBodyLen, littleEndian = true)

      val headersJson = String(readBytes(sab, HeaderBytes, headersJsonLen), "UTF-8")
      val bodyBytes   = readBytes(sab, HeaderBytes + headersJsonLen, bodyLen)

      val headers = decodeHeaders(headersJson)
      val content =
        if bodyBytes.isEmpty then
          HttpContent.Empty
        else
          HttpContent.bytes(bodyBytes)
      Response(HttpStatus.ofCode(status), headers, content)
    finally
      // Always terminate — Atomics.wait returns once the worker stores + notifies, but the
      // Worker handle isn't garbage-collected until we explicitly terminate.
      worker.terminate()
    end try
  end send

  private def readBytes(sab: js.Dynamic, offset: Int, length: Int): Array[Byte] =
    if length == 0 then
      Array.emptyByteArray
    else
      // Int8Array view over the shared buffer; `.toArray` does a bulk copy into a JVM array.
      Int8Array(sab.asInstanceOf[ArrayBuffer], offset, length).toArray

  private def decodeHeaders(json: String): HttpMultiMap =
    // The worker serialises headers as a flat `[[k,v],[k,v],…]` array (not an object) so
    // duplicate header names (e.g. multiple `Set-Cookie`) survive the round-trip.
    val parsed  = js.JSON.parse(json).asInstanceOf[js.Array[js.Array[String]]]
    val builder = HttpMultiMap.newBuilder
    parsed.foreach { entry =>
      builder.add(entry(0), entry(1))
    }
    builder.result()

end NodeSyncHttpChannel

object NodeSyncHttpChannel:

  // Shared buffer layout (little-endian). The Scala side reads these; the worker writes them.
  // The values are single-sourced into the worker via the constants block in WorkerScript.
  //   [0..4)   state (i32):          StatePending / StateSuccess / StateError
  //   [4..8)   statusOrErrLen (i32): HTTP status (success) | error-message byte length (error)
  //   [8..12)  headersJsonLen (i32): headers-JSON UTF-8 byte length (success only)
  //   [12..16) bodyLen (i32):        body byte length (success only)
  //   [16..)   headersJson ++ body (success) | errorMessage (error)
  private final val OffsetState          = 0
  private final val OffsetStatusOrErrLen = 4
  private final val OffsetHeadersLen     = 8
  private final val OffsetBodyLen        = 12
  private final val HeaderBytes          = 16

  private final val StatePending = 0
  private final val StateSuccess = 1
  private final val StateError   = 2

  // Extra time granted to the worker beyond its own fetch-abort timeout before the main
  // thread gives up waiting.
  private final val WorkerGraceMillis = 5000

  // Load Node's `worker_threads` lazily at call time (see NodeModules.builtin for why).
  private[http] def workerThreads: js.Dynamic = NodeModules.builtin("worker_threads")

  /**
    * True on a Node-compatible runtime (Node, Bun, Deno). Browsers — including web workers — lack
    * it.
    */
  def isNode: Boolean = NodeModules.isNode

  // `SharedArrayBuffer` has no typed binding in scalajs-library, so it's accessed via js.Dynamic.
  // Scala.js disallows loading `js.Dynamic.global` itself as a value, so resolve it lazily.
  private def SharedArrayBufferCtor = js.Dynamic.global.SharedArrayBuffer

  /**
    * Worker source. Receives the request via `workerData`, performs an async fetch, encodes the
    * response into the shared buffer, then calls `Atomics.notify` to wake the parent. The leading
    * constants block is interpolated from the Scala-side layout constants so the buffer contract
    * stays single-sourced.
    *
    * `workerData` is obtained via `await import('node:worker_threads')` rather than `require(...)`:
    * Node's `eval` workers are CommonJS (so `require` works there), but Deno's `eval` workers are
    * ESM and have no `require`. Dynamic `import('node:...')` works in Node, Bun, and Deno alike.
    */
  private val WorkerScript =
    s"""
    const HEADER_BYTES = ${HeaderBytes};
    const OFF_STATUS = ${OffsetStatusOrErrLen};
    const OFF_HEADERS_LEN = ${OffsetHeadersLen};
    const OFF_BODY_LEN = ${OffsetBodyLen};
    const STATE_SUCCESS = ${StateSuccess};
    const STATE_ERROR = ${StateError};
    """ + """
    (async () => {
      const { workerData } = await import('node:worker_threads');
      const { method, uri, headers, body, sab, timeoutMillis, maxResponseBytes } = workerData;
      const stateView = new Int32Array(sab, 0, 1);
      const ints = new DataView(sab);
      const encoder = new TextEncoder();

      const setI32 = (offset, value) => ints.setInt32(offset, value, true);

      const finishError = (message) => {
        const bytes = encoder.encode(String(message));
        const len = Math.min(bytes.length, sab.byteLength - HEADER_BYTES);
        new Uint8Array(sab, HEADER_BYTES, len).set(bytes.subarray(0, len));
        setI32(OFF_STATUS, len);
        Atomics.store(stateView, 0, STATE_ERROR);
        Atomics.notify(stateView, 0);
      };

      try {
        // redirect: 'manual' so DefaultHttpSyncClient owns redirect handling
        // (noFollowRedirects / maxRedirects / method rewriting), matching FetchChannel.
        // Node's fetch exposes the real 3xx status + Location on a manual redirect.
        const init = { method, headers: new Headers(headers), redirect: 'manual' };
        if (body !== undefined && body !== null) init.body = body;
        if (timeoutMillis > 0) {
          const controller = new AbortController();
          init.signal = controller.signal;
          setTimeout(() => controller.abort(), timeoutMillis);
        }
        const resp = await fetch(uri, init);

        // Stream the body so an oversized response is rejected before it is fully buffered,
        // rather than letting `arrayBuffer()` materialise it all and risk an OOM.
        let bodyBuf = new Uint8Array(0);
        if (resp.body) {
          const reader = resp.body.getReader();
          const parts = [];
          let received = 0;
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            received += value.length;
            if (received > maxResponseBytes) {
              finishError(`response too large: exceeded ${maxResponseBytes} bytes`);
              return;
            }
            parts.push(value);
          }
          bodyBuf = new Uint8Array(received);
          let offset = 0;
          for (const part of parts) { bodyBuf.set(part, offset); offset += part.length; }
        }

        // Header list preserves duplicate keys.
        const headerList = [];
        resp.headers.forEach((v, k) => { headerList.push([k, v]); });
        const headersJson = encoder.encode(JSON.stringify(headerList));

        const totalLen = headersJson.length + bodyBuf.length;
        if (totalLen > maxResponseBytes) {
          finishError(`response too large: ${totalLen} > ${maxResponseBytes} bytes`);
          return;
        }

        setI32(OFF_STATUS, resp.status);
        setI32(OFF_HEADERS_LEN, headersJson.length);
        setI32(OFF_BODY_LEN, bodyBuf.length);
        new Uint8Array(sab, HEADER_BYTES, headersJson.length).set(headersJson);
        new Uint8Array(sab, HEADER_BYTES + headersJson.length, bodyBuf.length).set(bodyBuf);

        Atomics.store(stateView, 0, STATE_SUCCESS);
        Atomics.notify(stateView, 0);
      } catch (e) {
        finishError(e && e.stack ? e.stack : (e && e.message ? e.message : String(e)));
      }
    })();
  """

end NodeSyncHttpChannel

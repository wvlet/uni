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
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.*

/**
  * Synchronous HTTP channel for Node.js, implemented via `worker_threads` + `Atomics.wait` on a
  * `SharedArrayBuffer`. Node.js has no native sync HTTP API; this is the modern "wait until a
  * worker signals" pattern that production libraries (e.g. `sync-fetch`) use internally.
  *
  * Flow:
  *   - Main thread allocates a `SharedArrayBuffer`, spawns a `new Worker(code, { eval: true })`,
  *     and calls `Atomics.wait` to block.
  *   - Worker performs an async `fetch`, encodes the response into the shared buffer, then
  *     `Atomics.notify`s the main thread.
  *   - Main thread decodes the response and returns it synchronously.
  *
  * Limitations:
  *   - Node.js only. Browser sync HTTP is unrecoverable (sync XHR is deprecated and
  *     `worker_threads` doesn't exist on the web). `JSHttpChannelFactory.newChannel` should detect
  *     non-Node environments and throw before constructing this channel.
  *   - Response size is capped by the `SharedArrayBuffer` (default 16 MB). Larger responses surface
  *     as `HttpException` from the worker.
  */
class NodeSyncHttpChannel(maxResponseBytes: Int = 16 * 1024 * 1024) extends HttpChannel:

  import NodeSyncHttpChannel.*

  override def send(request: HttpRequest, config: HttpClientConfig): HttpResponse =
    val sab       = js.Dynamic.newInstance(SharedArrayBufferCtor)(HeaderBytes + maxResponseBytes)
    val stateWord = Int32Array(sab.asInstanceOf[ArrayBuffer], OffsetState, 1)

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
        connectTimeoutMillis = config.connectTimeoutMillis,
        readTimeoutMillis = config.readTimeoutMillis,
        maxResponseBytes = maxResponseBytes
      )

    val worker =
      js.Dynamic
        .newInstance(workerThreads.Worker)(
          WorkerScript,
          js.Dynamic.literal(eval = true, workerData = requestData)
        )

    // Cap the wait with a safety timeout so a crashed or hung worker can't block the main
    // thread forever. The worker aborts its fetch at max(connect, read) timeout, so give it
    // a grace period on top of that before we give up. A non-positive value means no timeout
    // is configured, so wait forever (`js.undefined`).
    val workerTimeoutMillis = math.max(config.connectTimeoutMillis, config.readTimeoutMillis)
    val waitTimeoutMillis   =
      if workerTimeoutMillis > 0 then
        workerTimeoutMillis + WorkerGraceMillis
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

  // `worker_threads` is imported statically rather than lazily: Scala.js has no synchronous
  // dynamic require, and a static import is exactly what Node ESModule consumers need. Trade-off:
  // a *browser* ESModule bundle that links `JSHttpChannelFactory` (only to reach the browser
  // `NotImplementedError` path) will fail to resolve this Node-only import at load time, so that
  // fallback is never actually reached. That is acceptable — browsers never supported sync HTTP.
  @js.native
  @JSImport("worker_threads", JSImport.Namespace)
  private[http] val workerThreads: js.Dynamic = js.native

  /**
    * Detects Node by the presence of `process.versions.node`. Browser environments — including web
    * workers — lack it.
    */
  def isNode: Boolean =
    !js.isUndefined(js.Dynamic.global.process) &&
      !js.isUndefined(js.Dynamic.global.process.versions) &&
      !js.isUndefined(js.Dynamic.global.process.versions.node)

  // `SharedArrayBuffer` has no typed binding in scalajs-library, so it's accessed via js.Dynamic.
  // Scala.js disallows loading `js.Dynamic.global` itself as a value, so resolve it lazily.
  private def SharedArrayBufferCtor = js.Dynamic.global.SharedArrayBuffer

  /**
    * Worker source. Receives the request via `workerData`, performs an async fetch, encodes the
    * response into the shared buffer, then calls `Atomics.notify` to wake the parent. The leading
    * constants block is interpolated from the Scala-side layout constants so the buffer contract
    * stays single-sourced.
    */
  private val WorkerScript =
    s"""
    const HEADER_BYTES = ${HeaderBytes};
    const OFF_STATUS = ${OffsetStatusOrErrLen};
    const OFF_HEADERS_LEN = ${OffsetHeadersLen};
    const OFF_BODY_LEN = ${OffsetBodyLen};
    const STATE_SUCCESS = ${StateSuccess};
    const STATE_ERROR = ${StateError};
    """ +
      """
    const { workerData } = require('worker_threads');
    const { method, uri, headers, body, sab, connectTimeoutMillis, readTimeoutMillis, maxResponseBytes } = workerData;
    const stateView = new Int32Array(sab, 0, 1);
    const ints = new DataView(sab);
    const encoder = new TextEncoder();

    function setI32(offset, value) { ints.setInt32(offset, value, true); }

    function finishError(message) {
      const bytes = encoder.encode(String(message));
      const len = Math.min(bytes.length, sab.byteLength - HEADER_BYTES);
      new Uint8Array(sab, HEADER_BYTES, len).set(bytes.subarray(0, len));
      setI32(OFF_STATUS, len);
      Atomics.store(stateView, 0, STATE_ERROR);
      Atomics.notify(stateView, 0);
    }

    (async () => {
      try {
        // redirect: 'manual' so DefaultHttpSyncClient owns redirect handling
        // (noFollowRedirects / maxRedirects / method rewriting), matching FetchChannel.
        // Node's fetch exposes the real 3xx status + Location on a manual redirect.
        const init = { method, headers: new Headers(headers), redirect: 'manual' };
        if (body !== undefined && body !== null) init.body = body;
        if (connectTimeoutMillis > 0 || readTimeoutMillis > 0) {
          const controller = new AbortController();
          init.signal = controller.signal;
          const timeout = Math.max(connectTimeoutMillis, readTimeoutMillis);
          if (timeout > 0) setTimeout(() => controller.abort(), timeout);
        }
        const resp = await fetch(uri, init);
        const bodyBuf = new Uint8Array(await resp.arrayBuffer());

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

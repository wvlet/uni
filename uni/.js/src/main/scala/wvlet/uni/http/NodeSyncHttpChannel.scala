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
    // Shared buffer layout (little-endian):
    //   [0..4)   stateWord (i32):    0 pending, 1 success, 2 error
    //   [4..8)   statusOrErrLen:     on success — HTTP status; on error — error msg byte length
    //   [8..12)  headersJsonLen:     length of headers-JSON UTF-8 bytes (success only)
    //   [12..16) bodyLen:            length of body bytes (success only)
    //   [16..)   headersJson || body (success) | errorMsg (error)
    val headerBytes = 16
    val sab         = js.Dynamic.newInstance(SharedArrayBufferCtor)(headerBytes + maxResponseBytes)
    val stateWord   = js.Dynamic.newInstance(Int32ArrayCtor)(sab, 0, 1)

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
            // Need a stable typed-array view — toTypedArray gives a fresh Uint8Array backed
            // by an ArrayBuffer (not the shared one). The worker's `fetch` accepts it.
            asUint8Array(request.content.toContentBytes)
        ,
        sab = sab,
        connectTimeoutMillis = config.connectTimeoutMillis,
        readTimeoutMillis = config.readTimeoutMillis,
        maxBodyBytes = maxResponseBytes
      )

    val worker =
      js.Dynamic
        .newInstance(workerThreads.Worker)(
          WorkerScript,
          js.Dynamic.literal(eval = true, workerData = requestData)
        )

    // Cap the wait with a safety timeout so a crashed or hung worker can't block the main
    // thread forever. The worker aborts its fetch at max(connect, read) timeout, so give it
    // a 5s grace period on top of that before we give up. `js.undefined` means "wait forever"
    // when no timeout is configured.
    val workerTimeoutMillis = math.max(config.connectTimeoutMillis, config.readTimeoutMillis)
    val waitTimeout: js.Any =
      if workerTimeoutMillis > 0 then
        workerTimeoutMillis + 5000
      else
        js.undefined

    try
      // Block until the worker stores a non-zero value at stateWord[0]. `applyDynamic` is
      // needed because plain `.wait(...)` resolves to `java.lang.Object.wait` on Scala 3.
      val waitResult = js
        .Dynamic
        .global
        .Atomics
        .applyDynamic("wait")(stateWord, 0, 0, waitTimeout)
        .asInstanceOf[String]
      if waitResult == "timed-out" then
        throw HttpException.connectionFailed(
          s"sync HTTP request timed out after ${workerTimeoutMillis + 5000} ms"
        )

      val state = stateWord.selectDynamic("0").asInstanceOf[Int]

      // DataView over the shared buffer for reading the small header (status, lengths).
      val view = js.Dynamic.newInstance(DataViewCtor)(sab)

      if state == 2 then
        val errLen   = view.getInt32(4, true).asInstanceOf[Int]
        val errBytes = readBytes(sab, headerBytes, errLen)
        throw HttpException.connectionFailed(new String(errBytes, "UTF-8"))

      if state != 1 then
        throw HttpException.connectionFailed(s"worker returned unexpected state ${state}")

      val status         = view.getInt32(4, true).asInstanceOf[Int]
      val headersJsonLen = view.getInt32(8, true).asInstanceOf[Int]
      val bodyLen        = view.getInt32(12, true).asInstanceOf[Int]

      val headersJson = new String(readBytes(sab, headerBytes, headersJsonLen), "UTF-8")
      val bodyBytes   = readBytes(sab, headerBytes + headersJsonLen, bodyLen)

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

  private def asUint8Array(bytes: Array[Byte]): js.Any =
    // `.set` with a typed array does a bulk native copy; element-by-element `updateDynamic`
    // with stringified indices is far slower in Scala.js. Int8 -> Uint8 wraps as expected.
    val arr = js.Dynamic.newInstance(Uint8ArrayCtor)(bytes.length)
    arr.set(bytes.toTypedArray)
    arr

  private def readBytes(sab: js.Dynamic, offset: Int, length: Int): Array[Byte] =
    if length == 0 then
      Array.emptyByteArray
    else
      // Cast to js.Array[Int] so the loop uses numeric indexing instead of stringified keys.
      val u8 = js
        .Dynamic
        .newInstance(Uint8ArrayCtor)(sab, offset, length)
        .asInstanceOf[js.Array[Int]]
      val bytes = new Array[Byte](length)
      var i     = 0
      while i < length do
        bytes(i) = u8(i).toByte
        i += 1
      bytes

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

  // `SharedArrayBuffer`, `Atomics`, `DataView`, `Int32Array`, `Uint8Array` are all standard
  // ES globals — accessed via js.Dynamic since scalajs-library doesn't ship typed bindings
  // for SharedArrayBuffer / Atomics. Scala.js disallows loading `js.Dynamic.global` itself
  // as a value, so resolve lazily on every reference.
  private def SharedArrayBufferCtor = js.Dynamic.global.SharedArrayBuffer
  private def Int32ArrayCtor        = js.Dynamic.global.Int32Array
  private def Uint8ArrayCtor        = js.Dynamic.global.Uint8Array
  private def DataViewCtor          = js.Dynamic.global.DataView

  /**
    * Worker source. Receives the request via `workerData`, performs an async fetch, encodes the
    * response into the shared buffer, then calls `Atomics.notify` to wake the parent.
    */
  private val WorkerScript =
    """
    const { workerData } = require('worker_threads');
    const { method, uri, headers, body, sab, connectTimeoutMillis, readTimeoutMillis, maxBodyBytes } = workerData;
    const HEADER_BYTES = 16;
    const stateView = new Int32Array(sab, 0, 1);
    const ints = new DataView(sab);
    const encoder = new TextEncoder();

    function setI32(offset, value) { ints.setInt32(offset, value, true); }

    function finishError(message) {
      const bytes = encoder.encode(String(message));
      const len = Math.min(bytes.length, sab.byteLength - HEADER_BYTES);
      new Uint8Array(sab, HEADER_BYTES, len).set(bytes.subarray(0, len));
      setI32(4, len);
      Atomics.store(stateView, 0, 2);
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
        if (totalLen > maxBodyBytes) {
          finishError(`response too large: ${totalLen} > ${maxBodyBytes} bytes`);
          return;
        }

        setI32(4, resp.status);
        setI32(8, headersJson.length);
        setI32(12, bodyBuf.length);
        new Uint8Array(sab, HEADER_BYTES, headersJson.length).set(headersJson);
        new Uint8Array(sab, HEADER_BYTES + headersJson.length, bodyBuf.length).set(bodyBuf);

        Atomics.store(stateView, 0, 1);
        Atomics.notify(stateView, 0);
      } catch (e) {
        finishError(e && e.stack ? e.stack : (e && e.message ? e.message : String(e)));
      }
    })();
  """

end NodeSyncHttpChannel

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

import wvlet.uni.test.UniTest

import scala.scalajs.js

class NodeSyncHttpChannelTest extends UniTest:

  test("isNode returns true under Node") {
    NodeSyncHttpChannel.isNode shouldBe true
  }

  test("HttpSyncClient round-trips GET against a local server") {
    withLocalServer { port =>
      Http.setDefaultChannelFactory(JSHttpChannelFactory)
      val client = Http.client.withBaseUri(s"http://localhost:${port}").newSyncClient
      try
        val resp = client.send(Request(method = HttpMethod.GET, uri = "/get"))
        info(s"status=${resp.status} bytes=${resp.content.toContentString.length}")
        resp.status.code shouldBe 200
        resp.content.toContentString shouldContain "\"method\":\"GET\""
        resp.content.toContentString shouldContain "\"url\":\"/get\""
      finally
        client.close()
    }
  }

  test("HttpSyncClient round-trips POST with a JSON body") {
    withLocalServer { port =>
      Http.setDefaultChannelFactory(JSHttpChannelFactory)
      val client = Http.client.withBaseUri(s"http://localhost:${port}").newSyncClient
      try
        val body = """{"hello": "world"}"""
        val resp = client.send(
          Request(method = HttpMethod.POST, uri = "/post").withJsonContent(body)
        )
        resp.status.code shouldBe 200
        resp.content.toContentString shouldContain "\"method\":\"POST\""
        resp.content.toContentString shouldContain "\\\"hello\\\""
      finally
        client.close()
    }
  }

  test("HttpSyncClient aborts a stalled request at readTimeoutMillis") {
    withLocalServer { port =>
      Http.setDefaultChannelFactory(JSHttpChannelFactory)
      val client =
        Http
          .client
          .withBaseUri(s"http://localhost:${port}")
          .withReadTimeoutMillis(500)
          .newSyncClient
          .noRetry
      try
        val start  = System.currentTimeMillis()
        var thrown = false
        try
          client.send(Request(method = HttpMethod.GET, uri = "/slow"))
        catch
          case _: Throwable =>
            thrown = true
        val elapsed = System.currentTimeMillis() - start
        info(s"stalled request aborted after ${elapsed}ms")
        thrown shouldBe true
        // The worker's own fetch abort fires near 500ms; well before the parent's
        // safety-net wait (readTimeout + grace). A broken timeout would blow past this.
        (elapsed < 4000) shouldBe true
      finally
        client.close()
    }
  }

  test("HttpSyncClient rejects a response larger than maxResponseBytes") {
    withLocalServer { port =>
      Http.setDefaultChannelFactory(JSHttpChannelFactory)
      val client =
        Http
          .client
          .withBaseUri(s"http://localhost:${port}")
          .withMaxResponseBytes(8)
          .newSyncClient
          .noRetry
      try
        var thrown = false
        try
          client.send(Request(method = HttpMethod.GET, uri = "/get"))
        catch
          case _: Throwable =>
            thrown = true
        thrown shouldBe true
      finally
        client.close()
    }
  }

  /**
    * Runs `body` with a throwaway echo HTTP server bound to an ephemeral port. The server runs in
    * its own worker thread on purpose: the sync client blocks the main thread's event loop while
    * waiting for a response, so a same-thread server could never accept the connection.
    */
  private def withLocalServer(body: Int => Unit): Unit =
    import NodeSyncHttpChannelTest.*
    // Shared buffer layout: [0] readiness flag, [1] assigned port.
    val sab    = js.Dynamic.newInstance(js.Dynamic.global.SharedArrayBuffer)(8)
    val state  = js.Dynamic.newInstance(js.Dynamic.global.Int32Array)(sab, 0, 2)
    val worker =
      js.Dynamic
        .newInstance(NodeSyncHttpChannel.workerThreads.Worker)(
          ServerScript,
          js.Dynamic.literal(eval = true, workerData = sab)
        )
    try
      js.Dynamic.global.Atomics.applyDynamic("wait")(state, 0, 0, 10000)
      val port = state.selectDynamic("1").asInstanceOf[Int]
      (port > 0) shouldBe true
      body(port)
    finally
      worker.terminate()

end NodeSyncHttpChannelTest

object NodeSyncHttpChannelTest:

  /**
    * Worker source for the test echo server. Listens on an ephemeral port, then reports the port
    * back through the shared buffer and notifies the waiting main thread.
    */
  private val ServerScript = """
    const { workerData } = require('worker_threads');
    const http = require('http');
    const state = new Int32Array(workerData, 0, 2);
    const server = http.createServer((req, res) => {
      if (req.url === '/slow') {
        // Never respond, so the client read timeout has to fire.
        return;
      }
      const chunks = [];
      req.on('data', (c) => chunks.push(c));
      req.on('end', () => {
        const reqBody = Buffer.concat(chunks).toString('utf-8');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ method: req.method, url: req.url, body: reqBody }));
      });
    });
    server.listen(0, () => {
      Atomics.store(state, 1, server.address().port);
      Atomics.store(state, 0, 1);
      Atomics.notify(state, 0);
    });
    """

end NodeSyncHttpChannelTest

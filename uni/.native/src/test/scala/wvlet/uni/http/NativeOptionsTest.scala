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

class NativeOptionsTest extends UniTest:

  test("Native options are applied in order") {
    val config = Native
      .options
      .withWorkerThreads(4)
      .withReadTimeoutMillis(1000)
      .withWorkerThreads(8)
      .applyTo(NativeServerConfig())
    config.workerThreads shouldBe 8
    config.readTimeoutMillis shouldBe 1000
  }

  test("the default worker count follows the cores, from 4 to 16, and stays 16 on Windows") {
    NativeServerConfig.defaultWorkerThreads(1, windows = false) shouldBe 4
    NativeServerConfig.defaultWorkerThreads(2, windows = false) shouldBe 4
    NativeServerConfig.defaultWorkerThreads(6, windows = false) shouldBe 6
    NativeServerConfig.defaultWorkerThreads(18, windows = false) shouldBe 16
    NativeServerConfig.defaultWorkerThreads(2, windows = true) shouldBe 16
    NativeServerConfig().workerThreads shouldBe NativeServerConfig.DefaultWorkerThreads
  }

  test("Native options check their values when set") {
    intercept[IllegalArgumentException] {
      Native.options.withWorkerThreads(0)
    }
  }

  test("Http.server starts NativeServer with the Native options") {
    Http
      .server
      .withPort(0)
      .withBackend(Native.options.withWorkerThreads(2))
      .withHandler(_ => Response.ok("ok"))
      .start { server =>
        server shouldMatch { case _: NativeHttpServer =>
        }
      }
  }

  test("Http.server's shared limits reach the Native config and win over Native.options") {
    val config = HttpCompat.nativeConfig(
      Http
        .server
        .withMaxInitialLineLength(1024)
        .withMaxHeaderSize(2048)
        .withRequestReadTimeoutMillis(5000)
        .withWriteTimeoutMillis(6000)
        .withIdleTimeoutMillis(7000)
        .noRequestDecompression
        .withMaxDecompressedRequestSize(4096)
        .withMaxStreamingConnections(3)
        .withShutdownTimeoutMillis(2500)
        .withBackend(Native.options.withWorkerThreads(8).withReadTimeoutMillis(900))
    )
    config.maxInitialLineLength shouldBe 1024
    config.maxHeaderSize shouldBe 2048
    config.requestReadTimeoutMillis shouldBe 5000
    config.writeTimeoutMillis shouldBe 6000
    config.idleTimeoutMillis shouldBe 7000
    config.requestDecompression shouldBe false
    config.maxDecompressedRequestSize shouldBe 4096
    config.effectiveMaxStreamingConnections shouldBe 3
    config.shutdownTimeoutMillis shouldBe 2500
    config.workerThreads shouldBe 8
    config.readTimeoutMillis shouldBe 900
  }

  test("unset shared limits keep the Native defaults") {
    val config = HttpCompat.nativeConfig(Http.server)
    val native = NativeServerConfig()
    config.idleTimeoutMillis shouldBe native.idleTimeoutMillis
    config.maxStreamingConnections shouldBe None
    config.shutdownTimeoutMillis shouldBe native.shutdownTimeoutMillis
  }

end NativeOptionsTest

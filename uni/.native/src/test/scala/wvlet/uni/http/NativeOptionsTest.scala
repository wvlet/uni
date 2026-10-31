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
      .withIdleTimeoutMillis(1000)
      .withWorkerThreads(8)
      .applyTo(NativeServerConfig())
    config.workerThreads shouldBe 8
    config.idleTimeoutMillis shouldBe 1000
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

end NativeOptionsTest

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

import scala.scalanative.libc.signal as csignal
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.pthread
import scala.scalanative.posix.signal as psignal
import scala.scalanative.unsafe.*

/**
  * The curl client keeps its signal handling to the calling thread: it sets `CURLOPT_NOSIGNAL`, so
  * libcurl no longer swaps the process-wide SIGPIPE disposition around each call, and it blocks
  * SIGPIPE on its own thread instead.
  */
class NativeCurlSignalTest extends UniTest:
  import NativeCurlSignalTest.*

  test("a curl transfer leaves the process's SIGPIPE handler in place") {
    // Each branch is resolved at link time, so the POSIX-only code isn't linked on Windows
    if LinktimeInfo.isWindows then
      skip("Windows has no SIGPIPE")
    else
      withSentinelHandler {
        // Read the disposition while the client thread is inside curl. Without CURLOPT_NOSIGNAL,
        // an OpenSSL-based libcurl has swapped in SIG_IGN for the whole process at this point.
        @volatile
        var seen: Ptr[Byte] = null
        NativeServerConfig()
          .withHandler { _ =>
            seen = sigpipeHandler()
            Response.ok("ok")
          }
          .withPort(0)
          .start { server =>
            val response = Http
              .client
              .withBaseUri(s"http://127.0.0.1:${server.localPort}")
              .newSyncClient
              .send(Request.get("/"))
            response.statusCode shouldBe 200
          }
        seen shouldBe sentinelPtr
      }
  }

  test("a SIGPIPE raised during a transfer is discarded on that thread") {
    if LinktimeInfo.isWindows then
      skip("Windows has no SIGPIPE")
    else
      withSentinelHandler {
        // The handler sees an unblocked SIGPIPE, so the check below is not vacuous
        hits = 0
        pthread.pthread_kill(pthread.pthread_self(), psignal.SIGPIPE)
        hits shouldBe 1

        hits = 0
        CurlBindings.withSigpipeBlocked {
          pthread.pthread_kill(pthread.pthread_self(), psignal.SIGPIPE)
          hits shouldBe 0
        }
        // Unblocking would deliver a SIGPIPE still pending
        hits shouldBe 0
      }
  }

end NativeCurlSignalTest

object NativeCurlSignalTest:
  @volatile
  private var hits: Int = 0

  private val sentinel: CFuncPtr1[CInt, Unit] = CFuncPtr1.fromScalaFunction { (_: CInt) =>
    hits += 1
  }

  private def sentinelPtr: Ptr[Byte] = CFuncPtr.toPtr(sentinel).asInstanceOf[Ptr[Byte]]

  private def withSentinelHandler[A](body: => A): A =
    // Save the whole `struct sigaction` (flags and mask too) to put it back as it was
    val saved = stackalloc[Byte](512).asInstanceOf[Ptr[psignal.sigaction]]
    psignal.sigaction(psignal.SIGPIPE, null, saved)
    csignal.signal(psignal.SIGPIPE, sentinel)
    try body
    finally psignal.sigaction(psignal.SIGPIPE, saved, null)

  /** The current SIGPIPE handler, read from the first field of `struct sigaction` */
  private def sigpipeHandler(): Ptr[Byte] =
    // Larger than `struct sigaction` on every supported platform
    val act = stackalloc[Byte](512)
    psignal.sigaction(psignal.SIGPIPE, null, act.asInstanceOf[Ptr[psignal.sigaction]])
    !act.asInstanceOf[Ptr[Ptr[Byte]]]

end NativeCurlSignalTest

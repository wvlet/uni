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

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

class NativeSocketListenerTest extends UniTest:

  test("close wakes a thread blocked in accept before releasing the fd") {
    // On Linux, closing a listening fd doesn't wake accept(). A thread left blocked there could,
    // after a signal restarts the call, accept on whatever socket next reuses the fd number.
    val listener = NativeSocketListener("127.0.0.1", 0, 16)
    val started  = CountDownLatch(1)
    val done     = CountDownLatch(1)
    val result   = AtomicReference[Option[SocketConnection]](None)
    val t        = Thread { () =>
      started.countDown()
      result.set(Option(listener.accept()))
      done.countDown()
    }
    t.setDaemon(true)
    t.start()
    started.await()
    Thread.sleep(50) // let the thread enter accept()
    listener.close()
    done.await(5, TimeUnit.SECONDS) shouldBe true
    result.get() shouldBe None
  }

  test("accept after close returns null without blocking") {
    val listener = NativeSocketListener("127.0.0.1", 0, 16)
    listener.close()
    listener.accept() shouldBe null
  }

end NativeSocketListenerTest

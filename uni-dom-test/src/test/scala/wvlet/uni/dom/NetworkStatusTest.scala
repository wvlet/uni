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
package wvlet.uni.dom

import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.rx.Rx

class NetworkStatusTest extends UniTest:

  test("NetworkStatus.isOnline returns current status"):
    // In a headless browser, navigator.onLine is typically true
    val status = NetworkStatus.isOnline
    status shouldMatch { case _: Boolean =>
    }

  test("NetworkStatus.online returns Rx[Boolean]"):
    val rx = NetworkStatus.online
    rx shouldMatch { case _: Rx[?] =>
    }

  test("NetworkStatus.offline returns Rx[Boolean]"):
    val rx = NetworkStatus.offline
    rx shouldMatch { case _: Rx[?] =>
    }

  test("NetworkStatus.online and offline are inverses"):
    // Get current values
    var onlineValue  = false
    var offlineValue = false

    NetworkStatus
      .online
      .run { v =>
        onlineValue = v
      }
    NetworkStatus
      .offline
      .run { v =>
        offlineValue = v
      }

    onlineValue shouldBe !offlineValue

  test("NetworkStatus can be used in reactive expressions"):
    val statusText = NetworkStatus
      .online
      .map { online =>
        if online then
          "Connected"
        else
          "Disconnected"
      }

    var result = ""
    statusText.run { v =>
      result = v
    }

    // Should have one of the two values
    (result == "Connected" || result == "Disconnected") shouldBe true

end NetworkStatusTest

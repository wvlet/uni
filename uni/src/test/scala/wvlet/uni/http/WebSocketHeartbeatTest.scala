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

import wvlet.uni.http.WebSocketHeartbeat.Decision
import wvlet.uni.test.UniTest

/** Deterministic tests for the cross-platform ping/pong heartbeat state machine. */
class WebSocketHeartbeatTest extends UniTest:

  test("an idle interval sends a ping, then closes if unanswered") {
    val hb = WebSocketHeartbeat()
    hb.onTick() shouldBe Decision.SendPing // idle → ping
    hb.onTick() shouldBe Decision.Close    // still idle, ping unanswered → close
  }

  test("activity (e.g. a pong) clears the pending ping") {
    val hb = WebSocketHeartbeat()
    hb.onTick() shouldBe Decision.SendPing
    hb.onActivity()                        // a frame arrived
    hb.onTick() shouldBe Decision.Idle     // liveness proven → no ping, no close
    hb.onTick() shouldBe Decision.SendPing // idle again → ping
  }

  test("continuous activity keeps it idle (never closes)") {
    val hb = WebSocketHeartbeat()
    hb.onActivity()
    hb.onTick() shouldBe Decision.Idle
    hb.onActivity()
    hb.onTick() shouldBe Decision.Idle
  }

end WebSocketHeartbeatTest

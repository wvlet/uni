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

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Close-frame rules shared by every WebSocket backend: sendable codes, reason truncation, the
  * close status reported to `onClose`, and the headers a client may add to the upgrade.
  */
class WebSocketCloseTest extends UniTest:

  private def utf8Length(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  test("keep codes that may be sent, and send others as 1011") {
    Seq(1000, 1001, 1003, 1007, 1011, 1013, 3000, 4999).foreach { code =>
      WebSocketClose.sendableCode(code) shouldBe code
    }
    Seq(-1, 0, 999, 1004, 1005, 1006, 1015, 2999, 5000).foreach { code =>
      WebSocketClose.sendableCode(code) shouldBe 1011
    }
  }

  test("keep a reason that fits a close frame") {
    WebSocketClose.truncateReason("") shouldBe ""
    WebSocketClose.truncateReason(null) shouldBe ""
    val fits = "a" * 123
    WebSocketClose.truncateReason(fits) shouldBe fits
  }

  test("cut a long ASCII reason to 123 bytes") {
    WebSocketClose.truncateReason("a" * 300) shouldBe "a" * 123
  }

  test("cut a long multi-byte reason on a character boundary") {
    // 3 bytes per char: 41 chars fit in 123 bytes, 42 don't
    val reason = "あ" * 100
    val cut    = WebSocketClose.truncateReason(reason)
    cut shouldBe "あ" * 41
    // 4-byte chars: the cut backs up to a whole code point
    val emoji = "😀" * 40
    val cut2  = WebSocketClose.truncateReason(emoji)
    utf8Length(cut2) shouldBe 120
    cut2 shouldBe "😀" * 30
  }

  test("build a close payload within the 125-byte control-frame limit") {
    val payload = WebSocketFrame.closePayload(1006, "x" * 500)
    payload.length shouldBe 125
    (((payload(0) & 0xff) << 8) | (payload(1) & 0xff)) shouldBe 1011
  }

  test("report the first recorded close status to onClose, exactly once") {
    val seen    = mutable.ArrayBuffer.empty[(Int, String)]
    val handler =
      new WebSocketHandler:
        override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
          seen += ((statusCode, reason))
    val state = WebSocketCloseState()
    state.record(4001, "first")
    state.record(1000, "second")
    state.notifyClose(handler, null)
    state.notifyClose(handler, null)
    seen.toSeq shouldBe Seq((4001, "first"))
  }

  test("report 1006 when the connection ended without a close frame") {
    var status  = (0, "")
    val handler =
      new WebSocketHandler:
        override def onClose(ctx: WebSocketContext, statusCode: Int, reason: String): Unit =
          status = (statusCode, reason)
    WebSocketCloseState().notifyClose(handler, null)
    status shouldBe (1006, "")
  }

  test("call the one-argument onClose by default") {
    var closed  = 0
    val handler =
      new WebSocketHandler:
        override def onClose(ctx: WebSocketContext): Unit = closed += 1
    WebSocketCloseState().notifyClose(handler, null)
    closed shouldBe 1
  }

  test("pass custom upgrade headers through") {
    val request = Request
      .get("ws://localhost/ws")
      .addHeader("Authorization", "Bearer token")
      .addHeader("X-Client", "uni")
    WebSocketClient.upgradeHeaders(request) shouldBe
      Seq("Authorization" -> "Bearer token", "X-Client" -> "uni")
  }

  test("reject headers the handshake sets itself") {
    Seq("Host", "Upgrade", "connection", "Sec-WebSocket-Key", "Sec-WebSocket-Protocol").foreach {
      name =>
        intercept[IllegalArgumentException] {
          WebSocketClient.upgradeHeaders(Request.get("ws://localhost/ws").addHeader(name, "x"))
        }
    }
  }

end WebSocketCloseTest

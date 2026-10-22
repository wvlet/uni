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
package wvlet.uni.http.rpc

import wvlet.uni.http.Request
import wvlet.uni.test.UniTest

trait FailingService:
  def fail(): String

class FailingServiceImpl extends FailingService:
  def fail(): String = throw IllegalStateException("secret internal detail")

class RPCErrorStackTraceTest extends UniTest:

  test("include stackTrace in the error body when shouldReportStackTrace is true") {
    val ex = RPCStatus.UNAVAILABLE_I2.newException("probe message")
    ex.shouldReportStackTrace shouldBe true
    val m = RPCErrorMessage.fromJson(ex.toJson)
    m.stackTrace shouldBe Some(ex.getStackTrace.toSeq.map(RPCStackTraceElement.fromJava))
    (m.stackTrace.get.size > 0) shouldBe true
    ex.toJson shouldContain "\"stackTrace\""
    ex.toJson shouldContain "\"className\""
    ex.toJson shouldContain "\"methodName\""
    ex.toJson shouldContain "\"lineNumber\""
  }

  test("omit stackTrace for auth errors") {
    for status <- Seq(RPCStatus.UNAUTHENTICATED_U13, RPCStatus.PERMISSION_DENIED_U14) do
      val json = status.newException("denied").toJson
      json shouldNotContain "stackTrace"
      RPCErrorMessage.fromJson(json).stackTrace shouldBe None
  }

  test("omit unset optional fields from the error body") {
    RPCStatus.UNAVAILABLE_I2.newException("probe message").noStackTrace.toJson shouldBe
      """{"code":2002,"codeName":"UNAVAILABLE_I2","message":"probe message"}"""
  }

  test("omit stackTrace when noStackTrace is set") {
    val json = RPCStatus.INTERNAL_ERROR_I0.newException("hidden").noStackTrace.toJson
    json shouldNotContain "stackTrace"
  }

  test("restore the server-side stack trace on the client") {
    // Compare the serialized fields; Java module info in StackTraceElement is not transferred
    def frames(e: Throwable) = e.getStackTrace.toSeq.map(RPCStackTraceElement.fromJava)
    val original             = RPCStatus.UNAVAILABLE_I2.newException("probe message")
    val expected             = frames(original)

    frames(RPCException.fromJson(original.toJson)) shouldBe expected
    frames(RPCException.fromMsgPack(original.toMsgPack)) shouldBe expected
  }

  test("RPCStackTraceElement handles frames without a file name") {
    val frame = StackTraceElement("a.B", "run", null, -2)
    val e     = RPCStackTraceElement.fromJava(frame)
    e.fileName shouldBe None
    e.toJava shouldBe frame
  }

  test("hide the stack trace of unexpected exceptions unless detailedErrors is enabled") {
    val router = RPCRouter.of[FailingService](FailingServiceImpl())

    val hidden = router.routes.find(_.methodName == "fail").get
    val resp   =
      RPCDispatcher(router)
        .dispatch(Request.post(hidden.path).withJsonContent("""{"request":{}}"""))
        .await
    val body = resp.contentAsString.get
    body shouldNotContain "stackTrace"
    body shouldNotContain "secret internal detail"

    val detailed =
      RPCDispatcher(router)
        .withDetailedErrors(true)
        .dispatch(Request.post(hidden.path).withJsonContent("""{"request":{}}"""))
        .await
        .contentAsString
        .get
    detailed shouldContain "stackTrace"
    detailed shouldContain "secret internal detail"
    // The frames are the handler's, not the dispatcher's wrapper
    detailed shouldContain "FailingServiceImpl"
  }

end RPCErrorStackTraceTest

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

import wvlet.uni.http.{HttpHeader, HttpStatus, Request, Response}
import wvlet.uni.test.UniTest

trait FailingService:
  def fail(): String
  def add(title: String): String
  def unavailable(): String
  def denied(): String
  def hidden(): String

class FailingServiceImpl extends FailingService:
  def fail(): String             = throw IllegalStateException("secret internal detail")
  def add(title: String): String = title
  def unavailable(): String      = throw RPCStatus.UNAVAILABLE_I2.newException("busy")
  def denied(): String           = throw RPCStatus.PERMISSION_DENIED_U14.newException("denied")
  def hidden(): String           = throw RPCStatus.UNAVAILABLE_I2.newException("busy").noStackTrace

class RPCErrorStackTraceTest extends UniTest:

  private def withStackTrace(status: RPCStatus, message: String): RPCException = RPCException(
    status = status,
    message = message,
    includeStackTrace = Some(true)
  )

  test("omit stackTrace from the error body by default") {
    for status <- Seq(RPCStatus.INVALID_REQUEST_U1, RPCStatus.INTERNAL_ERROR_I0) do
      val ex = status.newException("probe message")
      ex.shouldReportStackTrace shouldBe false
      ex.toJson shouldNotContain "stackTrace"
      ex.toResponse.contentAsString.get shouldNotContain "stackTrace"
      RPCErrorMessage.fromMsgPack(ex.toMsgPack).stackTrace shouldBe None
  }

  test("include stackTrace in the error body when includeStackTrace is set") {
    val ex = withStackTrace(RPCStatus.UNAVAILABLE_I2, "probe message")
    ex.shouldReportStackTrace shouldBe true
    val m = RPCErrorMessage.fromJson(ex.toJson)
    m.stackTrace shouldBe Some(ex.getStackTrace.toSeq.map(RPCStackTraceElement.fromJava))
    (m.stackTrace.get.size > 0) shouldBe true
    ex.toJson shouldContain "\"stackTrace\""
    ex.toJson shouldContain "\"className\""
    ex.toJson shouldContain "\"methodName\""
    ex.toJson shouldContain "\"lineNumber\""
  }

  test("omit unset optional fields from the error body") {
    RPCStatus.UNAVAILABLE_I2.newException("probe message").toJson shouldBe
      """{"code":2002,"codeName":"UNAVAILABLE_I2","message":"probe message"}"""
  }

  test("restore the server-side stack trace on the client") {
    // Compare the serialized fields; Java module info in StackTraceElement is not transferred
    def frames(e: Throwable) = e.getStackTrace.toSeq.map(RPCStackTraceElement.fromJava)
    val original             = withStackTrace(RPCStatus.UNAVAILABLE_I2, "probe message")
    val expected             = frames(original)

    frames(RPCException.fromJson(original.toJson)) shouldBe expected
    frames(RPCException.fromMsgPack(original.toMsgPack)) shouldBe expected
  }

  test("parse error responses from servers that send stackTrace") {
    // An error body as written by older Uni servers and Airframe RPC
    val body =
      """{"code":1001,"codeName":"INVALID_REQUEST_U1","message":"bad request","stackTrace":[{"className":"example.Server","methodName":"handle","fileName":"Server.scala","lineNumber":42}]}"""
    val response = Response(HttpStatus.BadRequest_400)
      .addHeader(HttpHeader.XRPCStatus, RPCStatus.INVALID_REQUEST_U1.code.toString)
      .withJsonContent(body)
    val e = RPCException.fromResponse(response)
    e.status shouldBe RPCStatus.INVALID_REQUEST_U1
    e.message shouldBe "bad request"
    e.getStackTrace.toSeq shouldBe
      Seq(StackTraceElement("example.Server", "handle", "Server.scala", 42))
  }

  test("parse error responses without stackTrace") {
    val response = RPCStatus.INVALID_REQUEST_U1.newException("bad request").toResponse
    val e        = RPCException.fromResponse(response)
    e.status shouldBe RPCStatus.INVALID_REQUEST_U1
    e.message shouldBe "bad request"
  }

  test("RPCStackTraceElement handles frames without a file name") {
    val frame = StackTraceElement("a.B", "run", null, -2)
    val e     = RPCStackTraceElement.fromJava(frame)
    e.fileName shouldBe None
    e.toJava shouldBe frame
  }

  private val router = RPCRouter.of[FailingService](FailingServiceImpl())

  private def call(
      dispatcher: RPCDispatcher,
      methodName: String,
      body: String = """{"request":{}}"""
  ): String =
    val route = router.routes.find(_.methodName == methodName).get
    dispatcher.dispatch(Request.post(route.path).withJsonContent(body)).await.contentAsString.get

  test("omit stackTrace from dispatcher responses unless detailedErrors is enabled") {
    val default  = RPCDispatcher(router)
    val detailed = RPCDispatcher(router).withDetailedErrors(true)

    // A client error: the request is missing the "request" wrapper
    val malformed   = """{"title":"hello"}"""
    val clientError = call(default, "add", malformed)
    clientError shouldContain RPCStatus.INVALID_REQUEST_U1.name
    clientError shouldNotContain "stackTrace"
    call(detailed, "add", malformed) shouldContain "stackTrace"

    // A server error thrown as RPCException by the handler
    val serverError = call(default, "unavailable")
    serverError shouldContain RPCStatus.UNAVAILABLE_I2.name
    serverError shouldNotContain "stackTrace"
    call(detailed, "unavailable") shouldContain "FailingServiceImpl"
  }

  test("omit stackTrace for auth errors and noStackTrace even with detailedErrors") {
    val detailed = RPCDispatcher(router).withDetailedErrors(true)
    call(detailed, "denied") shouldNotContain "stackTrace"
    call(detailed, "hidden") shouldNotContain "stackTrace"
  }

  test("hide the stack trace of unexpected exceptions unless detailedErrors is enabled") {
    val body = call(RPCDispatcher(router), "fail")
    body shouldNotContain "stackTrace"
    body shouldNotContain "secret internal detail"

    val detailed = call(RPCDispatcher(router).withDetailedErrors(true), "fail")
    detailed shouldContain "stackTrace"
    detailed shouldContain "secret internal detail"
    // The frames are the handler's, not the dispatcher's wrapper
    detailed shouldContain "FailingServiceImpl"
  }

end RPCErrorStackTraceTest

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

import wvlet.uni.http.{
  ServiceErrorMessage,
  ServiceException,
  ServiceStackTraceElement,
  ServiceStatus
}
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
  def unavailable(): String      = throw ServiceStatus.UNAVAILABLE_I2.newException("busy")
  def denied(): String           = throw ServiceStatus.PERMISSION_DENIED_U14.newException("denied")
  def hidden(): String = throw ServiceStatus.UNAVAILABLE_I2.newException("busy").noStackTrace

class RPCErrorStackTraceTest extends UniTest:

  private def withStackTrace(status: ServiceStatus, message: String): ServiceException =
    ServiceException(status = status, message = message, includeStackTrace = Some(true))

  test("omit stackTrace from the error body by default") {
    for status <- Seq(ServiceStatus.INVALID_REQUEST_U1, ServiceStatus.INTERNAL_ERROR_I0) do
      val ex = status.newException("probe message")
      ex.shouldReportStackTrace shouldBe false
      ex.toJson shouldNotContain "stackTrace"
      ex.toResponse.contentAsString.get shouldNotContain "stackTrace"
      ServiceErrorMessage.fromMsgPack(ex.toMsgPack).stackTrace shouldBe None
  }

  test("include stackTrace in the error body when includeStackTrace is set") {
    val ex = withStackTrace(ServiceStatus.UNAVAILABLE_I2, "probe message")
    ex.shouldReportStackTrace shouldBe true
    val m = ServiceErrorMessage.fromJson(ex.toJson)
    m.stackTrace shouldBe Some(ex.getStackTrace.toSeq.map(ServiceStackTraceElement.fromJava))
    (m.stackTrace.get.size > 0) shouldBe true
    ex.toJson shouldContain "\"stackTrace\""
    ex.toJson shouldContain "\"className\""
    ex.toJson shouldContain "\"methodName\""
    ex.toJson shouldContain "\"lineNumber\""
  }

  test("omit unset optional fields from the error body") {
    ServiceStatus.UNAVAILABLE_I2.newException("probe message").toJson shouldBe
      """{"code":2002,"codeName":"UNAVAILABLE_I2","message":"probe message"}"""
  }

  test("restore the server-side stack trace on the client") {
    // Compare the serialized fields; Java module info in StackTraceElement is not transferred
    def frames(e: Throwable) = e.getStackTrace.toSeq.map(ServiceStackTraceElement.fromJava)
    val original             = withStackTrace(ServiceStatus.UNAVAILABLE_I2, "probe message")
    val expected             = frames(original)

    frames(ServiceException.fromJson(original.toJson)) shouldBe expected
    frames(ServiceException.fromMsgPack(original.toMsgPack)) shouldBe expected
  }

  test("parse error responses from servers that send stackTrace") {
    // An error body as written by older Uni servers and Airframe RPC
    val body =
      """{"code":1001,"codeName":"INVALID_REQUEST_U1","message":"bad request","stackTrace":[{"className":"example.Server","methodName":"handle","fileName":"Server.scala","lineNumber":42}]}"""
    val response = Response(HttpStatus.BadRequest_400)
      .addHeader(HttpHeader.XRPCStatus, ServiceStatus.INVALID_REQUEST_U1.code.toString)
      .withJsonContent(body)
    val e = ServiceException.fromResponse(response)
    e.status shouldBe ServiceStatus.INVALID_REQUEST_U1
    e.message shouldBe "bad request"
    e.getStackTrace.toSeq shouldBe
      Seq(StackTraceElement("example.Server", "handle", "Server.scala", 42))
  }

  test("parse error responses without stackTrace") {
    val response = ServiceStatus.INVALID_REQUEST_U1.newException("bad request").toResponse
    val e        = ServiceException.fromResponse(response)
    e.status shouldBe ServiceStatus.INVALID_REQUEST_U1
    e.message shouldBe "bad request"
  }

  test("ServiceStackTraceElement handles frames without a file name") {
    val frame = StackTraceElement("a.B", "run", null, -2)
    val e     = ServiceStackTraceElement.fromJava(frame)
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
    clientError shouldContain ServiceStatus.INVALID_REQUEST_U1.name
    clientError shouldNotContain "stackTrace"
    call(detailed, "add", malformed) shouldContain "stackTrace"

    // A server error thrown as ServiceException by the handler
    val serverError = call(default, "unavailable")
    serverError shouldContain ServiceStatus.UNAVAILABLE_I2.name
    serverError shouldNotContain "stackTrace"
    call(detailed, "unavailable") shouldContain "FailingServiceImpl"
  }

  test("omit stackTrace for auth errors and noStackTrace even with detailedErrors") {
    val detailed = RPCDispatcher(router).withDetailedErrors(true)
    call(detailed, "denied") shouldNotContain "stackTrace"
    call(detailed, "hidden") shouldNotContain "stackTrace"
  }

  test("hide the stack trace of unexpected exceptions unless detailedErrors is enabled") {
    var body     = ""
    var detailed = ""
    val logs     = captureLogs {
      body = call(RPCDispatcher(router), "fail")
      detailed = call(RPCDispatcher(router).withDetailedErrors(true), "fail")
    }
    // The server log keeps the detail that the default response hides
    logs.warnings shouldContain "Unexpected error in RPC handler: secret internal detail"
    body shouldNotContain "stackTrace"
    body shouldNotContain "secret internal detail"

    detailed shouldContain "stackTrace"
    detailed shouldContain "secret internal detail"
    // The frames are the handler's, not the dispatcher's wrapper
    detailed shouldContain "FailingServiceImpl"
  }

end RPCErrorStackTraceTest

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
package wvlet.uni.http.netty

import wvlet.uni.http.{HttpMethod, HttpStatus, Request, Response, RxHttpFilter, RxHttpHandler}
import wvlet.uni.http.router.{ControllerProvider, Endpoint, Router, SimpleControllerProvider}
import wvlet.uni.rx.Rx
import wvlet.uni.surface.Surface
import wvlet.uni.test.UniTest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.mutable.ListBuffer

object RouterHandlerFilterScopeTest:
  class PublicController:
    @Endpoint(HttpMethod.GET, "/public")
    def publicEndpoint(): String = "public"

  class ProtectedController:
    @Endpoint(HttpMethod.GET, "/protected")
    def protectedEndpoint(): String = "protected"

  class InferenceController:
    @Endpoint(HttpMethod.GET, "/inference")
    def inferenceEndpoint(): String = "inference"

  class AuthFilter extends RxHttpFilter:
    override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
      if request.header("Authorization").contains("Bearer valid") then
        next.handle(request)
      else
        Rx.single(Response(HttpStatus.ofCode(401)))

  class VersionFilter extends RxHttpFilter:
    override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
      if request.header("X-Client-Version").contains("current") then
        next.handle(request)
      else
        Rx.single(Response(HttpStatus.ofCode(426)))

  // Records the order filters see the request and the response
  class Recorder:
    val events = ListBuffer.empty[String]

  class RecordingFilter(name: String, recorder: Recorder) extends RxHttpFilter:
    override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
      recorder.events.synchronized(recorder.events += s"${name}:request")
      next
        .handle(request)
        .map { resp =>
          recorder.events.synchronized(recorder.events += s"${name}:response")
          resp
        }

  class OuterFilter
  class InnerFilter

  class ThrowingFilter extends RxHttpFilter:
    override def apply(request: Request, next: RxHttpHandler): Rx[Response] =
      throw IllegalStateException("inner filter failure")

  class RecoveringFilter extends RxHttpFilter:
    override def apply(request: Request, next: RxHttpHandler): Rx[Response] = next
      .handle(request)
      .recover { case e: IllegalStateException =>
        Response(HttpStatus.ofCode(503)).withTextContent(e.getMessage)
      }

  // Provides filters by DI-like registration, falling back to reflection for controllers
  class FilterProvider(filters: Map[String, RxHttpFilter]) extends ControllerProvider:
    private val controllers                       = SimpleControllerProvider()
    override def get(surface: Surface): Any       = controllers.get(surface)
    override def getFilter(surface: Surface): Any = filters(surface.name)

end RouterHandlerFilterScopeTest

class RouterHandlerFilterScopeTest extends UniTest:
  import RouterHandlerFilterScopeTest.*

  private def status(
      handler: RouterHandler,
      path: String,
      authorized: Boolean = false,
      version: Boolean = false
  ): Int =
    var request = Request.get(path)
    if authorized then
      request = request.addHeader("Authorization", "Bearer valid")
    if version then
      request = request.addHeader("X-Client-Version", "current")
    handler.handle(request).toSeq.head.status.code

  test("a filter rejects requests to the routes it wraps") {
    val handler = RouterHandler(Router.filter[AuthFilter].andThen(Router.of[ProtectedController]))
    status(handler, "/protected") shouldBe 401
    status(handler, "/protected", authorized = true) shouldBe 200
    // A router-wide filter still sees unmatched requests, as before
    status(handler, "/unknown") shouldBe 401
  }

  test("a public sibling stays public when a protected subtree is appended") {
    val handler = RouterHandler(
      Router
        .of[PublicController]
        .andThen(Router.filter[AuthFilter].andThen(Router.of[ProtectedController]))
    )
    status(handler, "/public") shouldBe 200
    status(handler, "/protected") shouldBe 401
    status(handler, "/protected", authorized = true) shouldBe 200
    // No filter covers every route, so unmatched requests are not filtered
    status(handler, "/unknown") shouldBe 404
  }

  test("a public sibling stays public when it is appended after a protected subtree") {
    val handler = RouterHandler(
      Router
        .filter[AuthFilter]
        .andThen(Router.of[ProtectedController])
        .andThen(Router.of[PublicController])
    )
    status(handler, "/public") shouldBe 200
    status(handler, "/protected") shouldBe 401
  }

  test("an outer filter keeps the inner filter of a narrower subtree") {
    val protectedRoutes = Router
      .of[ProtectedController]
      .andThen(Router.filter[VersionFilter].andThen(Router.of[InferenceController]))
    val handler = RouterHandler(Router.filter[AuthFilter].andThen(protectedRoutes))
    status(handler, "/protected", authorized = true) shouldBe 200
    status(handler, "/inference", authorized = true) shouldBe 426
    status(handler, "/inference", authorized = true, version = true) shouldBe 200
    // The outer filter runs first
    status(handler, "/inference", version = true) shouldBe 401
  }

  test("run nested filters outer-to-inner on the request and inner-to-outer on the response") {
    val recorder = Recorder()
    val provider = FilterProvider(
      Map(
        "OuterFilter" -> RecordingFilter("outer", recorder),
        "InnerFilter" -> RecordingFilter("inner", recorder)
      )
    )
    val router = Router
      .filter[OuterFilter]
      .andThen(Router.filter[InnerFilter].andThen(Router.of[ProtectedController]))
    val handler = RouterHandler(router, provider)
    handler.handle(Request.get("/protected")).toSeq.head.contentAsString shouldBe Some("protected")
    recorder.events.toList shouldBe
      List("outer:request", "inner:request", "inner:response", "outer:response")
  }

  test("let an outer filter recover from an inner filter's synchronous exception") {
    val router = Router
      .filter[RecoveringFilter]
      .andThen(
        Router
          .of[PublicController]
          .andThen(Router.filter[ThrowingFilter].andThen(Router.of[ProtectedController]))
      )
    val handler = RouterHandler(router)
    val resp    = handler.handle(Request.get("/protected")).toSeq.head
    resp.status.code shouldBe 503
    resp.contentAsString shouldBe Some("inner filter failure")
    status(handler, "/public") shouldBe 200
  }

  test("apply scoped filters through a Netty server") {
    val router = Router
      .of[PublicController]
      .andThen(
        Router
          .filter[AuthFilter]
          .andThen(
            Router
              .of[ProtectedController]
              .andThen(Router.filter[VersionFilter].andThen(Router.of[InferenceController]))
          )
      )
    val server = NettyServer
      .withPort(0)
      .noNativeTransport
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withRxHandler(RouterHandler(router))
      .start()
    try
      val client                                      = HttpClient.newHttpClient()
      def get(path: String, authorized: Boolean): Int =
        val req = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.localPort}${path}"))
        if authorized then
          req.header("Authorization", "Bearer valid")
        client.send(req.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()

      get("/public", authorized = false) shouldBe 200
      get("/protected", authorized = false) shouldBe 401
      get("/protected", authorized = true) shouldBe 200
      get("/inference", authorized = true) shouldBe 426
    finally
      server.stop()
  }

end RouterHandlerFilterScopeTest

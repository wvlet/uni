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
package wvlet.uni.http.router

import wvlet.uni.http.{HttpMethod, HttpStatus, Request, Response}
import wvlet.uni.http.rpc.{RPCHandler, RPCRouter}
import wvlet.uni.rx.{OnNext, Rx, RxRunner}
import wvlet.uni.test.UniTest

case class HandlerTestUser(id: Int, name: String)

class HandlerTestController:
  @Endpoint(HttpMethod.GET, "/users/:id")
  def getUser(id: Int): HandlerTestUser = HandlerTestUser(id, s"user${id}")

trait HandlerTestApi:
  def hello(name: String): String

class HandlerTestApiImpl extends HandlerTestApi:
  def hello(name: String): String = s"Hello ${name}!"

/**
  * RouterHandler and RPCHandler live in the uni module, so every platform's server can serve them.
  * These tests call the handlers directly, the same way a server backend does.
  */
class RouterHandlerTest extends UniTest:

  private def run(rx: Rx[Response]): Response =
    var result: Response = null
    RxRunner.runOnce(rx) {
      case OnNext(r: Response) =>
        result = r
      case _ =>
    }
    result

  test("dispatch a request to an annotated controller") {
    val handler = RouterHandler(Router.of[HandlerTestController])
    val resp    = run(handler.handle(Request.get("/users/42")))
    resp.status shouldBe HttpStatus.Ok_200
    resp.contentAsString shouldBe Some("""{"id":42,"name":"user42"}""")
  }

  test("answer 404 for an unknown path") {
    val handler = RouterHandler(Router.of[HandlerTestController])
    run(handler.handle(Request.get("/missing"))).status shouldBe HttpStatus.NotFound_404
  }

  test("serve an RPC method") {
    val router  = RPCRouter.of[HandlerTestApi](HandlerTestApiImpl())
    val handler = RPCHandler(router)
    val resp    = run(
      handler.handle(
        Request
          .post(s"/${router.serviceName}/hello")
          .withJsonContent("""{"request":{"name":"uni"}}""")
      )
    )
    resp.status shouldBe HttpStatus.Ok_200
    resp.contentAsString.exists(_.contains("Hello uni!")) shouldBe true
  }

end RouterHandlerTest

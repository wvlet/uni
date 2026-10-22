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

import wvlet.uni.http.{HttpMethod, Request}
import wvlet.uni.http.router.{Endpoint, Router}
import wvlet.uni.rx.Rx
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.WeaverConfig

object RouterHandlerWeaverConfigTest:
  case class Usage(input: Int, cached: Option[Int] = None)
  case class Reply(id: String, stop: Option[String] = None, usage: Usage)

  class ReplyController:
    @Endpoint(HttpMethod.GET, "/reply")
    def reply(): Reply = Reply("r1", usage = Usage(10))

    @Endpoint(HttpMethod.GET, "/rx-reply")
    def rxReply(): Rx[Reply] = Rx.single(Reply("r2", usage = Usage(20)))

class RouterHandlerWeaverConfigTest extends UniTest:
  import RouterHandlerWeaverConfigTest.*

  private def body(handler: RouterHandler, path: String): Option[String] =
    handler.handle(Request.get(path)).toSeq.head.contentAsString

  test("write None fields as null by default") {
    val handler = RouterHandler(Router.of[ReplyController])
    body(handler, "/reply") shouldBe
      Some("""{"id":"r1","stop":null,"usage":{"input":10,"cached":null}}""")
  }

  test("omit None fields in responses with withWeaverConfig") {
    val handler = RouterHandler(Router.of[ReplyController]).withWeaverConfig(
      WeaverConfig().withOmitNoneFields(true)
    )
    body(handler, "/reply") shouldBe Some("""{"id":"r1","usage":{"input":10}}""")
    body(handler, "/rx-reply") shouldBe Some("""{"id":"r2","usage":{"input":20}}""")
  }

end RouterHandlerWeaverConfigTest

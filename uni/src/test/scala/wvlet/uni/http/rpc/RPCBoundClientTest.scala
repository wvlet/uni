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

import wvlet.uni.http.*
import wvlet.uni.rx.{OnCompletion, OnError, OnNext, Rx, RxRunner}
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.Weaver

case class Item(id: Long, tags: Seq[String]) derives Weaver

trait ItemApi:
  def get(id: Long): Item
  def tag(id: Long, tags: Seq[String]): Item
  def count(): Int
  def clear(): Unit

class ItemApiImpl extends ItemApi:
  var cleared                                = false
  def get(id: Long): Item                    = Item(id, Seq.empty)
  def tag(id: Long, tags: Seq[String]): Item = Item(id, tags)
  def count(): Int                           = 42
  def clear(): Unit                          = cleared = true

/**
  * Routes each request to an RPCDispatcher in memory, so the bound clients run their full path
  * (codec, HTTP client, dispatcher, router) on every platform without opening a socket.
  */
private class InMemoryRPCChannelFactory(dispatcher: RPCDispatcher) extends HttpChannelFactory:
  private def handle(request: Request): Response =
    var result: Response = null
    var error: Throwable = null
    RxRunner.runOnce(dispatcher.dispatch(request)) {
      case OnNext(r: Response) =>
        result = r
      case OnNext(_) =>
        throw IllegalStateException("RPCDispatcher must produce a Response")
      case OnError(e) =>
        error = e
      case OnCompletion =>
    }
    if error != null then
      throw error
    result

  override def newChannel: HttpChannel =
    new HttpChannel:
      override def send(request: Request, config: HttpClientConfig): Response = handle(request)
      override def close(): Unit                                              = ()

  override def newAsyncChannel: HttpAsyncChannel =
    new HttpAsyncChannel:
      override def send(request: Request, config: HttpClientConfig): Rx[Response] = Rx.single(
        handle(request)
      )

      override def sendStreaming(request: Request, config: HttpClientConfig): Rx[Array[Byte]] =
        throw NotImplementedError("Streaming is not used by RPC calls")

end InMemoryRPCChannelFactory

class RPCBoundClientTest extends UniTest:
  private val impl   = ItemApiImpl()
  private val config = Http
    .client
    .withChannelFactory(InMemoryRPCChannelFactory(RPCDispatcher(RPCRouter.of[ItemApi](impl))))

  test("newSyncClient binds the HTTP client once") {
    val rpc = RPCClient.of[ItemApi].newSyncClient(config.newSyncClient)
    rpc.call[Item]("get", 1L) shouldBe Item(1, Seq.empty)
    rpc.call[Int]("count") shouldBe 42
    rpc.call[Unit]("clear")
    impl.cleared shouldBe true
  }

  test("a Seq argument is passed as a single parameter") {
    val rpc = RPCClient.of[ItemApi].newSyncClient(config.newSyncClient)
    rpc.call[Item]("tag", 2L, Seq("a", "b")) shouldBe Item(2, Seq("a", "b"))
  }

  test("newAsyncClient returns results as Rx") {
    val rpc = RPCClient.of[ItemApi].newAsyncClient(config.newAsyncClient)
    rpc.call[Item]("tag", 3L, Seq("x")).map(_ shouldBe Item(3, Seq("x")))
  }

  test("one RPCClient can be shared by several bound clients") {
    val rpc = RPCClient.of[ItemApi]
    rpc.newSyncClient(config.newSyncClient).call[Int]("count") shouldBe 42
    rpc.newAsyncClient(config.newAsyncClient).call[Int]("count").map(_ shouldBe 42)
  }

end RPCBoundClientTest

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

import java.nio.charset.StandardCharsets

trait CountApi:
  def countTo(n: Int): Rx[Int]
  def failAfter(n: Int): Rx[Int]
  def answer(): Int

class CountApiImpl extends CountApi:
  def countTo(n: Int): Rx[Int]   = Rx.fromSeq(1 to n)
  def failAfter(n: Int): Rx[Int] = Rx
    .fromSeq(1 to n)
    .concat(Rx.exception(RPCStatus.INVALID_ARGUMENT_U2.newException("too far")))

  def answer(): Int = 42

/**
  * Serves streaming calls from an RPCDispatcher in memory: an event-stream response becomes the
  * bytes of its SSE body, as an HTTP server would write them.
  */
private class InMemoryStreamingChannelFactory(dispatcher: RPCDispatcher) extends HttpChannelFactory:
  private def handle(request: Request): Response =
    var result: Response = null
    RxRunner.runOnce(dispatcher.dispatch(request)) {
      case OnNext(r: Response) =>
        result = r
      case _ =>
    }
    result

  override def newChannel: HttpChannel = throw NotImplementedError("async only")

  override def newAsyncChannel: HttpAsyncChannel =
    new HttpAsyncChannel:
      override def send(request: Request, config: HttpClientConfig): Rx[Response] = Rx.single(
        handle(request)
      )

      override def sendStreaming(request: Request, config: HttpClientConfig): Rx[Array[Byte]] =
        sendStreaming(request, config, _ => ())

      override def sendStreaming(
          request: Request,
          config: HttpClientConfig,
          onResponse: HttpResponse => Unit
      ): Rx[Array[Byte]] =
        val response = handle(request)
        onResponse(response.withContent(HttpContent.Empty))
        if !response.isSuccessful then
          Rx.exception(HttpException.fromResponse(response))
        else if response.isEventStream then
          response.events.map(_.toContent.getBytes(StandardCharsets.UTF_8))
        else
          Rx.single(response.content.toContentBytes)

end InMemoryStreamingChannelFactory

class RPCStreamTest extends UniTest:
  private val client =
    Http
      .client
      .withChannelFactory(
        InMemoryStreamingChannelFactory(RPCDispatcher(RPCRouter.of[CountApi](CountApiImpl())))
      )
      .newAsyncClient

  private val rpc = RPCClient.of[CountApi].newAsyncClient(client)

  /** The values and the failure of a stream that runs to its end synchronously. */
  private def collect[A](rx: Rx[A]): (Seq[A], Option[Throwable]) =
    val values    = Seq.newBuilder[A]
    var failure   = Option.empty[Throwable]
    var completed = false
    RxRunner.run(rx) {
      case OnNext(v) =>
        values += v.asInstanceOf[A]
      case OnError(e) =>
        failure = Some(e)
      case OnCompletion =>
        completed = true
    }
    (completed || failure.isDefined) shouldBe true
    (values.result(), failure)

  test("stream emits every value of the server's Rx") {
    collect(rpc.stream[Int]("countTo", 3)) shouldBe (Seq(1, 2, 3), None)
  }

  test("a unary call to the same method still gets the first value") {
    collect(rpc.call[Int]("countTo", 3)) shouldBe (Seq(1), None)
  }

  test("a method that returns a plain value streams it as one event") {
    collect(rpc.stream[Int]("answer")) shouldBe (Seq(42), None)
  }

  test("a failure while streaming ends the stream with the server's RPCException") {
    val (values, failure) = collect(rpc.stream[Int]("failAfter", 2))
    values shouldBe Seq(1, 2)
    failure shouldMatch { case Some(e: RPCException) =>
      e.status shouldBe RPCStatus.INVALID_ARGUMENT_U2
      e.message shouldBe "too far"
    }
  }

  test("acceptsEventStream reads the Accept header") {
    val req = Request.post("/CountApi/countTo")
    RPCDispatcher.acceptsEventStream(req) shouldBe false
    RPCDispatcher.acceptsEventStream(req.withAcceptEventStream) shouldBe true
    RPCDispatcher.acceptsEventStream(req.withAccept("application/json, text/event-stream")) shouldBe
      true
    RPCDispatcher.acceptsEventStream(req.withAccept("application/json")) shouldBe false
  }

end RPCStreamTest

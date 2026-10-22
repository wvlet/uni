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
import wvlet.uni.msgpack.spi.{Packer, Unpacker, ValueType}
import wvlet.uni.test.UniTest
import wvlet.uni.weaver.{Weaver, WeaverConfig, WeaverContext}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8

object BodyBindingTest:
  /**
    * A token budget written as a number (1500) or a string with a k suffix ("2k"). The derived
    * Weaver would expect {"tokens": ...}, so a passing test shows the custom given was used.
    */
  case class Budget(tokens: Long)

  object Budget:
    given Weaver[Budget] with
      def pack(p: Packer, v: Budget, config: WeaverConfig): Unit = p.packLong(v.tokens)
      def unpack(u: Unpacker, context: WeaverContext): Unit      =
        u.getNextValueType match
          case ValueType.STRING =>
            context.setObject(Budget(u.unpackString.stripSuffix("k").toLong * 1000))
          case _ =>
            context.setObject(Budget(u.unpackLong))

  case class Message(role: String, text: String)
  case class MessagesRequest(model: String, messages: Seq[Message], budget: Option[Budget] = None)

  class BodyController:
    @Endpoint(HttpMethod.POST, "/v1/messages")
    def messages(request: MessagesRequest, httpRequest: Request): String =
      val budget = request.budget.map(_.tokens).getOrElse(0L)
      s"${request.model}:${request.messages.map(_.text).mkString(",")}:${budget}:${httpRequest
          .path}"

    @Endpoint(HttpMethod.PUT, "/budget")
    def setBudget(budget: Budget): String = s"${budget.tokens}"

    @Endpoint(HttpMethod.POST, "/projects/:id/rename")
    def rename(id: String, dryRun: Boolean = false, name: String, tags: Seq[String]): String =
      s"${id}:${dryRun}:${name}:${tags.mkString("|")}"

    @Endpoint(HttpMethod.POST, "/users")
    def createUser(user: Message, dryRun: Boolean = false): String =
      s"${user.role}/${user.text}:${dryRun}"

    @Endpoint(HttpMethod.POST, "/echo")
    def echo(request: Request, limit: Int = 10): String =
      s"${limit}:${request.content.toContentString}"

  // Weaver.of cannot derive these (no Weaver for the children), so Router.of must not try
  sealed trait Shape
  class Circle extends Shape
  sealed class Kind

  class UnderivableController:
    @Endpoint(HttpMethod.POST, "/shape")
    def shape(shape: Shape, kind: Option[Kind] = None): String = "ok"

end BodyBindingTest

class BodyBindingTest extends UniTest:
  import BodyBindingTest.*

  private val client = HttpClient.newHttpClient()

  private def withServer[A](f: String => A): A =
    val server = NettyServer
      .withPort(0)
      .noNativeTransport
      .withGracefulShutdown(quietPeriodSeconds = 0, timeoutSeconds = 5)
      .withRxHandler(RouterHandler(Router.of[BodyController]))
      .start()
    try f(s"http://127.0.0.1:${server.localPort}")
    finally server.stop()

  private def send(
      method: String,
      url: String,
      body: Array[Byte],
      contentType: Option[String]
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder(URI.create(url))
      .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
    contentType.foreach(builder.header("Content-Type", _))
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))

  private def post(url: String, json: String, contentType: String = "application/json") = send(
    "POST",
    url,
    json.getBytes(UTF_8),
    Some(contentType)
  )

  test("decode a JSON body into a case class next to a Request parameter") {
    withServer { base =>
      val r = post(
        s"${base}/v1/messages",
        """{"model":"m1","messages":[{"role":"user","text":"hi"},{"role":"user","text":"yo"}]}"""
      )
      r.statusCode() shouldBe 200
      r.body() shouldBe "m1:hi,yo:0:/v1/messages"
    }
  }

  test("use a custom given Weaver for a nested field and for the whole body") {
    withServer { base =>
      post(s"${base}/v1/messages", """{"model":"m1","messages":[],"budget":"2k"}""").body() shouldBe
        "m1::2000:/v1/messages"
      val r = send("PUT", s"${base}/budget", "\"3k\"".getBytes(UTF_8), Some("application/json"))
      r.statusCode() shouldBe 200
      r.body() shouldBe "3000"
    }
  }

  test("read several parameters from body fields, with path and query parameters") {
    withServer { base =>
      val r = post(
        s"${base}/projects/p1/rename?dryRun=true",
        """{"name":"new","tags":["a","b"],"ignored":1}"""
      )
      r.statusCode() shouldBe 200
      r.body() shouldBe "p1:true:new:a|b"
    }
  }

  test("choose whole-body decoding from the signature, not from the query string") {
    withServer { base =>
      val body = """{"role":"admin","text":"ann"}"""
      post(s"${base}/users", body).body() shouldBe "admin/ann:false"
      post(s"${base}/users?dryRun=true", body).body() shouldBe "admin/ann:true"
    }
  }

  test("decode MessagePack body fields") {
    withServer { base =>
      val packer = wvlet.uni.msgpack.spi.MessagePack.newPacker()
      packer.packMapHeader(2)
      packer.packString("name")
      packer.packString("n2")
      packer.packString("tags")
      packer.packArrayHeader(1)
      packer.packString("t")
      val r = send(
        "POST",
        s"${base}/projects/p9/rename",
        packer.toByteArray,
        Some("application/x-msgpack")
      )
      r.statusCode() shouldBe 200
      r.body() shouldBe "p9:false:n2:t"
    }
  }

  test("build a router for parameter types Weaver.of cannot derive") {
    wvlet.uni.http.router.Router.of[UnderivableController].routes.size shouldBe 1
  }

  test("decode a MessagePack body") {
    withServer { base =>
      val bytes = Weaver
        .of[MessagesRequest]
        .toMsgPack(MessagesRequest("m2", Seq(Message("user", "hey")), Some(Budget(5))))
      val r = send("POST", s"${base}/v1/messages", bytes, Some("application/msgpack"))
      r.statusCode() shouldBe 200
      r.body() shouldBe "m2:hey:5:/v1/messages"
    }
  }

  test("reject a malformed body with 400 without echoing it") {
    withServer { base =>
      val malformed = post(s"${base}/v1/messages", """{"model": "secret-value", """)
      malformed.statusCode() shouldBe 400
      malformed.body() shouldBe "Invalid request body for parameter 'request'"

      val wrongShape = post(s"${base}/projects/p1/rename", """["secret-value"]""")
      wrongShape.statusCode() shouldBe 400
      wrongShape.body() shouldBe "Request body must be an object"

      val badField = post(s"${base}/projects/p1/rename", """{"name":"n","tags":"secret-value"}""")
      badField.statusCode() shouldBe 400
      badField.body() shouldBe "Invalid request body for parameter 'tags'"

      val missing = post(s"${base}/v1/messages", "")
      missing.statusCode() shouldBe 400
      missing.body() shouldBe "Required parameter 'request' is missing"
    }
  }

  test("reject a body in an unsupported content type with 415") {
    withServer { base =>
      val r = post(s"${base}/v1/messages", "model=m1", "application/x-www-form-urlencoded")
      r.statusCode() shouldBe 415
      r.body() shouldBe "Unsupported content type: application/x-www-form-urlencoded"
    }
  }

  test("leave a body that a controller reads itself untouched") {
    withServer { base =>
      send("POST", s"${base}/echo", "plain text".getBytes(UTF_8), None).body() shouldBe
        "10:plain text"
      post(s"${base}/echo", "plain text", "text/plain").body() shouldBe "10:plain text"
      post(s"${base}/echo", """{"limit":3}""").body() shouldBe """3:{"limit":3}"""
      // Bodies that do not fit `limit` fall back to its default
      post(s"${base}/echo", "[1,2]").body() shouldBe "10:[1,2]"
      post(s"${base}/echo", "{not json").body() shouldBe "10:{not json"
      post(s"${base}/echo", """{"limit":"x"}""").body() shouldBe """10:{"limit":"x"}"""
    }
  }

end BodyBindingTest

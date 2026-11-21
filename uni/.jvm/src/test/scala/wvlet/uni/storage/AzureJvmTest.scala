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
package wvlet.uni.storage

import wvlet.uni.cloud.*
import wvlet.uni.util.Sha256

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import wvlet.uni.test.UniTest

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Checks Azure signing against javax.crypto, and token providers against a local server */
class AzureJvmTest extends UniTest:

  test("Shared Key signatures match javax.crypto.Mac") {
    val key =
      "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="
    val sts =
      "GET\n\n\n\n\n\n\n\n\n\n\n\nx-ms-date:Fri, 26 Jun 2015 23:39:12 GMT\nx-ms-version:2015-02-21\n/myaccount/mycontainer\ncomp:metadata\nrestype:container\ntimeout:20"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(java.util.Base64.getDecoder.decode(key), "HmacSHA256"))
    val expected = java.util.Base64.getEncoder.encodeToString(mac.doFinal(sts.getBytes(UTF_8)))
    AzureAuth.sign(key, sts) shouldBe expected
  }

  private case class Seen(
      method: String,
      path: String,
      query: String,
      body: String,
      headers: Map[String, String]
  )

  private def withServer[A](status: Int, json: String)(
      body: (String, () => Seq[Seen], AtomicInteger) => A
  ): A =
    val seen   = Seq.newBuilder[Seen]
    val calls  = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) =>
        try
          calls.incrementAndGet()
          val headers = Map.newBuilder[String, String]
          exchange.getRequestHeaders.forEach((k, v) => headers += k.toLowerCase -> v.get(0))
          seen.synchronized {
            seen +=
              Seen(
                exchange.getRequestMethod,
                exchange.getRequestURI.getRawPath,
                Option(exchange.getRequestURI.getRawQuery).getOrElse(""),
                String(exchange.getRequestBody.readAllBytes(), UTF_8),
                headers.result()
              )
          }
          val bytes = json.getBytes(UTF_8)
          exchange.sendResponseHeaders(status, bytes.length)
          exchange.getResponseBody.write(bytes)
        finally
          exchange.close()
    )
    server.start()
    try body(
        s"http://127.0.0.1:${server.getAddress.getPort}",
        () => seen.synchronized(seen.result()),
        calls
      )
    finally server.stop(0)

  end withServer

  test("client credentials post the secret and the storage scope") {
    withServer(200, """{"token_type":"Bearer","expires_in":3599,"access_token":"tok1"}""") {
      (url, seen, calls) =>
        val p = AzureCredentialsProvider.clientSecret("my-tenant", "my-client", "s3cr&t", Some(url))
        p.credentials shouldMatch { case Some(AzureCredentials.Bearer("tok1", Some(_))) =>
        }
        // Cached until it nears expiry
        p.credentials
        calls.get() shouldBe 1
        val req = seen().head
        req.method shouldBe "POST"
        req.path shouldBe "/my-tenant/oauth2/v2.0/token"
        req.headers.get("content-type") shouldBe Some("application/x-www-form-urlencoded")
        req.body.split("&").toSet shouldBe
          Set(
            "grant_type=client_credentials",
            "client_id=my-client",
            "scope=https%3A%2F%2Fstorage.azure.com%2F.default",
            "client_secret=s3cr%26t"
          )
    }
  }

  test("a refused token request names the reason") {
    withServer(
      401,
      """{"error":"invalid_client","error_description":"AADSTS7000215: Invalid client secret.\r\nTrace ID: x"}"""
    ) { (url, _, _) =>
      val e = intercept[CloudException] {
        AzureCredentialsProvider.clientSecret("t", "c", "wrong", Some(url)).credentials
      }
      e.code shouldBe CloudErrorCode.NoCredentials
      e.getMessage shouldContain "AADSTS7000215: Invalid client secret."
    }
  }

  test("managed identity asks IMDS for a storage token") {
    withServer(200, """{"access_token":"mi","expires_on":"4102444800","token_type":"Bearer"}""") {
      (url, seen, _) =>
        val p = AzureCredentialsProvider.ManagedIdentityProvider(
          Some("user-assigned"),
          s"${url}/metadata/identity/oauth2/token"
        )
        p.credentials shouldBe Some(AzureCredentials.Bearer("mi", Some(4102444800000L)))
        val req = seen().head
        req.method shouldBe "GET"
        req.path shouldBe "/metadata/identity/oauth2/token"
        req.headers.get("metadata") shouldBe Some("true")
        req.query.split("&").toSet shouldBe
          Set(
            "api-version=2018-02-01",
            "resource=https%3A%2F%2Fstorage.azure.com%2F",
            "client_id=user-assigned"
          )
    }
  }

  test("managed identity has nothing to give when no identity is assigned") {
    withServer(400, """{"error":"invalid_request"}""") { (url, _, _) =>
      AzureCredentialsProvider.ManagedIdentityProvider(None, s"${url}/token").credentials shouldBe
        None
    }
  }

  test("a chain falls through to the next provider") {
    val failing =
      new AzureCredentialsProvider:
        def name: String                          = "failing"
        def credentials: Option[AzureCredentials] = throw RuntimeException("unreachable")
    val chain = AzureCredentialsProvider.chain(failing, AzureCredentialsProvider.sas("sv=x"))
    chain.credentials shouldBe Some(AzureCredentials.Sas("sv=x"))
    val empty = AzureCredentialsProvider.chain(failing)
    val e     = intercept[CloudException] {
      empty.credentials
    }
    e.code shouldBe CloudErrorCode.NoCredentials
    // The reason a provider failed is part of the message
    e.getMessage shouldContain "failing: unreachable"
  }

end AzureJvmTest

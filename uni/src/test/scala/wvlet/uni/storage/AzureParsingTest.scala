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

import wvlet.uni.http.HttpHeader
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.HttpMultiMap
import wvlet.uni.http.HttpStatus
import wvlet.uni.http.HttpContent
import wvlet.uni.http.Response
import wvlet.uni.storage.AzureObjectStore.ListPage
import wvlet.uni.storage.AzureObjectStore.StorageCall
import wvlet.uni.test.UniTest

class AzureParsingTest extends UniTest:
  private val key = "a2V5" // Base64 of "key"

  test("connection strings") {
    val c = AzureConfig.fromConnectionString(
      s"DefaultEndpointsProtocol=https;AccountName=acct;AccountKey=${key};EndpointSuffix=core.windows.net"
    )
    c.resolvedAccount shouldBe "acct"
    c.resolvedEndpoint shouldBe "https://acct.blob.core.windows.net"
    c.credentials.credentials shouldBe Some(AzureCredentials.SharedKey("acct", key))

    val sas = AzureConfig.fromConnectionString(
      "BlobEndpoint=https://acct.blob.core.chinacloudapi.cn/;SharedAccessSignature=sv=2024-11-04&sp=r&sig=abc%3D"
    )
    sas.resolvedAccount shouldBe "acct"
    sas.resolvedEndpoint shouldBe "https://acct.blob.core.chinacloudapi.cn"
    sas.credentials.credentials shouldBe Some(AzureCredentials.Sas("sv=2024-11-04&sp=r&sig=abc%3D"))

    val dev = AzureConfig.fromConnectionString("UseDevelopmentStorage=true")
    dev.resolvedAccount shouldBe "devstoreaccount1"
    dev.resolvedEndpoint shouldBe "http://127.0.0.1:10000/devstoreaccount1"

    intercept[IllegalArgumentException] {
      AzureConfig.fromConnectionString("AccountKey=abc")
    }
  }

  test("the account comes from the config, the endpoint, or a shared key") {
    AzureConfig().withAccount("a").resolvedEndpoint shouldBe "https://a.blob.core.windows.net"
    AzureConfig().withEndpoint("https://b.blob.core.windows.net/").resolvedAccount shouldBe "b"
    AzureConfig.azurite("http://localhost:10000/devstoreaccount1").resolvedAccount shouldBe
      "devstoreaccount1"
    AzureConfig().withCredentials(AzureCredentials.SharedKey("k", key)).resolvedAccount shouldBe "k"
    intercept[IllegalArgumentException] {
      AzureConfig().withBlockSizeBytes(1024)
    }
  }

  test("credentials hide secrets in toString") {
    AzureCredentials.SharedKey("acct", "secret-key").toString shouldNotContain "secret-key"
    val sas = AzureCredentials.Sas("?sv=2024-11-04&sp=r&sig=SECRET").toString
    sas shouldNotContain "SECRET"
    sas shouldContain "sp=r"
    AzureCredentials.Bearer("eyJ-token").toString shouldNotContain "eyJ"
  }

  test("az:// and azure:// URIs keep their scheme") {
    val u = ObjectUri.parse("az://container/dir/file.parquet")
    u.bucket shouldBe "container"
    u.key shouldBe "dir/file.parquet"
    u.toString shouldBe "az://container/dir/file.parquet"
    ObjectUri.parse("azure://c/k").scheme shouldBe "azure"
  }

  test("requests carry the version, the date, and a Shared Key signature") {
    val store = ObjectStore.azure(AzureConfig.azurite())
    try
      val req = store.buildRequest(
        StorageCall(
          HttpMethod.GET,
          ObjectUri.parse("az://c/dir/a b+c.txt"),
          headers = Seq("x-ms-range" -> "bytes=0-9")
        ),
        Some(AzureCredentials.SharedKey("devstoreaccount1", key)),
        1435361952000L
      )
      req.uri shouldBe "http://127.0.0.1:10000/devstoreaccount1/c/dir/a%20b%2Bc.txt"
      req.header("x-ms-version") shouldBe Some(AzureAuth.Version)
      req.header("x-ms-date") shouldBe Some("Fri, 26 Jun 2015 23:39:12 GMT")
      val sts =
        "GET\n\n\n\n\n\n\n\n\n\n\n\nx-ms-date:Fri, 26 Jun 2015 23:39:12 GMT\nx-ms-range:bytes=0-9\n" +
          s"x-ms-version:${AzureAuth
              .Version}\n/devstoreaccount1/devstoreaccount1/c/dir/a%20b%2Bc.txt"
      req.header("Authorization") shouldBe
        Some(AzureAuth.authorization("devstoreaccount1", key, sts))
    finally
      store.close()
  }

  test("a SAS token is appended to the query; a bearer token needs HTTPS") {
    val store = ObjectStore.azure(AzureConfig().withAccount("acct").noCredentials)
    try
      val list = store.buildRequest(
        StorageCall(
          HttpMethod.GET,
          ObjectUri.parse("az://c/"),
          query = Seq("restype" -> "container", "comp" -> "list")
        ),
        Some(AzureCredentials.Sas("?sv=2024-11-04&sig=x%3D")),
        0L
      )
      list.uri shouldBe
        "https://acct.blob.core.windows.net/c?comp=list&restype=container&sv=2024-11-04&sig=x%3D"
      list.header("Authorization") shouldBe None

      val bearer = store.buildRequest(
        StorageCall(HttpMethod.GET, ObjectUri.parse("az://c/k")),
        Some(AzureCredentials.Bearer("tok")),
        0L
      )
      bearer.header("Authorization") shouldBe Some("Bearer tok")
    finally
      store.close()

    val plain = ObjectStore.azure(AzureConfig.azurite())
    try
      val e = intercept[StorageException] {
        plain.buildRequest(
          StorageCall(HttpMethod.GET, ObjectUri.parse("az://c/k")),
          Some(AzureCredentials.Bearer("tok")),
          0L
        )
      }
      e.code shouldBe CloudErrorCode.InvalidConfig
    finally
      plain.close()
  }

  test("other schemes are rejected") {
    val store = ObjectStore.azure(AzureConfig.azurite())
    try intercept[IllegalArgumentException] {
        store.url("s3://bucket/key")
      }
    finally store.close()
  }

  test("presign signs a service SAS with a shared key") {
    val store = ObjectStore.azure(AzureConfig().withSharedKey("acct", key))
    try
      val url = store.presign(ObjectUri.parse("az://c/a b.txt"), HttpMethod.PUT, 600)
      url shouldMatch {
        case u: String
            if u.startsWith("https://acct.blob.core.windows.net/c/a%20b.txt?sp=cw&se=") =>
      }
      url shouldContain s"&sv=${AzureAuth.Version}&sr=b&sig="
      intercept[IllegalArgumentException] {
        store.presign(ObjectUri.parse("az://c/a"), HttpMethod.GET, AzureAuth.MaxPresignSeconds + 1)
      }
    finally
      store.close()

    val sasOnly = ObjectStore.azure(AzureConfig().withAccount("acct").withSasToken("sv=x&sig=y"))
    try
      intercept[StorageException] {
        sasOnly.presign("az://c/a")
      }.code shouldBe CloudErrorCode.NoCredentials
    finally sasOnly.close()
  }

  test("listings: blobs, prefixes, encoded names, and the next marker") {
    val xml =
      """<?xml version="1.0" encoding="utf-8"?>
        |<EnumerationResults ServiceEndpoint="https://acct.blob.core.windows.net/" ContainerName="c">
        |  <Prefix>logs/</Prefix>
        |  <Blobs>
        |    <Blob>
        |      <Name>logs/a.txt</Name>
        |      <Properties>
        |        <Last-Modified>Fri, 20 Nov 2026 18:00:00 GMT</Last-Modified>
        |        <Etag>0x8D</Etag>
        |        <Content-Length>42</Content-Length>
        |        <Content-Type>text/plain</Content-Type>
        |      </Properties>
        |    </Blob>
        |    <BlobPrefix><Name>logs/2026/</Name></BlobPrefix>
        |    <Blob>
        |      <Name Encoded="true">logs/odd%EF%BF%BEname</Name>
        |      <Properties><Content-Length>0</Content-Length></Properties>
        |    </Blob>
        |  </Blobs>
        |  <NextMarker>2!72!MDAwMDA0</NextMarker>
        |</EnumerationResults>""".stripMargin
    val page = AzureObjectStore.parseListing(ObjectUri.parse("az://c/logs/"), xml.getBytes("UTF-8"))
    page.entries.map(_.uri.toString) shouldBe
      Seq("az://c/logs/a.txt", "az://c/logs/odd￾name", "az://c/logs/2026/")
    page.entries.head.size shouldBe 42L
    page.entries.head.etag shouldBe Some("0x8D")
    page.entries.head.contentType shouldBe Some("text/plain")
    page.entries.last.isDirectory shouldBe true
    page.nextToken shouldBe Some("2!72!MDAwMDA0")

    val last = AzureObjectStore.parseListing(
      ObjectUri.parse("az://c/"),
      "<EnumerationResults><Blobs/><NextMarker/></EnumerationResults>".getBytes("UTF-8")
    )
    last shouldBe ListPage(Nil, None)
  }

  test("percent-decoding keeps + and decodes UTF-8") {
    AzureAuth.percentDecode("a+b%20c%E3%81%82") shouldBe "a+b cあ"
    AzureAuth.percentDecode("100%") shouldBe "100%"
  }

  private def response(status: Int, body: String, headers: (String, String)*): Response =
    val h = headers.foldLeft(HttpMultiMap.newBuilder)((b, kv) => b.add(kv._1, kv._2)).result()
    val r = Response(HttpStatus.ofCode(status), h)
    if body.isEmpty then
      r
    else
      r.withContent(HttpContent.bytes(body.getBytes("UTF-8")))

  test("errors keep Azure's code, from the body or the header") {
    val uri  = ObjectUri.parse("az://c/missing")
    val body =
      """<?xml version="1.0" encoding="utf-8"?><Error><Code>BlobNotFound</Code><Message>The specified blob does not exist.
        |RequestId:abc
        |Time:2026-11-20T00:00:00Z</Message></Error>""".stripMargin
    val e = AzureObjectStore.errorOf(uri, response(404, body))
    e.code shouldBe "BlobNotFound"
    e.getMessage shouldBe "az://c/missing: BlobNotFound The specified blob does not exist."
    e.isNotFound shouldBe true

    // HEAD responses carry no body
    val h = AzureObjectStore.errorOf(
      uri,
      response(403, "", "x-ms-error-code" -> "AuthenticationFailed")
    )
    h.code shouldBe "AuthenticationFailed"
    h.statusCode shouldBe Some(403)

    AzureObjectStore.errorOf(uri, response(503, "")).code shouldBe "HTTP503"
  }

  test("user delegation keys") {
    val k = AzureObjectStore.parseDelegationKey(
      """<?xml version="1.0" encoding="utf-8"?><UserDelegationKey><SignedOid>o</SignedOid><SignedTid>t</SignedTid>
        |<SignedStart>2026-11-20T00:00:00Z</SignedStart><SignedExpiry>2026-11-27T00:00:00Z</SignedExpiry>
        |<SignedService>b</SignedService><SignedVersion>2024-11-04</SignedVersion><Value>a2V5</Value></UserDelegationKey>"""
        .stripMargin
        .getBytes("UTF-8")
    )
    k shouldBe
      UserDelegationKey(
        "o",
        "t",
        "2026-11-20T00:00:00Z",
        "2026-11-27T00:00:00Z",
        "b",
        "2024-11-04",
        "a2V5"
      )
    k.expiryMillis shouldBe AwsTime.parseIso("2026-11-27T00:00:00Z").get
    k.toString shouldNotContain "a2V5"
  }

  test("token responses from Entra ID and managed identity endpoints") {
    val now = 1_000_000L
    AzureCredentialsProvider.parseTokenJson(
      """{"token_type":"Bearer","expires_in":3599,"access_token":"abc"}""",
      now
    ) shouldBe Some(AzureCredentials.Bearer("abc", Some(now + 3599 * 1000L)))
    AzureCredentialsProvider.parseTokenJson(
      """{"access_token":"def","expires_in":"3599","expires_on":"1506484173","token_type":"Bearer"}""",
      now
    ) shouldBe Some(AzureCredentials.Bearer("def", Some(1506484173000L)))
    AzureCredentialsProvider.parseTokenJson("""{"error":"invalid_client"}""", now) shouldBe None
  }

  test("XML attributes") {
    val x = XmlReader.parse("""<a k="v" q='x>y' e="&amp;&lt;"><b flag/><c n="1"/>text</a>""")
    x.attributes shouldBe Map("k" -> "v", "q" -> "x>y", "e" -> "&<")
    x.child("b").map(_.attributes) shouldBe Some(Map("flag" -> ""))
    x.child("c").flatMap(_.attribute("n")) shouldBe Some("1")
    x.text shouldBe "text"
  }

end AzureParsingTest

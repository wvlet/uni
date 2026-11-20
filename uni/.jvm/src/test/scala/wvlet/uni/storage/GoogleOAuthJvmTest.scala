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

import wvlet.uni.http.Http
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.HttpServer
import wvlet.uni.http.HttpStatus
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest
import wvlet.uni.util.URLEncoder

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
  * OAuth flows and resumable uploads against a local fake of Google's token endpoint, metadata
  * server, IAM Credentials API, and upload sessions
  */
class GoogleOAuthJvmTest extends UniTest:
  private val tokenCalls    = AtomicInteger(0)
  private val metadataCalls = AtomicInteger(0)
  @volatile
  private var lastAuthorization: Option[String] = None

  @volatile
  private var sessionAuthorization: Option[String] = None

  private val key = Rsa.parsePem(GcsSignedUrlTest.pem)

  // Upload session state: bytes committed so far, and how many bytes of each chunk to keep
  private val uploaded = ByteArrayOutputStream()
  @volatile
  private var keepPerChunk = 256 * 1024

  @volatile
  private var completed: Option[Array[Byte]] = None

  @volatile
  private var cancelled = false

  private def form(req: Request): Map[String, String] =
    req
      .content
      .toContentString
      .split('&')
      .filter(_.nonEmpty)
      .map { kv =>
        val i = kv.indexOf('=')
        URLEncoder.decode(kv.substring(0, i)) -> URLEncoder.decode(kv.substring(i + 1))
      }
      .toMap

  private def tokenResponse(token: String): Response = Response
    .ok
    .withJsonContent(s"""{"access_token":"${token}","expires_in":3599,"token_type":"Bearer"}""")

  private def handle(req: Request): Response =
    req.path match
      case "/token" =>
        tokenCalls.incrementAndGet()
        val f = form(req)
        f.get("grant_type") match
          case Some("urn:ietf:params:oauth:grant-type:jwt-bearer") =>
            val parts = f("assertion").split('.')
            val ok    = Rsa.verifySha256(
              key.modulus,
              key.publicExponent,
              s"${parts(0)}.${parts(1)}".getBytes("UTF-8"),
              Base64.getUrlDecoder.decode(parts(2))
            )
            if ok then
              tokenResponse("sa-token")
            else
              Response(HttpStatus.BadRequest_400).withJsonContent("""{"error":"invalid_grant"}""")
          case Some("refresh_token") if f.get("refresh_token").contains("good-refresh") =>
            tokenResponse("user-token")
          case _ =>
            Response(HttpStatus.BadRequest_400).withJsonContent(
              """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""
            )
      case "/computeMetadata/v1/instance/service-accounts/default/token" =>
        metadataCalls.incrementAndGet()
        if req.header("Metadata-Flavor").contains("Google") then
          tokenResponse("metadata-token").setHeader("Metadata-Flavor", "Google")
        else
          Response(HttpStatus.Forbidden_403)
      case "/computeMetadata/v1/instance/service-accounts/default/email" =>
        Response
          .ok
          .withTextContent("vm@example.iam.gserviceaccount.com")
          .setHeader("Metadata-Flavor", "Google")
      case p if p.endsWith(":signBlob") =>
        lastAuthorization = req.header("Authorization")
        val payload =
          JSON.parse(req.content.toContentString) match
            case o: JSON.JSONObject =>
              o.get("payload")
                .collect { case JSON.JSONString(s) =>
                  s
                }
                .get
            case _ =>
              ""
        val sig = Rsa.signSha256(key, Base64.getDecoder.decode(payload))
        Response
          .ok
          .withJsonContent(
            s"""{"keyId":"k","signedBlob":"${Base64.getEncoder.encodeToString(sig)}"}"""
          )
      case "/upload/storage/v1/b/bucket/o"
          if req.getQueryParam("uploadType").contains("resumable") =>
        uploaded.reset()
        completed = None
        Response.ok.setHeader("Location", s"http://127.0.0.1:${server.localPort}/session/1")
      case "/session/1" if req.method == HttpMethod.DELETE =>
        cancelled = true
        Response(HttpStatus.ofCode(499))
      case "/session/1" =>
        sessionAuthorization = req.header("Authorization")
        handleChunk(req)
      case _ =>
        Response.notFound

  // Keeps only `keepPerChunk` bytes of each non-final chunk, as GCS may
  private def handleChunk(req: Request): Response = synchronized {
    val range = req.header("Content-Range").getOrElse("")
    val body  = req.content.toContentBytes
    val total = range.substring(range.lastIndexOf('/') + 1)
    if !range.startsWith("bytes */") then
      val start = range.stripPrefix("bytes ").takeWhile(_ != '-').toLong
      if start != uploaded.size() then
        return Response(HttpStatus.BadRequest_400)
      val keep =
        if total == "*" then
          math.min(keepPerChunk, body.length)
        else
          body.length
      uploaded.write(body, 0, keep)
    if total != "*" && uploaded.size().toString == total then
      completed = Some(uploaded.toByteArray)
      Response.ok.withJsonContent("""{"name":"big.bin"}""")
    else
      val r = Response.ok.setHeader("X-Http-Status-Code-Override", "308")
      if uploaded.size() > 0 then
        r.setHeader("Range", s"bytes=0-${uploaded.size() - 1}")
      else
        r
  }

  private lazy val server: HttpServer = Http.server.withPort(0).withHandler(handle).start()
  private def base: String            = s"http://127.0.0.1:${server.localPort}"

  override protected def afterAll: Unit = server.stop()

  private def serviceAccount = GoogleServiceAccountKey(
    GcsSignedUrlTest.email,
    key,
    Some("key-1"),
    tokenUri = s"${base}/token"
  )

  test("service account: JWT bearer flow, cached") {
    val before   = tokenCalls.get()
    val provider = GoogleCredentialsProvider.serviceAccount(serviceAccount)
    provider.accessToken.map(_.token) shouldBe Some("sa-token")
    provider.accessToken.map(_.token) shouldBe Some("sa-token")
    tokenCalls.get() - before shouldBe 1
  }

  test("authorized user: refresh-token flow") {
    val ok = GoogleCredentialsProvider.authorizedUser(
      GoogleAuthorizedUser("cid", "csecret", "good-refresh", s"${base}/token")
    )
    ok.accessToken.map(_.token) shouldBe Some("user-token")
    ok.signer shouldBe None
  }

  test("a refused refresh token reports Google's error, never the secrets") {
    val bad = GoogleCredentialsProvider.authorizedUser(
      GoogleAuthorizedUser("cid", "csecret", "revoked-refresh", s"${base}/token")
    )
    val e = intercept[StorageException] {
      bad.accessToken
    }
    e.code shouldBe StorageErrorCode.NoCredentials
    e.statusCode shouldBe Some(400)
    e.getMessage shouldContain "invalid_grant"
    e.getMessage shouldNotContain "revoked-refresh"
    e.getMessage shouldNotContain "csecret"
  }

  test("metadata server: token and signBlob signing") {
    val provider = GoogleCredentialsProvider.MetadataServerProvider(
      () => s"127.0.0.1:${server.localPort}",
      base
    )
    val before = metadataCalls.get()
    provider.accessToken.map(_.token) shouldBe Some("metadata-token")
    provider.accessToken.map(_.token) shouldBe Some("metadata-token")
    metadataCalls.get() - before shouldBe 1

    val signer = provider.signer.get
    signer.serviceAccountEmail shouldBe "vm@example.iam.gserviceaccount.com"
    val sig = signer.sign("data".getBytes("UTF-8"))
    Rsa.verifySha256(key.modulus, key.publicExponent, "data".getBytes("UTF-8"), sig) shouldBe true
    lastAuthorization shouldBe Some("Bearer metadata-token")
  }

  test("no metadata server: no token, so the chain moves on") {
    // Nothing listens on port 1
    val provider = GoogleCredentialsProvider.MetadataServerProvider(() => "127.0.0.1:1", base)
    provider.accessToken shouldBe None
  }

  test("presign with metadata-server credentials signs through IAM") {
    val store = ObjectStore.gcs(
      GcsConfig().withCredentials(
        GoogleCredentialsProvider.MetadataServerProvider(
          () => s"127.0.0.1:${server.localPort}",
          base
        )
      )
    )
    try
      val url = store.presign("gs://bucket/report.pdf")
      url shouldContain "https://storage.googleapis.com/bucket/report.pdf?"
      url shouldContain "X-Goog-Credential=vm%40example.iam.gserviceaccount.com"
    finally
      store.close()
  }

  test("presign with user credentials explains why it can't sign") {
    val store = ObjectStore.gcs(GcsConfig().withAccessToken("t"))
    try
      intercept[StorageException] {
        store.presign("gs://bucket/x")
      }.code shouldBe StorageErrorCode.NoCredentials
    finally store.close()
  }

  private def uploadStore = ObjectStore.gcs(
    GcsConfig().withEndpoint(base).withAccessToken("upload-token").withChunkSizeBytes(512 * 1024)
  )

  test("resumable upload resends what a chunk response did not commit") {
    keepPerChunk = 256 * 1024
    val data  = Array.tabulate[Byte](2 * 1024 * 1024 + 12345)(i => (i % 253).toByte)
    val store = uploadStore
    try
      val out = store.newOutputStream("gs://bucket/big.bin")
      data.grouped(100 * 1024 + 3).foreach(out.write(_))
      out.close()
      completed.map(_.toSeq) shouldBe Some(data.toSeq)
      // The session URI is the credential; no bearer token goes to it
      sessionAuthorization shouldBe None
    finally
      store.close()
  }

  test("resumable upload of an exact multiple of the chunk size") {
    keepPerChunk = 512 * 1024
    val data  = Array.fill[Byte](1024 * 1024)(7)
    val store = uploadStore
    try
      val out = store.newOutputStream("gs://bucket/big.bin")
      out.write(data)
      out.close()
      completed.map(_.length) shouldBe Some(data.length)
    finally
      store.close()
  }

  test("an upload that stops committing fails instead of looping") {
    keepPerChunk = 0
    val store = uploadStore
    try
      val out = store.newOutputStream("gs://bucket/big.bin")
      val e   = intercept[StorageException] {
        out.write(Array.fill[Byte](600 * 1024)(1))
      }
      e.getMessage shouldContain "stopped making progress"
      e.getMessage shouldNotContain "/session/"
      // A failed upload is cancelled
      cancelled shouldBe true
    finally
      store.close()
  }

end GoogleOAuthJvmTest

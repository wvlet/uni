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

import wvlet.uni.http.HttpStatus
import wvlet.uni.http.Response
import wvlet.uni.json.JSON
import wvlet.uni.test.UniTest

import java.util.Base64

class GoogleCredentialsTest extends UniTest:
  private def serviceAccountJson(tokenUri: String = GoogleOAuth.DefaultTokenUri): String =
    s"""{
       |  "type": "service_account",
       |  "project_id": "dummy-project-id",
       |  "private_key_id": "key-1",
       |  "private_key": ${JSON.JSONString(GcsSignedUrlTest.pem).toJSON},
       |  "client_email": "${GcsSignedUrlTest.email}",
       |  "token_uri": "${tokenUri}"
       |}""".stripMargin

  test("parse a service-account key file") {
    GoogleCredentialsFile.parse(serviceAccountJson(), "test") shouldMatch { case Left(key) =>
      key.clientEmail shouldBe GcsSignedUrlTest.email
      key.privateKeyId shouldBe Some("key-1")
      key.projectId shouldBe Some("dummy-project-id")
      key.toString shouldNotContain "BEGIN"
    }
  }

  test("parse gcloud authorized_user credentials") {
    val json =
      """{"type":"authorized_user","client_id":"cid","client_secret":"csecret","refresh_token":"rtoken","quota_project_id":"p"}"""
    GoogleCredentialsFile.parse(json, "test") shouldMatch { case Right(user) =>
      user.clientId shouldBe "cid"
      user.quotaProjectId shouldBe Some("p")
      user.toString shouldNotContain "csecret"
      user.toString shouldNotContain "rtoken"
    }
  }

  test("unsupported credential types fail with their type, not a fallback") {
    val e = intercept[StorageException] {
      GoogleCredentialsFile.parse("""{"type":"external_account","audience":"x"}""", "adc.json")
    }
    e.code shouldBe StorageErrorCode.InvalidCredentials
    e.getMessage shouldContain "external_account"
    e.getMessage shouldContain "adc.json"
  }

  test("malformed files never echo their contents") {
    val e = intercept[StorageException] {
      GoogleCredentialsFile.parse("""{"type":"authorized_user","client_secret":"s3cr3t"}""", "f")
    }
    e.getMessage shouldNotContain "s3cr3t"
    intercept[StorageException] {
      GoogleCredentialsFile.parse("not json", "f")
    }.code shouldBe StorageErrorCode.InvalidCredentials
  }

  test("tokens are masked") {
    GoogleAccessToken("ya29.secret").toString shouldNotContain "ya29"
    GoogleCredentialsProvider.static("ya29.secret").accessToken.map(_.token) shouldBe
      Some("ya29.secret")
  }

  test("JWT assertion for the token endpoint") {
    val key = GoogleCredentialsFile.parse(serviceAccountJson(), "test").left.toOption.get
    val jwt = GoogleOAuth.jwtAssertion(
      key,
      Seq(GoogleCredentialsProvider.StorageReadWrite),
      1000000L
    )
    val parts = jwt.split('.')
    parts.length shouldBe 3
    val dec    = Base64.getUrlDecoder
    val header = String(dec.decode(parts(0)), "UTF-8")
    header shouldBe """{"alg":"RS256","typ":"JWT","kid":"key-1"}"""
    val claims = JSON.parse(String(dec.decode(parts(1)), "UTF-8"))
    claims shouldMatch { case o: JSON.JSONObject =>
      o.get("iss") shouldBe Some(JSON.JSONString(GcsSignedUrlTest.email))
      o.get("aud") shouldBe Some(JSON.JSONString(GoogleOAuth.DefaultTokenUri))
      o.get("scope") shouldBe Some(JSON.JSONString(GoogleCredentialsProvider.StorageReadWrite))
      o.get("iat") shouldBe Some(JSON.JSONLong(1000L))
      o.get("exp") shouldBe Some(JSON.JSONLong(4600L))
    }
    Rsa.verifySha256(
      key.privateKey.modulus,
      key.privateKey.publicExponent,
      s"${parts(0)}.${parts(1)}".getBytes("UTF-8"),
      dec.decode(parts(2))
    ) shouldBe true
  }

  test("token responses") {
    val t = GoogleOAuth.parseTokenResponse(
      """{"access_token":"abc","expires_in":3599,"token_type":"Bearer"}""",
      "test",
      nowMillis = 1000L
    )
    t.token shouldBe "abc"
    t.expiresAtMillis shouldBe Some(3600000L)
    t.isFresh(1000L) shouldBe true
    t.isFresh(3600000L - GoogleAccessToken.RefreshMarginMillis) shouldBe false
    intercept[StorageException] {
      GoogleOAuth.parseTokenResponse("""{"token_type":"Bearer"}""", "test")
    }.code shouldBe StorageErrorCode.InvalidResponse
  }

  test("token endpoints must use HTTPS, except loopback") {
    GoogleOAuth.checkTokenUri("https://oauth2.googleapis.com/token")
    GoogleOAuth.checkTokenUri("http://127.0.0.1:8080/token")
    GoogleOAuth.checkTokenUri("http://localhost/token")
    intercept[StorageException] {
      GoogleOAuth.checkTokenUri("http://example.com/token")
    }.code shouldBe StorageErrorCode.InvalidCredentials
    intercept[StorageException] {
      GoogleOAuth.checkTokenUri("http://localhost.example.com/token")
    }.code shouldBe StorageErrorCode.InvalidCredentials
  }

  test("tokens are cached until shortly before they expire") {
    var calls = 0
    var now   = System.currentTimeMillis()
    val cache = GoogleCredentialsProvider.TokenCache { () =>
      calls += 1
      Some(GoogleAccessToken(s"t${calls}", Some(System.currentTimeMillis() + 3600 * 1000L)))
    }
    cache.get.map(_.token) shouldBe Some("t1")
    cache.get.map(_.token) shouldBe Some("t1")
    calls shouldBe 1
    val expiring = GoogleCredentialsProvider.TokenCache { () =>
      calls += 1
      Some(GoogleAccessToken(s"t${calls}", Some(System.currentTimeMillis() + 60 * 1000L)))
    }
    expiring.get
    expiring.get
    calls shouldBe 3
  }

  test("a chain with no source names every provider") {
    val e = intercept[StorageException] {
      GoogleCredentialsProvider
        .chain(GoogleCredentialsProvider.anonymous, GoogleCredentialsProvider.anonymous)
        .accessToken
    }
    e.code shouldBe StorageErrorCode.NoCredentials
    e.getMessage shouldContain "anonymous, anonymous"
  }

  test("a chain uses the first provider with a token, and its signer") {
    val key   = GoogleCredentialsFile.parse(serviceAccountJson(), "test").left.toOption.get
    val sa    = GoogleCredentialsProvider.serviceAccount(key)
    val chain = GoogleCredentialsProvider.chain(
      GoogleCredentialsProvider.anonymous,
      GoogleCredentialsProvider.static("tok")
    )
    chain.accessToken.map(_.token) shouldBe Some("tok")
    chain.signer shouldBe None
    sa.signer.map(_.serviceAccountEmail) shouldBe Some(GcsSignedUrlTest.email)
  }

  private def response(status: Int, body: String): Response = Response(HttpStatus.ofCode(status))
    .withJsonContent(body)

  test("GCS errors map to storage error codes") {
    val uri                                               = ObjectUri("b", "k", "gs")
    def err(status: Int, message: String, reason: String) = GcsObjectStore.errorOf(
      uri,
      response(
        status,
        s"""{"error":{"code":${status},"message":"${message}","errors":[{"reason":"${reason}"}]}}"""
      )
    )
    err(404, "No such object: b/k", "notFound").code shouldBe StorageErrorCode.NoSuchKey
    // An object whose name mentions "bucket" is still a missing key
    err(404, "No such object: my-bucket/bucket-list.csv", "notFound").code shouldBe
      StorageErrorCode.NoSuchKey
    err(404, "The specified bucket does not exist.", "notFound").code shouldBe
      StorageErrorCode.NoSuchBucket
    err(403, "denied", "forbidden").code shouldBe StorageErrorCode.AccessDenied
    err(429, "slow down", "rateLimitExceeded").code shouldBe "rateLimitExceeded"
    val e = err(404, "No such object: b/k", "notFound")
    e.isNotFound shouldBe true
    e.statusCode shouldBe Some(404)
    e.getMessage shouldContain "No such object"
    GcsObjectStore.errorOf(uri, response(500, "oops")).code shouldBe "HTTP500"
  }

  test("parse a listing page") {
    val page = GcsObjectStore.parseListing(
      ObjectUri("b", "logs/", "gs"),
      JSON.parse(
        """{"kind":"storage#objects","nextPageToken":"n1","prefixes":["logs/2026/"],
          |"items":[{"name":"logs/a.txt","size":"12","etag":"e1","updated":"2026-11-20T00:00:00Z","contentType":"text/plain"}]}"""
          .stripMargin
      )
    )
    page.nextToken shouldBe Some("n1")
    page.entries.map(_.uri.toString) shouldBe Seq("gs://b/logs/a.txt", "gs://b/logs/2026/")
    page.entries.head.size shouldBe 12L
    page.entries.head.contentType shouldBe Some("text/plain")
    page.entries(1).isDirectory shouldBe true
  }

  test("committed bytes of a resumable upload") {
    GcsObjectStore.committedBytes(
      Response(HttpStatus.ofCode(308)).setHeader("Range", "bytes=0-262143")
    ) shouldBe 262144L
    GcsObjectStore.committedBytes(Response(HttpStatus.ofCode(308))) shouldBe 0L
  }

  test("a plain-HTTP endpoint gets anonymous requests unless credentials are set") {
    GcsConfig().withEndpoint("http://localhost:4443").resolvedCredentials.name shouldBe "anonymous"
    GcsConfig()
      .withEndpoint("http://localhost:4443")
      .withAccessToken("t")
      .resolvedCredentials
      .name shouldBe "static"
  }

  test("chunk sizes are multiples of 256 KiB") {
    GcsConfig().withChunkSizeBytes(512 * 1024).chunkSizeBytes shouldBe 512 * 1024
    intercept[IllegalArgumentException] {
      GcsConfig().withChunkSizeBytes(300 * 1024)
    }
  }

  test("object names are one path segment in the JSON API") {
    val store = ObjectStore.gcs(GcsConfig().withEndpoint("http://127.0.0.1:1").noCredentials)
    try
      store.objectUrl(ObjectUri("b", "dir/a b+c.txt", "gs")) shouldBe
        "http://127.0.0.1:1/storage/v1/b/b/o/dir%2Fa%20b%2Bc.txt"
      // Signed URLs use the XML API, which keeps `/` in the path
      GcsSignedUrl.path(ObjectUri("b", "dir/a b+c.txt", "gs")) shouldBe "/b/dir/a%20b%2Bc.txt"
    finally
      store.close()
  }

end GoogleCredentialsTest

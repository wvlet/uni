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

import wvlet.uni.http.Http
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Request
import wvlet.uni.test.UniTest

/**
  * Runs against a GCS emulator when `UNI_GCS_TEST_ENDPOINT` is set (CI starts fake-gcs-server for
  * the JVM job), with `UNI_GCS_TEST_BUCKET` (an existing bucket). The emulator checks no
  * credentials; GoogleOAuthJvmTest covers the OAuth flows.
  */
class GcsIntegrationTest extends UniTest:
  private val endpoint = CloudEnv.get("UNI_GCS_TEST_ENDPOINT")

  // Anonymous requests, plus the conformance key for signing URLs
  private object TestCredentials extends GoogleCredentialsProvider:
    def name: String                           = "test"
    def accessToken: Option[GoogleAccessToken] = None
    private lazy val key                       = Rsa.parsePem(GcsSignedUrlTest.pem)
    override def signer: Option[GoogleSigner]  = Some(
      new GoogleSigner:
        def serviceAccountEmail: String          = GcsSignedUrlTest.email
        def sign(data: Array[Byte]): Array[Byte] = Rsa.signSha256(key, data)
    )

  private lazy val config = GcsConfig()
    .withEndpoint(endpoint.get)
    .withCredentials(TestCredentials)
    .withChunkSizeBytes(GcsConfig.ChunkUnit)

  private lazy val store  = ObjectStore.gcs(config)
  private lazy val bucket = CloudEnv.get("UNI_GCS_TEST_BUCKET").getOrElse("uni-test")
  private lazy val prefix = ObjectUri(bucket, s"it-${System.currentTimeMillis()}/", "gs")

  private def requireServer(): Unit =
    if endpoint.isEmpty then
      skip("Set UNI_GCS_TEST_ENDPOINT to run GCS integration tests")

  override protected def afterAll: Unit =
    if endpoint.isDefined then
      store.list(prefix).foreach(o => store.delete(o.uri))
      store.close()

  test("put, head, get, range, list, delete") {
    requireServer()
    val uri  = prefix.resolve("dir/hello world+&é.txt")
    val body = "hello uni storage".getBytes("UTF-8")
    store.put(uri, body, "text/plain")

    val info = store.head(uri)
    info.map(_.size) shouldBe Some(body.length.toLong)
    info.flatMap(_.contentType) shouldBe Some("text/plain")
    info.map(_.uri) shouldBe Some(uri)
    String(store.get(uri), "UTF-8") shouldBe "hello uni storage"
    String(store.getRange(uri, 6, 3), "UTF-8") shouldBe "uni"
    // A range past the end returns what is there
    String(store.getRange(uri, 10, 100), "UTF-8") shouldBe "storage"
    store.getRange(uri, 1000, 10).length shouldBe 0

    store.list(prefix).map(_.uri.key) shouldContain uri.key
    val top = store.list(prefix, recursive = false)
    top.exists(e => e.isDirectory && e.uri.key == prefix.resolve("dir/").key) shouldBe true

    store.delete(uri)
    store.head(uri) shouldBe None
    // Deleting again is fine
    store.delete(uri)
  }

  test("empty objects") {
    requireServer()
    val uri = prefix.resolve("empty")
    store.put(uri, Array.emptyByteArray)
    store.head(uri).map(_.size) shouldBe Some(0L)
    store.get(uri).length shouldBe 0
  }

  test("missing objects raise NoSuchKey") {
    requireServer()
    val e = intercept[StorageException] {
      store.get(prefix.resolve("missing"))
    }
    e.isNotFound shouldBe true
  }

  test("resumable upload through an output stream") {
    requireServer()
    val uri  = prefix.resolve("resumable.bin")
    val size = GcsConfig.ChunkUnit * 3 + 12345
    val data = Array.tabulate[Byte](size)(i => (i % 251).toByte)
    val out  = store.newOutputStream(uri)
    data.grouped(100 * 1024 + 7).foreach(chunk => out.write(chunk))
    out.close()
    store.head(uri).map(_.size) shouldBe Some(size.toLong)
    store.get(uri).toSeq shouldBe data.toSeq
  }

  test("a stream within one chunk sends one upload") {
    requireServer()
    val uri = prefix.resolve("small-stream.txt")
    val out = store.newOutputStream(uri, "text/plain")
    out.write("small".getBytes("UTF-8"))
    out.close()
    String(store.get(uri), "UTF-8") shouldBe "small"
  }

  test("an aborted upload leaves no object") {
    requireServer()
    val uri = prefix.resolve("aborted.bin")
    val out = store.newOutputStream(uri)
    out.write(Array.ofDim[Byte](GcsConfig.ChunkUnit + 10))
    out.abort()
    store.head(uri) shouldBe None
  }

  test("signed URLs address the object") {
    requireServer()
    val uri = prefix.resolve("signed.txt")
    store.put(uri, "via url".getBytes("UTF-8"))
    val url = store.presign(uri, HttpMethod.GET, 300)
    url shouldContain s"${endpoint.get}/${bucket}/"
    url shouldContain "X-Goog-Signature="
  }

  test("async API") {
    requireServer()
    val uri = prefix.resolve("async.txt")
    // Return the Rx so the test also runs on Scala.js, which has no Rx.await
    store
      .putAsync(uri, "async".getBytes("UTF-8"))
      .flatMap(_ => store.getAsync(uri))
      .flatMap { b =>
        String(b, "UTF-8") shouldBe "async"
        store.getRangeAsync(uri, 1, 3)
      }
      .flatMap { b =>
        String(b, "UTF-8") shouldBe "syn"
        store.headAsync(uri)
      }
      .flatMap { info =>
        info.map(_.size) shouldBe Some(5L)
        store.listAsync(prefix)
      }
      .flatMap { all =>
        all.map(_.uri.key) shouldContain uri.key
        store.deleteAsync(uri)
      }
      .flatMap(_ => store.headAsync(uri))
      .map(_ shouldBe None)
  }

  test("a missing bucket raises a 404") {
    requireServer()
    val e = intercept[StorageException] {
      store.list(ObjectUri(s"missing-${System.currentTimeMillis()}", "", "gs"))
    }
    e.statusCode shouldBe Some(404)
  }

  test("putIfAbsent creates an object only once") {
    requireServer()
    val uri = prefix.resolve("once/v1.metadata.json")
    store.putIfAbsent(uri, "first".getBytes("UTF-8")) shouldBe true
    store.putIfAbsent(uri, "second".getBytes("UTF-8")) shouldBe false
    String(store.get(uri), "UTF-8") shouldBe "first"
    val other = prefix.resolve("once/v2.metadata.json")
    store
      .putIfAbsentAsync(other, "a".getBytes("UTF-8"))
      .flatMap(created => store.putIfAbsentAsync(other, "b".getBytes("UTF-8")).map(created -> _))
      .map(_ shouldBe (true, false))
  }

end GcsIntegrationTest

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

import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Http
import wvlet.uni.http.Request
import wvlet.uni.test.UniTest

/**
  * Runs against a real S3-compatible server when `UNI_S3_TEST_ENDPOINT` is set (CI starts MinIO for
  * the JVM job), with `UNI_S3_TEST_ACCESS_KEY`, `UNI_S3_TEST_SECRET_KEY`, and `UNI_S3_TEST_BUCKET`
  * (an existing bucket).
  */
class S3IntegrationTest extends UniTest:
  private val endpoint = StorageEnv.get("UNI_S3_TEST_ENDPOINT")

  private lazy val config = S3Config
    .minio(endpoint.get)
    .withCredentials(
      StorageEnv.get("UNI_S3_TEST_ACCESS_KEY").getOrElse("minioadmin"),
      StorageEnv.get("UNI_S3_TEST_SECRET_KEY").getOrElse("minioadmin")
    )
    .withPartSizeBytes(S3Config.MinPartSize)

  private lazy val store  = ObjectStore.s3(config)
  private lazy val bucket = StorageEnv.get("UNI_S3_TEST_BUCKET").getOrElse("uni-test")
  private lazy val prefix = ObjectUri(bucket, s"it-${System.currentTimeMillis()}/")

  private def requireServer(): Unit =
    if endpoint.isEmpty then
      skip("Set UNI_S3_TEST_ENDPOINT to run S3 integration tests")

  override protected def afterAll: Unit =
    if endpoint.isDefined then
      store.list(prefix).foreach(o => store.delete(o.uri))
      store.close()

  test("put, head, get, range, list, delete") {
    requireServer()
    val uri  = prefix.resolve("dir/hello world+&.txt")
    val body = "hello uni storage".getBytes("UTF-8")
    store.put(uri, body, "text/plain")

    val info = store.head(uri)
    info.map(_.size) shouldBe Some(body.length.toLong)
    info.flatMap(_.contentType) shouldBe Some("text/plain")
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

  test("multipart upload through an output stream") {
    requireServer()
    val uri  = prefix.resolve("multipart.bin")
    val size = S3Config.MinPartSize * 2 + 12345
    val data = Array.tabulate[Byte](size)(i => (i % 251).toByte)
    val out  = store.newOutputStream(uri)
    data.grouped(1024 * 1024 + 7).foreach(chunk => out.write(chunk))
    out.close()
    store.head(uri).map(_.size) shouldBe Some(size.toLong)
    store.get(uri).toSeq shouldBe data.toSeq
  }

  test("an aborted upload leaves no object") {
    requireServer()
    val uri = prefix.resolve("aborted.bin")
    val out = store.newOutputStream(uri)
    out.write(Array.ofDim[Byte](S3Config.MinPartSize + 10))
    out.abort()
    store.head(uri) shouldBe None
  }

  test("presigned GET and PUT URLs") {
    requireServer()
    val uri    = prefix.resolve("presigned.txt")
    val client = Http.client.newSyncClient
    try
      client.send(
        Request
          .put(store.presign(uri, HttpMethod.PUT, 300))
          .withBytesContent("via url".getBytes("UTF-8"))
      )
      val got = client.send(Request.get(store.presign(uri, HttpMethod.GET, 300)))
      got.contentAsString shouldBe Some("via url")
    finally
      client.close()
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

  test("wrong credentials are refused") {
    requireServer()
    val bad = ObjectStore.s3(config.withCredentials("wrong", "wrong-secret"))
    try
      val e = intercept[StorageException] {
        bad.get(prefix.resolve("x"))
      }
      e.statusCode shouldBe Some(403)
    finally
      bad.close()
  }

end S3IntegrationTest

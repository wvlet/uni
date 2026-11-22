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
import wvlet.uni.storage.AzureObjectStore.StorageCall
import wvlet.uni.test.UniTest

/**
  * Runs against Azurite or a real storage account when `UNI_AZURE_TEST_CONNECTION_STRING` is set
  * (CI starts Azurite for the JVM job), using the container in `UNI_AZURE_TEST_CONTAINER` (created
  * when missing). For Azurite: `UseDevelopmentStorage=true`.
  */
class AzureIntegrationTest extends UniTest:
  private val connectionString = CloudEnv.get("UNI_AZURE_TEST_CONNECTION_STRING")

  private lazy val config = AzureConfig
    .fromConnectionString(connectionString.get)
    .withBlockSizeBytes(AzureConfig.MinBlockSize)

  private lazy val store     = ObjectStore.azure(config)
  private lazy val container = CloudEnv.get("UNI_AZURE_TEST_CONTAINER").getOrElse("uni-test")
  private lazy val prefix    = ObjectUri(container, s"it-${System.currentTimeMillis()}/", "az")

  private def requireServer(): Unit =
    if connectionString.isEmpty then
      skip("Set UNI_AZURE_TEST_CONNECTION_STRING to run Azure Blob integration tests")

  override protected def beforeAll: Unit =
    if connectionString.isDefined then
      store.createContainer(container)

  override protected def afterAll: Unit =
    if connectionString.isDefined then
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

    val all = store.list(prefix)
    all.map(_.uri.key) shouldContain uri.key
    all.find(_.uri.key == uri.key).map(_.size) shouldBe Some(body.length.toLong)
    val top = store.list(prefix, recursive = false)
    top.exists(e => e.isDirectory && e.uri.key == prefix.resolve("dir/").key) shouldBe true
    top.forall(_.uri.scheme == "az") shouldBe true

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

  test("missing blobs raise BlobNotFound") {
    requireServer()
    val e = intercept[StorageException] {
      store.get(prefix.resolve("missing"))
    }
    e.isNotFound shouldBe true
    e.code shouldBe "BlobNotFound"
  }

  test("listings page through NextMarker") {
    requireServer()
    val dir = prefix.resolve("many/")
    (1 to 7).foreach(i => store.put(dir.resolve(f"f${i}%02d"), Array(i.toByte)))
    // Ask for 3 per page so the store follows markers
    val call =
      (marker: Option[String]) =>
        StorageCall(
          HttpMethod.GET,
          dir.copy(key = ""),
          query =
            Seq(
              "restype"    -> "container",
              "comp"       -> "list",
              "prefix"     -> dir.key,
              "maxresults" -> "3"
            ) ++ marker.map("marker" -> _)
        )
    var marker = Option.empty[String]
    var pages  = 0
    var keys   = Seq.empty[String]
    while
      val page = AzureObjectStore.parseListing(
        dir,
        store.checked(call(marker), store.send(call(marker))).contentAsBytes.get
      )
      keys ++= page.entries.map(_.uri.key)
      marker = page.nextToken
      pages += 1
      marker.isDefined
    do
      (
    )
    pages shouldBe 3
    keys shouldBe (1 to 7).map(i => dir.resolve(f"f${i}%02d").key)
    store.list(dir).size shouldBe 7
  }

  test("block upload through an output stream") {
    requireServer()
    val uri  = prefix.resolve("blocks.bin")
    val size = AzureConfig.MinBlockSize * 3 + 12345
    val data = Array.tabulate[Byte](size)(i => (i % 251).toByte)
    val out  = store.newOutputStream(uri, "application/x-test")
    data.grouped(10000 + 7).foreach(chunk => out.write(chunk))
    out.close()
    val info = store.head(uri)
    info.map(_.size) shouldBe Some(size.toLong)
    info.flatMap(_.contentType) shouldBe Some("application/x-test")
    store.get(uri).toSeq shouldBe data.toSeq
  }

  test("an aborted upload leaves no blob, and an existing blob untouched") {
    requireServer()
    val uri = prefix.resolve("aborted.bin")
    val out = store.newOutputStream(uri)
    out.write(Array.ofDim[Byte](AzureConfig.MinBlockSize * 2 + 10))
    out.abort()
    store.head(uri) shouldBe None

    val kept = prefix.resolve("kept.txt")
    store.put(kept, "original".getBytes("UTF-8"))
    val again = store.newOutputStream(kept)
    again.write(Array.ofDim[Byte](AzureConfig.MinBlockSize * 2 + 10))
    again.abort()
    String(store.get(kept), "UTF-8") shouldBe "original"
    // A later upload of the same blob still succeeds
    val out2 = store.newOutputStream(kept)
    out2.write(Array.fill[Byte](AzureConfig.MinBlockSize + 1)(7))
    out2.close()
    store.head(kept).map(_.size) shouldBe Some(AzureConfig.MinBlockSize + 1L)
  }

  test("SAS URLs for GET and PUT") {
    requireServer()
    val uri    = prefix.resolve("presigned.txt")
    val client = Http.client.newSyncClient
    try
      client.send(
        Request
          .put(store.presign(uri, HttpMethod.PUT, 300))
          .setHeader("x-ms-blob-type", "BlockBlob")
          .setHeader("Content-Type", "text/plain")
          .withBytesContent("via url".getBytes("UTF-8"))
      )
      val got = client.send(Request.get(store.presign(uri, HttpMethod.GET, 300)))
      got.contentAsString shouldBe Some("via url")
    finally
      client.close()
  }

  test("a SAS token works as the store's credentials") {
    requireServer()
    val uri = prefix.resolve("sas-store.txt")
    store.put(uri, "sas".getBytes("UTF-8"))
    // A read-only token for one blob
    val url   = store.presign(uri, HttpMethod.GET, 300)
    val token = url.substring(url.indexOf('?') + 1)
    val sas   = ObjectStore.azure(config.withSasToken(token))
    try
      String(sas.get(uri), "UTF-8") shouldBe "sas"
      String(sas.getRange(uri, 1, 2), "UTF-8") shouldBe "as"
      sas.head(uri).map(_.size) shouldBe Some(3L)
      // The token doesn't allow writes
      intercept[StorageException] {
        sas.put(uri, "x".getBytes("UTF-8"))
      }.statusCode shouldBe Some(403)
    finally
      sas.close()
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

  test("a wrong key is refused") {
    requireServer()
    val bad = ObjectStore.azure(config.withSharedKey(store.account, "d3Jvbmcta2V5"))
    try
      val e = intercept[StorageException] {
        bad.get(prefix.resolve("x"))
      }
      e.statusCode shouldBe Some(403)
    finally
      bad.close()
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

end AzureIntegrationTest

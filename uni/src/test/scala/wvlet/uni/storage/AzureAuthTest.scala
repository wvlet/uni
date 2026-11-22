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

import wvlet.uni.http.HttpMethod
import wvlet.uni.test.UniTest
import wvlet.uni.util.Base64

/**
  * Shared Key and SAS signing, checked against the examples in Microsoft's "Authorize with Shared
  * Key" and SAS documentation
  */
class AzureAuthTest extends UniTest:
  // Azurite's well-known development key
  private val devKey =
    "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="

  test("canonicalized resource: Get Container Metadata example") {
    AzureAuth.canonicalResource(
      "myaccount",
      "/mycontainer",
      Seq("restype" -> "container", "comp" -> "metadata")
    ) shouldBe "/myaccount/mycontainer\ncomp:metadata\nrestype:container"
  }

  test("canonicalized resource: List Blobs example with repeated parameters") {
    AzureAuth.canonicalResource(
      "myaccount",
      "/mycontainer",
      Seq(
        "restype" -> "container",
        "comp"    -> "list",
        "include" -> "snapshots",
        "include" -> "metadata",
        "include" -> "uncommittedblobs"
      )
    ) shouldBe
      "/myaccount/mycontainer\ncomp:list\ninclude:metadata,snapshots,uncommittedblobs\nrestype:container"
  }

  test("canonicalized resource: a blob, and the emulator's repeated account") {
    AzureAuth.canonicalResource("myaccount", "/mycontainer/myblob", Nil) shouldBe
      "/myaccount/mycontainer/myblob"
    AzureAuth.canonicalResource(
      "devstoreaccount1",
      "/devstoreaccount1/mycontainer/myblob",
      Nil
    ) shouldBe "/devstoreaccount1/devstoreaccount1/mycontainer/myblob"
    // Parameter names are lowercased; values are used decoded
    AzureAuth.canonicalResource("a", "/c/b", Seq("BlockId" -> "AAA=")) shouldBe
      "/a/c/b\nblockid:AAA="
  }

  test("string-to-sign: the documented example") {
    AzureAuth.stringToSign(
      "GET",
      Seq("x-ms-date" -> "Fri, 26 Jun 2015 23:39:12 GMT", "x-ms-version" -> "2015-02-21"),
      0,
      "myaccount",
      "/mycontainer",
      Seq("restype" -> "container", "comp" -> "metadata", "timeout" -> "20")
    ) shouldBe
      "GET\n\n\n\n\n\n\n\n\n\n\n\nx-ms-date:Fri, 26 Jun 2015 23:39:12 GMT\nx-ms-version:2015-02-21\n/myaccount/mycontainer\ncomp:metadata\nrestype:container\ntimeout:20"
  }

  test("string-to-sign: content headers, a non-zero length, and header canonicalization") {
    val sts = AzureAuth.stringToSign(
      "PUT",
      Seq(
        "Content-Type"   -> "text/plain",
        "X-MS-Blob-Type" -> "BlockBlob",
        "x-ms-meta-note" -> "  two   spaces ",
        "x-ms-date"      -> "Fri, 26 Jun 2015 23:39:12 GMT",
        "x-ms-version"   -> "2015-02-21"
      ),
      11,
      "myaccount",
      "/mycontainer/hello%20world.txt",
      Nil
    )
    sts shouldBe
      "PUT\n\n\n11\n\ntext/plain\n\n\n\n\n\n\n" +
      "x-ms-blob-type:BlockBlob\nx-ms-date:Fri, 26 Jun 2015 23:39:12 GMT\nx-ms-meta-note:two spaces\nx-ms-version:2015-02-21\n" +
      "/myaccount/mycontainer/hello%20world.txt"
  }

  test("x-ms-date uses RFC 1123") {
    AwsTime.httpDate(1435361952000L) shouldBe "Fri, 26 Jun 2015 23:39:12 GMT"
    AwsTime.httpDate(0L) shouldBe "Thu, 01 Jan 1970 00:00:00 GMT"
    AwsTime.httpDate(AwsTime.parseIso("2024-02-29T09:05:01Z").get) shouldBe
      "Thu, 29 Feb 2024 09:05:01 GMT"
    AwsTime.isoSeconds(AwsTime.parseIso("2026-11-20T18:00:00.123Z").get) shouldBe
      "2026-11-20T18:00:00Z"
  }

  test("Shared Key signature is Base64 HMAC-SHA256 with the decoded key") {
    val sts = "GET\n\n\n\n\n\n\n\n\n\n\n\nx-ms-date:Fri, 26 Jun 2015 23:39:12 GMT\n"
    val sig = AzureAuth.sign(devKey, sts)
    Base64.decode(sig).length shouldBe 32
    sig shouldBe Base64.encode(Sha256.hmac(Base64.decode(devKey), sts))
    AzureAuth.authorization("devstoreaccount1", devKey, sts) shouldBe
      s"SharedKey devstoreaccount1:${sig}"
  }

  test("service SAS string-to-sign has the 2020-12-06 layout") {
    val sts = AzureAuth.serviceSasStringToSign(
      "r",
      "",
      "2026-11-21T00:00:00Z",
      AzureAuth.sasResource("myaccount", "music", "intro.mp3")
    )
    sts shouldBe
      "r\n\n2026-11-21T00:00:00Z\n/blob/myaccount/music/intro.mp3\n\n\n\n2024-11-04\nb\n\n\n\n\n\n\n"
    sts.split("\n", -1).length shouldBe 16
  }

  test("user delegation SAS string-to-sign has the 2020-12-06 layout") {
    val key = UserDelegationKey(
      "oid",
      "tid",
      "2026-11-20T00:00:00Z",
      "2026-11-27T00:00:00Z",
      "b",
      "2024-11-04",
      devKey
    )
    val sts = AzureAuth.userDelegationStringToSign(
      "cw",
      "",
      "2026-11-21T00:00:00Z",
      AzureAuth.sasResource("myaccount", "c", "dir/a b.txt"),
      key
    )
    val lines = sts.split("\n", -1)
    lines.length shouldBe 24
    lines.take(10).toSeq shouldBe
      Seq(
        "cw",
        "",
        "2026-11-21T00:00:00Z",
        "/blob/myaccount/c/dir/a b.txt",
        "oid",
        "tid",
        "2026-11-20T00:00:00Z",
        "2026-11-27T00:00:00Z",
        "b",
        "2024-11-04"
      )
    lines(15) shouldBe "2024-11-04"
    lines(16) shouldBe "b"
  }

  test("SAS permissions follow the racwd order") {
    AzureAuth.permissions(HttpMethod.GET) shouldBe "r"
    AzureAuth.permissions(HttpMethod.HEAD) shouldBe "r"
    AzureAuth.permissions(HttpMethod.PUT) shouldBe "cw"
    AzureAuth.permissions(HttpMethod.DELETE) shouldBe "d"
    intercept[IllegalArgumentException] {
      AzureAuth.permissions(HttpMethod.POST)
    }
  }

  test("block ids are Base64 of 36 bytes, the same length for every block") {
    val a = AzureOutputStream.blockId("0000abcd", 1)
    val b = AzureOutputStream.blockId("ffffffff", 50000)
    Base64.decode(a).length shouldBe 36
    a.length shouldBe b.length
    String(Base64.decode(a), "UTF-8") shouldBe "0000abcd-000000000000000000000000001"
  }

end AzureAuthTest

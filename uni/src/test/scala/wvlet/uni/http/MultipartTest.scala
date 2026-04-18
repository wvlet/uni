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
package wvlet.uni.http

import wvlet.uni.test.UniTest

class MultipartTest extends UniTest:

  private def decode(bytes: Array[Byte]): String = String(bytes, "UTF-8")

  test("build form-data with a single field") {
    val mp = Multipart.builder().withBoundary("BOUNDARY").addField("greeting", "hello").build()

    val expected =
      "--BOUNDARY\r\n" + "Content-Disposition: form-data; name=\"greeting\"\r\n" + "\r\n" +
        "hello\r\n" + "--BOUNDARY--\r\n"

    decode(mp.encode) shouldBe expected
  }

  test("build form-data with a single file part") {
    val fileBytes = Array[Byte](1, 2, 3, 4, 5)
    val mp        = Multipart
      .builder()
      .withBoundary("BOUNDARY")
      .addFile("upload", "data.bin", fileBytes, ContentType.ApplicationOctetStream)
      .build()

    val encoded = mp.encode
    val asText  = decode(encoded)

    asText shouldContain "--BOUNDARY\r\n"
    asText shouldContain
      "Content-Disposition: form-data; name=\"upload\"; filename=\"data.bin\"\r\n"
    asText shouldContain "Content-Type: application/octet-stream\r\n"
    asText shouldContain "--BOUNDARY--\r\n"

    // The body bytes must appear verbatim (not text-encoded) between the blank line and the trailing CRLF
    val marker = "\r\n\r\n".getBytes("UTF-8")
    val idx    = indexOfSubarray(encoded, marker)
    (idx >= 0) shouldBe true
    val bodyStart = idx + marker.length
    val bodyEnd   = bodyStart + fileBytes.length
    encoded.slice(bodyStart, bodyEnd).toSeq shouldBe fileBytes.toSeq
  }

  test("build form-data with mixed field and file parts") {
    val mp = Multipart
      .builder()
      .withBoundary("B")
      .addField("name", "alice")
      .addFile("avatar", "a.png", Array[Byte](9, 8, 7), ContentType.ImagePng)
      .addField("note", "hi")
      .build()

    val asText = decode(mp.encode)

    // Parts appear in insertion order
    val nameIdx   = asText.indexOf("name=\"name\"")
    val avatarIdx = asText.indexOf("name=\"avatar\"")
    val noteIdx   = asText.indexOf("name=\"note\"")
    (nameIdx >= 0) shouldBe true
    (avatarIdx > nameIdx) shouldBe true
    (noteIdx > avatarIdx) shouldBe true

    asText shouldContain "Content-Type: image/png\r\n"
  }

  test("contentType includes multipart/form-data with boundary") {
    val mp = Multipart.builder().withBoundary("xyz").addField("k", "v").build()
    mp.contentType.value shouldBe "multipart/form-data; boundary=xyz"
  }

  test("escape quotes in field name and filename") {
    val mp = Multipart
      .builder()
      .withBoundary("B")
      .addFile("a\"b", "c\".txt", "x".getBytes("UTF-8"))
      .build()

    val asText = decode(mp.encode)
    asText shouldContain "name=\"a\\\"b\""
    asText shouldContain "filename=\"c\\\".txt\""
  }

  test("reject CR or LF in field name") {
    val mp = Multipart.builder().withBoundary("B").addField("bad\r\nname", "v").build()

    intercept[IllegalArgumentException] {
      mp.encode
    }
  }

  test("reject CR or LF in filename") {
    val mp = Multipart
      .builder()
      .withBoundary("B")
      .addFile("f", "bad\nfile", "x".getBytes("UTF-8"))
      .build()

    intercept[IllegalArgumentException] {
      mp.encode
    }
  }

  test("generateBoundary produces unique and well-formed values") {
    val a = Multipart.generateBoundary()
    val b = Multipart.generateBoundary()
    (a != b) shouldBe true
    a.startsWith("----uni-") shouldBe true
    a.length shouldBe "----uni-".length + 24
  }

  test("auto-generated boundary is used when none provided") {
    val mp = Multipart.builder().addField("k", "v").build()
    mp.boundary.startsWith("----uni-") shouldBe true
    decode(mp.encode) shouldContain s"--${mp.boundary}--\r\n"
  }

  test("reject CR or LF in boundary") {
    val mp = Multipart.builder().withBoundary("bad\r\nboundary").addField("k", "v").build()
    intercept[IllegalArgumentException] {
      mp.encode
    }
  }

  test("HttpContent.multipart wraps a Multipart with correct contentType and bytes") {
    val mp = Multipart.builder().withBoundary("B").addField("k", "v").build()
    val hc = HttpContent.multipart(mp)

    hc.isEmpty shouldBe false
    hc.contentType shouldBe Some(mp.contentType)
    hc.toContentBytes.toSeq shouldBe mp.encode.toSeq
    hc.length shouldBe mp.encode.length.toLong
  }

  test("zero-part multipart keeps closing-boundary bytes on the wire") {
    val mp = Multipart.builder().withBoundary("B").build()
    val hc = HttpContent.multipart(mp)

    hc.isEmpty shouldBe false
    hc.toContentString shouldBe "--B--\r\n"
    hc.contentType shouldBe Some(mp.contentType)
  }

  test("Request.withMultipartContent sets content and content-type") {
    val mp  = Multipart.builder().withBoundary("BND").addField("k", "v").build()
    val req = Request.post("/upload").withMultipartContent(mp)

    req.content.isEmpty shouldBe false
    req.contentType shouldBe Some(mp.contentType)
    req.content.toContentBytes.toSeq shouldBe mp.encode.toSeq
  }

  test("Request.withMultipart(Seq) is a one-liner for common uploads") {
    val req = Request
      .post("/upload")
      .withMultipart(
        Seq(
          MultipartPart.field("name", "alice"),
          MultipartPart.file("avatar", "a.png", Array[Byte](1, 2, 3), ContentType.ImagePng)
        )
      )

    req.content.isEmpty shouldBe false
    req.contentType.map(_.fullType) shouldBe Some("multipart/form-data")
    // A boundary parameter is present in the Content-Type
    req.contentType.exists(_.value.contains("boundary=")) shouldBe true
  }

  test("wireHeaders sets Content-Type from content when header not set explicitly") {
    val mp  = Multipart.builder().withBoundary("BND").addField("k", "v").build()
    val req = Request.post("/upload").withMultipartContent(mp)

    req.wireHeaders.get(HttpHeader.ContentType) shouldBe Some("multipart/form-data; boundary=BND")
  }

  test("wireHeaders keeps explicit Content-Type header when user set one") {
    val mp  = Multipart.builder().withBoundary("BND").addField("k", "v").build()
    val req = Request
      .post("/upload")
      .withMultipartContent(mp)
      .setHeader(HttpHeader.ContentType, "application/override")

    req.wireHeaders.get(HttpHeader.ContentType) shouldBe Some("application/override")
  }

  test("reject CR or LF in custom part header name or value") {
    val withBadName = Multipart
      .builder()
      .withBoundary("B")
      .addPart(
        MultipartPart.FilePart(
          name = "f",
          filename = "x.bin",
          bytes = Array[Byte](1),
          headers = HttpMultiMap("X-Bad\r\nInjected" -> "1")
        )
      )
      .build()
    intercept[IllegalArgumentException] {
      withBadName.encode
    }

    val withBadValue = Multipart
      .builder()
      .withBoundary("B")
      .addPart(
        MultipartPart.FilePart(
          name = "f",
          filename = "x.bin",
          bytes = Array[Byte](1),
          headers = HttpMultiMap("X-Meta" -> "bad\r\nvalue")
        )
      )
      .build()
    intercept[IllegalArgumentException] {
      withBadValue.encode
    }
  }

  test("custom part headers are emitted and do not override disposition/content-type") {
    val mp = Multipart
      .builder()
      .withBoundary("B")
      .addPart(
        MultipartPart.FilePart(
          name = "f",
          filename = "x.bin",
          bytes = Array[Byte](1),
          contentType = ContentType.ApplicationOctetStream,
          headers = HttpMultiMap("X-Custom" -> "1", "Content-Type" -> "ignored/type")
        )
      )
      .build()

    val asText = decode(mp.encode)
    asText shouldContain "X-Custom: 1\r\n"
    asText shouldContain "Content-Type: application/octet-stream\r\n"
    asText.contains("Content-Type: ignored/type") shouldBe false
  }

  // Simple subarray search to avoid pulling in extra libs
  private def indexOfSubarray(haystack: Array[Byte], needle: Array[Byte]): Int =
    if needle.isEmpty then
      0
    else
      var i   = 0
      val end = haystack.length - needle.length
      while i <= end do
        var j = 0
        while j < needle.length && haystack(i + j) == needle(j) do
          j += 1
        if j == needle.length then
          return i
        i += 1
      -1

end MultipartTest

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
package wvlet.uni.cloud

import wvlet.uni.cloud.storage.*

import wvlet.uni.util.Sha256

import wvlet.uni.test.UniTest

/**
  * Examples from the AWS documentation: "Signature Calculations for the Authorization Header" and
  * "Authenticating Requests: Using Query Parameters" in the S3 API reference, and the SigV4 test
  * suite's get-vanilla case.
  */
class SigV4Test extends UniTest:
  private val s3Credentials = AwsCredentials(
    "AKIAIOSFODNN7EXAMPLE",
    "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
  )

  private val may24 = AwsTime.parseIso("2013-05-24T00:00:00Z").get
  private val host  = "examplebucket.s3.amazonaws.com"

  private def signature(headers: Seq[(String, String)]): String =
    val auth = headers.find(_._1 == "Authorization").get._2
    auth.substring(auth.indexOf("Signature=") + "Signature=".length)

  test("UTC timestamps") {
    AwsTime.amzDate(may24) shouldBe "20130524T000000Z"
    AwsTime.amzDate(0L) shouldBe "19700101T000000Z"
    AwsTime.parseIso("2026-02-28T23:59:59.123Z").map(AwsTime.amzDate) shouldBe
      Some("20260228T235959Z")
    AwsTime.parseIso("2024-02-29T09:00:00+09:00").map(AwsTime.amzDate) shouldBe
      Some("20240229T000000Z")
    AwsTime.parseIso("not a date") shouldBe None
  }

  test("GET object with a Range header") {
    val headers = SigV4.signHeaders(
      SigV4Request("GET", host, "/test.txt", headers = Seq("range" -> "bytes=0-9")),
      s3Credentials,
      "us-east-1",
      epochMillis = may24
    )
    signature(headers) shouldBe "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
    headers.find(_._1 == "Authorization").get._2 shouldContain
      "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"
  }

  test("PUT object with a payload and an encoded key") {
    val headers = SigV4.signHeaders(
      SigV4Request(
        "PUT",
        host,
        SigV4.encodePath("test$file.text").prependedAll("/"),
        headers = Seq(
          "Date"                -> "Fri, 24 May 2013 00:00:00 GMT",
          "x-amz-storage-class" -> "REDUCED_REDUNDANCY"
        ),
        payloadHash = Sha256.hex("Welcome to Amazon S3.")
      ),
      s3Credentials,
      "us-east-1",
      epochMillis = may24
    )
    signature(headers) shouldBe "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"
  }

  test("GET with query parameters") {
    val lifecycle = SigV4.signHeaders(
      SigV4Request("GET", host, "/", query = Seq("lifecycle" -> "")),
      s3Credentials,
      "us-east-1",
      epochMillis = may24
    )
    signature(lifecycle) shouldBe "fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543"

    val listing = SigV4.signHeaders(
      SigV4Request("GET", host, "/", query = Seq("prefix" -> "J", "max-keys" -> "2")),
      s3Credentials,
      "us-east-1",
      epochMillis = may24
    )
    signature(listing) shouldBe "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"
  }

  test("presigned GET URL") {
    val q = SigV4.presignQuery(
      SigV4Request("GET", host, "/test.txt"),
      s3Credentials,
      "us-east-1",
      expiresSeconds = 86400,
      epochMillis = may24
    )
    q shouldContain
      "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
    q shouldContain "X-Amz-SignedHeaders=host"
    q.endsWith(
      "X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
    ) shouldBe true
  }

  test("presigned URLs expire within 7 days") {
    intercept[IllegalArgumentException] {
      SigV4.presignQuery(
        SigV4Request("GET", host, "/a"),
        s3Credentials,
        "us-east-1",
        SigV4.MaxPresignSeconds + 1
      )
    }
  }

  test("SigV4 test suite: get-vanilla") {
    val headers = SigV4.signHeaders(
      SigV4Request("GET", "example.amazonaws.com", "/"),
      AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
      "us-east-1",
      service = "service",
      epochMillis = AwsTime.parseIso("2015-08-30T12:36:00Z").get,
      includeContentSha256 = false
    )
    signature(headers) shouldBe "5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
  }

  test("temporary credentials add and sign x-amz-security-token") {
    val headers = SigV4.signHeaders(
      SigV4Request("GET", host, "/test.txt"),
      s3Credentials.withSessionToken("token123"),
      "us-east-1",
      epochMillis = may24
    )
    headers.find(_._1 == "x-amz-security-token").map(_._2) shouldBe Some("token123")
    headers.find(_._1 == "Authorization").get._2 shouldContain "x-amz-security-token"
  }

  test("credentials never print their secret") {
    val s = s3Credentials.withSessionToken("secret-token").toString
    s shouldContain "AKIAIOSFODNN7EXAMPLE"
    s.contains("wJalrXUtnFEMI") shouldBe false
    s.contains("secret-token") shouldBe false
  }

end SigV4Test

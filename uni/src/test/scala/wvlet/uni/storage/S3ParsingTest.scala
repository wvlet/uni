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
import wvlet.uni.http.HttpStatus
import wvlet.uni.http.Response
import wvlet.uni.test.UniTest

class S3ParsingTest extends UniTest:
  test("parse object URIs") {
    ObjectUri.parse("s3://bucket/path/to/key.parquet") shouldBe
      ObjectUri("bucket", "path/to/key.parquet")
    ObjectUri.parse("gs://bucket") shouldBe ObjectUri("bucket", "", "gs")
    ObjectUri.parse("s3://bucket/dir/").resolve("a.txt").toString shouldBe "s3://bucket/dir/a.txt"
    intercept[IllegalArgumentException] {
      ObjectUri.parse("/local/path")
    }
    intercept[IllegalArgumentException] {
      ObjectUri.parse("s3:///key")
    }
  }

  test("read a ListObjectsV2 page") {
    val xml =
      """<?xml version="1.0" encoding="UTF-8"?>
        |<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
        |  <Name>bucket</Name><Prefix>data/</Prefix><KeyCount>2</KeyCount>
        |  <IsTruncated>true</IsTruncated>
        |  <NextContinuationToken>1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=</NextContinuationToken>
        |  <Contents>
        |    <Key>data/a &amp; b.parquet</Key><LastModified>2026-11-20T00:00:00.000Z</LastModified>
        |    <ETag>&quot;abc&quot;</ETag><Size>1024</Size><StorageClass>STANDARD</StorageClass>
        |  </Contents>
        |  <Contents><Key>data/ x </Key><Size>0</Size></Contents>
        |  <CommonPrefixes><Prefix>data/sub/</Prefix></CommonPrefixes>
        |</ListBucketResult>""".stripMargin
    val page = S3ObjectStore.parseListing(ObjectUri("bucket", "data/"), xml.getBytes("UTF-8"))
    page.nextToken shouldBe Some("1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=")
    page.entries.map(_.uri.key) shouldBe Seq("data/a & b.parquet", "data/ x ", "data/sub/")
    page.entries.head.size shouldBe 1024L
    page.entries.head.etag shouldBe Some("\"abc\"")
    page.entries.last.isDirectory shouldBe true
  }

  test("map S3 errors to StorageException") {
    val uri  = ObjectUri("bucket", "missing")
    val resp = Response(HttpStatus.ofCode(404)).withBytesContent(
      "<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>"
        .getBytes("UTF-8")
    )
    val e = S3ObjectStore.errorOf(uri, resp)
    e.code shouldBe "NoSuchKey"
    e.isNotFound shouldBe true
    e.statusCode shouldBe Some(404)

    val moved = Response(HttpStatus.ofCode(301)).addHeader("x-amz-bucket-region", "eu-west-1")
    val w     = S3ObjectStore.errorOf(uri, moved)
    w.code shouldBe StorageErrorCode.WrongRegion
    w.getMessage shouldContain "eu-west-1"

    // A 400 from the bucket's own region keeps the S3 error code
    val bad = Response(HttpStatus.ofCode(400))
      .addHeader("x-amz-bucket-region", "us-east-1")
      .withBytesContent("<Error><Code>EntityTooSmall</Code></Error>".getBytes("UTF-8"))
    S3ObjectStore.errorOf(uri, bad, "us-east-1").code shouldBe "EntityTooSmall"
  }

  test("XML reader handles CDATA, comments, and numeric entities") {
    val x = XmlReader.parse(
      "<?xml version='1.0'?><!-- c --><a x='1>2'><b><![CDATA[<raw>]]></b><c>&#65;&#x42;</c><d/></a>"
    )
    x.name shouldBe "a"
    x.childText("b") shouldBe Some("<raw>")
    x.childText("c") shouldBe Some("AB")
    x.child("d").map(_.children) shouldBe Some(Nil)
    intercept[CloudException] {
      XmlReader.parse("<a><b></a")
    }
  }

  test("read AWS profile files") {
    val ini = AwsProfiles.parseIni("""# comment
        |[default]
        |aws_access_key_id = AKIA1
        |aws_secret_access_key=secret1
        |
        |[profile   dev]
        |region = ap-northeast-1
        |""".stripMargin)
    ini("default")("aws_access_key_id") shouldBe "AKIA1"
    ini("default")("aws_secret_access_key") shouldBe "secret1"
    ini("profile dev")("region") shouldBe "ap-northeast-1"
  }

  test("parse container and IMDS credential JSON") {
    val c = AwsCredentialsProvider.parseCredentialsJson(
      """{"AccessKeyId":"ASIA1","SecretAccessKey":"s","Token":"t","Expiration":"2026-11-20T18:00:00Z"}"""
    )
    c.map(_.accessKeyId) shouldBe Some("ASIA1")
    c.flatMap(_.sessionToken) shouldBe Some("t")
    c.flatMap(_.expirationMillis).map(AwsTime.amzDate) shouldBe Some("20261120T180000Z")
    AwsCredentialsProvider.parseCredentialsJson("""{"Code":"Failed"}""") shouldBe None
  }

  test("address buckets virtual-hosted or path-style") {
    val aws = S3ObjectStore(S3Config().withRegion("us-west-2").noCredentials)
    aws.location(ObjectUri("my-bucket", "a b/c.txt")) shouldBe
      ("my-bucket.s3.us-west-2.amazonaws.com", "/a%20b/c.txt")
    // Dotted bucket names would break TLS wildcard certificates
    aws.location(ObjectUri("my.bucket", "k")) shouldBe
      ("s3.us-west-2.amazonaws.com", "/my.bucket/k")

    val minio = S3ObjectStore(S3Config.minio("http://localhost:9000").noCredentials)
    minio.location(ObjectUri("b", "")) shouldBe ("localhost:9000", "/b")
    minio.location(ObjectUri("b", "x/y")) shouldBe ("localhost:9000", "/b/x/y")
  }

  test("presigned URLs need credentials") {
    val anon = S3ObjectStore(S3Config().withRegion("us-east-1").noCredentials)
    intercept[StorageException] {
      anon.presign(ObjectUri("b", "k"))
    }
    val signed = S3ObjectStore(
      S3Config().withRegion("us-east-1").withCredentials("AKIAIOSFODNN7EXAMPLE", "secret")
    )
    val url = signed.presign(ObjectUri("b", "k"), HttpMethod.PUT, 60)
    url.startsWith("https://b.s3.us-east-1.amazonaws.com/k?") shouldBe true
    url shouldContain "X-Amz-Expires=60"
  }

  test("part size must be at least 5 MiB") {
    intercept[IllegalArgumentException] {
      S3Config().withPartSizeBytes(1024)
    }
  }

end S3ParsingTest

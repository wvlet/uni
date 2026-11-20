---
description: Read and write objects in AWS S3, S3-compatible stores (Cloudflare R2, MinIO), and Google Cloud Storage from Scala on the JVM, Scala.js, and Scala Native, with no AWS or Google SDK. Covers credentials (including Google Application Default Credentials), ranged reads, listing, multipart and resumable uploads, presigned and signed URLs, and Parquet files in S3 and GCS.
---

# Object Storage

`wvlet.uni.storage` reads and writes objects in [AWS S3](https://aws.amazon.com/s3/), in
services that speak the S3 API ([Cloudflare R2](https://developers.cloudflare.com/r2/) and
self-hosted servers such as [MinIO](https://min.io/) or the
[Versity S3 Gateway](https://github.com/versity/versitygw)), and in
[Google Cloud Storage](#google-cloud-storage) with Google credentials. It is part of the `uni`
artifact and signs requests in pure Scala (AWS Signature Version 4, Google OAuth 2.0 and RSA), so
the same code runs on the JVM, Scala Native, and Scala.js, with no AWS or Google SDK.

```scala
import wvlet.uni.storage.ObjectStore
import wvlet.uni.storage.ObjectUri

val store = ObjectStore.s3()

store.put("s3://my-bucket/hello.txt", "Hello".getBytes("UTF-8"))
val bytes: Array[Byte] = store.get("s3://my-bucket/hello.txt")
store.head("s3://my-bucket/hello.txt").map(_.size) // Some(5)
store.list("s3://my-bucket/logs/").foreach(o => println(o.uri))
store.delete("s3://my-bucket/hello.txt")
store.close()
```

Every method takes an `s3://bucket/key` (or `gs://bucket/key`) string or an `ObjectUri`. S3 and
GCS stores share the `ObjectStore` interface, so code written against it works with either. `head` returns `None` for a
missing object; the other methods raise `StorageException`, whose `code` is the store's error
code (`NoSuchKey`, `AccessDenied`, `SlowDown`, …) and `statusCode` the HTTP status.

## Credentials and Region

`ObjectStore.s3()` finds credentials the way the AWS CLI does, and uses the first source that has
them:

1. `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`
2. The `AWS_PROFILE` (or `default`) profile in `~/.aws/credentials` or `~/.aws/config`
3. The ECS or EKS Pod Identity container endpoint (`AWS_CONTAINER_CREDENTIALS_*`)
4. The EC2 instance profile, through IMDSv2

Temporary credentials are refreshed before they expire. The region comes from `AWS_REGION`,
`AWS_DEFAULT_REGION`, or the profile's `region`, and defaults to `us-east-1`. Set either
explicitly with `S3Config`:

```scala
import wvlet.uni.storage.S3Config
import wvlet.uni.storage.AwsCredentialsProvider

val tokyo = ObjectStore.s3(
  S3Config()
    .withRegion("ap-northeast-1")
    .withCredentials(AwsCredentialsProvider.profile("analytics"))
)

// A public bucket: send unsigned requests
val open = ObjectStore.s3(S3Config().withRegion("us-west-2").noCredentials)
```

`AwsCredentials.toString` hides the secret and the session token, so credentials don't leak into
logs. SSO, web identity (EKS IRSA), and `credential_process` profiles are not read yet; pass keys
from those through `withCredentials(AwsCredentials(...))`.

A bucket in another region fails with the `WrongRegion` error code and a message naming the
bucket's region. The client never follows redirects, because a redirect would send the signed
headers to another host.

## S3-Compatible Stores

`S3Config` has presets for the common services. They use path-style addressing
(`endpoint/bucket/key`) and the region the service expects:

```scala
// Cloudflare R2: keys from an R2 API token
val r2 = ObjectStore.s3(S3Config.r2("<account-id>").withCredentials("<key-id>", "<secret>"))

// Google Cloud Storage through its S3 interoperability API, with HMAC keys
val gcsHmac = ObjectStore.s3(S3Config.gcs.withCredentials("<hmac-key-id>", "<hmac-secret>"))

// MinIO or another self-hosted server
val local = ObjectStore.s3(S3Config.minio("http://localhost:9000").withCredentials("admin", "secret"))
```

For any other service, set `withEndpoint(url)`, `withRegion(region)`, and `withPathStyle(true)`
if it does not support `bucket.endpoint` host names.

## Google Cloud Storage

`ObjectStore.gcs()` talks to Google Cloud Storage through its JSON API with OAuth 2.0 access
tokens, so it works with the credentials a Google Cloud project already has: no HMAC keys needed.

```scala
import wvlet.uni.storage.GcsConfig
import wvlet.uni.storage.GoogleCredentialsProvider

val gcs = ObjectStore.gcs()

gcs.put("gs://my-bucket/hello.txt", "Hello".getBytes("UTF-8"))
val hello: Array[Byte] = gcs.get("gs://my-bucket/hello.txt")
gcs.list("gs://my-bucket/logs/").foreach(o => println(o.uri))
```

It finds credentials in [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials)
order and uses the first source that exists:

1. The JSON file named by `GOOGLE_APPLICATION_CREDENTIALS`, usually a service-account key
2. The file `gcloud auth application-default login` writes
   (`~/.config/gcloud/application_default_credentials.json`, `%APPDATA%\gcloud\...` on Windows, or
   under `CLOUDSDK_CONFIG`)
3. The metadata server on Compute Engine, GKE, Cloud Run, and Cloud Functions (`GCE_METADATA_HOST`
   overrides its address)

Access tokens are cached and refreshed 5 minutes before they expire. A service account signs its
token request (a JWT) with RSA in pure Scala, so service-account keys work on Scala Native too.
A source that exists but can't be used, such as a malformed key or a credentials type Uni doesn't
read (`external_account` for workload identity federation, `impersonated_service_account`), fails
with the `InvalidCredentials` code instead of silently trying the next source. Tokens, refresh
tokens, client secrets, and private keys never appear in `toString` output or error messages.

Pick credentials explicitly with `GcsConfig`:

```scala
val sa = ObjectStore.gcs(
  GcsConfig().withCredentials(
    GoogleCredentialsProvider.fromFile(
      "/secrets/key.json",
      scopes = Seq(GoogleCredentialsProvider.StorageReadOnly)
    )
  )
)

// A short-lived access token handed out by your server, for example in a browser
val withToken = ObjectStore.gcs(GcsConfig().withAccessToken("<access-token>"))

// A public bucket
val public = ObjectStore.gcs(GcsConfig().noCredentials)
```

The default scope is `devstorage.read_write`. Large uploads through `newOutputStream` use
[resumable uploads](https://cloud.google.com/storage/docs/resumable-uploads) in 8 MiB chunks
(`withChunkSizeBytes`, a multiple of 256 KiB); `abort()` cancels the session. Errors use the same
codes as S3 (`NoSuchKey`, `NoSuchBucket`, `AccessDenied`), or Google's `reason` such as
`rateLimitExceeded`.

For local tests, point `STORAGE_EMULATOR_HOST` at an emulator such as
[fake-gcs-server](https://github.com/fsouza/fake-gcs-server); `ObjectStore.gcs()` then sends
anonymous requests to it, as Google's own client libraries do.

## Ranges and Listings

`getRange(uri, offset, length)` reads part of an object with an HTTP Range request. A range that
runs past the end returns the bytes that exist. Large reads are split into requests below the HTTP
client's `maxResponseBytes`, so they work on Node.js too, where one response must fit in a fixed
buffer.

```scala
val header: Array[Byte] = store.getRange("s3://my-bucket/data.bin", 0, 16)

// Every key under the prefix, following continuation tokens
val all = store.list("s3://my-bucket/logs/")

// One level: keys and "directories" (common prefixes) directly under the prefix
val top = store.list(ObjectUri.parse("s3://my-bucket/logs/"), recursive = false)
top.filter(_.isDirectory).foreach(d => println(d.uri))
```

## Uploading Large Objects

`newOutputStream` uploads what you write to it. It buffers 8 MiB parts (set
`withPartSizeBytes`, at least 5 MiB) and sends them with S3 multipart upload; the object appears
when the stream closes. A stream that never fills a part sends one PUT instead. If something goes
wrong, call `abort()` instead of `close()` and the partial upload is discarded:

```scala
val out = store.newOutputStream("s3://my-bucket/big.bin")
try
  (0 until 100).foreach(i => out.write(Array.fill[Byte](1024 * 1024)(i.toByte)))
  out.close()
catch
  case e: Exception =>
    out.abort()
    throw e
```

## Presigned URLs

A presigned URL lets anyone holding it send one kind of request to an object until it expires
(at most 7 days). Share a download, or let a browser upload without holding keys:

```scala
import wvlet.uni.http.HttpMethod

val download: String = store.presign(ObjectUri.parse("s3://my-bucket/report.pdf"))
val upload: String = store.presign(
  ObjectUri.parse("s3://my-bucket/uploads/photo.jpg"),
  HttpMethod.PUT,
  expiresSeconds = 600
)
```

On GCS, `presign` makes a [V4 signed URL](https://cloud.google.com/storage/docs/access-control/signed-urls).
A service-account key signs it locally. On Google Cloud with the metadata server's account, it is
signed through the IAM Credentials `signBlob` API, which needs the Service Account Token Creator
role on that account and a token with the `cloud-platform` scope. User credentials from `gcloud`
can't sign URLs.

```scala
val shared: String = gcs.presign("gs://my-bucket/report.pdf")
```

## Async API and Browsers

Each method has an `...Async` variant that returns an [Rx](../rx/) stream and works on every
platform, including browsers:

```scala
import wvlet.uni.rx.Rx

val text: Rx[String] = store
  .getAsync(ObjectUri.parse("s3://my-bucket/hello.txt"))
  .map(b => String(b, "UTF-8"))
```

Browsers have no environment variables, profile files, or instance metadata, so pass credentials
explicitly, ideally short-lived ones from AWS STS or a Google access token from your server, or
hand the page presigned URLs. Never ship long-lived access keys or service-account keys in a web
page. The bucket also needs a CORS rule that allows the
page's origin.

## Parquet Files in S3 and GCS

[uni-parquet](../text/parquet) reads Parquet files straight from S3 or GCS with ranged reads,
fetching only the footer and the column chunks it needs:

```scala
import wvlet.uni.parquet.Parquet
import wvlet.uni.weaver.Weaver

case class Event(id: Long, name: String) derives Weaver

val events: Seq[Event] = Parquet.openS3("s3://my-bucket/events.parquet").readAs[Event]().toSeq

// Write: the upload completes when the writer closes
val writer = Parquet.newWriter[Event](store.newOutputStream("s3://my-bucket/out.parquet"))
writer.writeAll(events)
writer.close()
```

`Parquet.openS3(uri, config)` takes an `S3Config` for R2 or MinIO, `Parquet.openGcs("gs://...")`
reads from Google Cloud Storage, and `openS3Async` / `openGcsAsync` read in browsers. A writer that fails, or whose `abort()` you call, aborts the upload, so no partial file
appears.

## When to Use Another Library

The [AWS SDK for Java](https://aws.amazon.com/sdk-for-java/) covers every S3 feature (server-side
encryption settings, object lock, transfer acceleration, and parallel transfers) and every
credential source, and is a good choice for JVM applications that use S3 heavily or other AWS
services too. The [Google Cloud Storage client library for Java](https://cloud.google.com/java/docs/reference/google-cloud-storage/latest/overview)
does the same for GCS, with every credential type (including workload identity federation) and
features such as parallel composite uploads. Choose `wvlet.uni.storage` for the common object
operations on Scala.js or Scala Native, in browsers, or with no dependencies beyond Uni.

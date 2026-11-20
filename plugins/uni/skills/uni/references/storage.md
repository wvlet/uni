# Object storage (S3, R2, MinIO, Google Cloud Storage, Azure Blob)

`wvlet.uni.storage` (in the `uni` artifact) reads and writes objects in AWS S3 and
S3-compatible stores (AWS Signature V4), Google Cloud Storage (OAuth 2.0), and Azure
Blob Storage (Shared Key, SAS, Entra ID), in pure Scala: JVM, Scala.js, and Scala
Native, no AWS, Google, or Azure SDK. All stores
implement `ObjectStore`, so code written against it works with any of them. Sync methods run on the JVM, Native, and Node.js; `...Async`
methods (returning `Rx`) run everywhere, including browsers.

Full reference: https://wvlet.org/uni/core/object-storage.md

## Basic operations

```scala
import wvlet.uni.storage.{ObjectStore, ObjectUri, S3Config, StorageException}

val store = ObjectStore.s3() // AWS credential chain: env, ~/.aws profile, ECS, EC2 IMDSv2

store.put("s3://my-bucket/a.txt", "hi".getBytes("UTF-8"))
val all: Array[Byte]   = store.get("s3://my-bucket/a.txt")
val part: Array[Byte]  = store.getRange("s3://my-bucket/a.txt", 0, 1)
val size: Option[Long] = store.head("s3://my-bucket/a.txt").map(_.size) // None if missing
val keys               = store.list("s3://my-bucket/logs/").map(_.uri.key)
val oneLevel           = store.list(ObjectUri.parse("s3://my-bucket/logs/"), recursive = false)
store.delete("s3://my-bucket/a.txt")

try store.get("s3://my-bucket/missing")
catch case e: StorageException if e.isNotFound => Array.emptyByteArray

store.close()
```

## Configuration

```scala
import wvlet.uni.storage.AwsCredentialsProvider

val aws   = S3Config().withRegion("ap-northeast-1").withCredentials(AwsCredentialsProvider.profile("dev"))
val r2    = S3Config.r2("<account-id>").withCredentials("<key-id>", "<secret>")
val gcs   = S3Config.gcs.withCredentials("<hmac-key-id>", "<hmac-secret>") // GCS HMAC keys
val minio = S3Config.minio("http://localhost:9000").withCredentials("admin", "secret")
val anon  = S3Config().withRegion("us-west-2").noCredentials // public buckets
```

Redirects are never followed (signed headers must not go to another host); a
bucket in another region fails with code `WrongRegion` naming the region.

## Uploads and presigned URLs

```scala
import wvlet.uni.http.HttpMethod

val out = store.newOutputStream("s3://my-bucket/big.bin") // 8 MiB multipart parts
try
  out.write(Array.fill[Byte](1024)(1))
  out.close() // completes the upload
catch
  case e: Exception =>
    out.abort() // discards the partial upload
    throw e

val url: String = store.presign(ObjectUri.parse("s3://my-bucket/a.txt"), HttpMethod.GET, expiresSeconds = 600)
```

In browsers, pass explicit (ideally temporary STS) credentials or use presigned
URLs; never ship long-lived keys. The bucket needs CORS.

## Google Cloud Storage

```scala
import wvlet.uni.storage.{GcsConfig, GoogleCredentialsProvider}

// Application Default Credentials: GOOGLE_APPLICATION_CREDENTIALS (service-account
// key), then the `gcloud auth application-default login` file, then the GCE/GKE
// metadata server. Tokens are cached and refreshed before they expire.
val gs = ObjectStore.gcs()
gs.put("gs://my-bucket/a.txt", "hi".getBytes("UTF-8"))
val text = String(gs.get("gs://my-bucket/a.txt"), "UTF-8")

val readOnly = ObjectStore.gcs(
  GcsConfig().withCredentials(
    GoogleCredentialsProvider.fromFile(
      "/secrets/key.json",
      scopes = Seq(GoogleCredentialsProvider.StorageReadOnly)
    )
  )
)
val withToken = ObjectStore.gcs(GcsConfig().withAccessToken("<access-token>")) // browsers
val public    = ObjectStore.gcs(GcsConfig().noCredentials)

// V4 signed URL: needs a service-account key, or the metadata server's account
// (signed through IAM signBlob); gcloud user credentials can't sign
val signed: String = gs.presign("gs://my-bucket/a.txt")
```

`newOutputStream` uses resumable uploads (8 MiB chunks, `withChunkSizeBytes` in
multiples of 256 KiB). An unusable credentials file (malformed, or a type such as
`external_account`) fails with code `InvalidCredentials` rather than falling
through. `STORAGE_EMULATOR_HOST` points `ObjectStore.gcs()` at an emulator such as
fake-gcs-server with anonymous requests. `S3Config.gcs` remains for HMAC keys.

## Azure Blob Storage

Same `ObjectStore` API with `az://container/blob` URIs (`azure://` also works).
The storage account comes from `AzureConfig`, not the URI.

```scala
import wvlet.uni.storage.{AzureConfig, AzureCredentialsProvider}

// Default: AZURE_STORAGE_CONNECTION_STRING, or AZURE_STORAGE_ACCOUNT plus
// AZURE_STORAGE_KEY / AZURE_STORAGE_SAS_TOKEN / service principal env vars /
// workload identity / managed identity
val azure = ObjectStore.azure()

val withKey  = AzureConfig().withSharedKey("myaccount", "<account-key>")
val withSas  = AzureConfig().withAccount("myaccount").withSasToken("<sas-token>")
val withApp  = AzureConfig()
  .withAccount("myaccount")
  .withCredentials(AzureCredentialsProvider.clientSecret("<tenant>", "<client-id>", "<secret>"))
val portal   = AzureConfig.fromConnectionString("<connection-string>")
val emulator = AzureConfig.azurite() // Azurite on 127.0.0.1:10000

val blobs = ObjectStore.azure(withKey)
blobs.put("az://my-container/a.txt", "hi".getBytes("UTF-8"))
val sasUrl: String = blobs.presign("az://my-container/a.txt") // service SAS (user delegation SAS with Entra ID)

try blobs.get("az://my-container/missing")
catch case e: StorageException if e.isNotFound => Array.emptyByteArray // code is "BlobNotFound"
```

`newOutputStream` uploads 8 MiB blocks (Put Block, then Put Block List on
`close()`); `abort()` skips the commit, so no blob appears. Errors keep Azure's
codes (`BlobNotFound`, `AuthenticationFailed`); use `isNotFound` to check for a
missing object on any store. A store holding only a SAS token can't `presign`.
In browsers, use SAS tokens and a CORS rule; never ship account keys.

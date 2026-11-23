# Object storage (S3, R2, MinIO, Google Cloud Storage, Azure Blob)

`wvlet.uni.cloud.storage` (in the `uni` artifact) reads and writes objects in AWS S3 and
S3-compatible stores (AWS Signature V4), Google Cloud Storage (OAuth 2.0), and Azure
Blob Storage (Shared Key, SAS, Entra ID), in pure Scala: JVM, Scala.js, and Scala
Native, no AWS, Google, or Azure SDK. All stores
implement `ObjectStore`, so code written against it works with any of them. Sync methods run on the JVM, Native, and Node.js; `...Async`
methods (returning `Rx`) run everywhere, including browsers.

Full reference: https://wvlet.org/uni/core/object-storage.md

## Basic operations

```scala
import wvlet.uni.cloud.storage.{ObjectStore, ObjectUri, S3Config, StorageException}

val store = ObjectStore.s3() // AWS credential chain: env, web identity, ~/.aws profile, ECS, EC2 IMDSv2

store.put("s3://my-bucket/a.txt", "hi".getBytes("UTF-8"))
val all: Array[Byte]   = store.get("s3://my-bucket/a.txt")
val part: Array[Byte]  = store.getRange("s3://my-bucket/a.txt", 0, 1)
val size: Option[Long] = store.head("s3://my-bucket/a.txt").map(_.size) // None if missing
val keys               = store.list("s3://my-bucket/logs/").map(_.uri.key)
val oneLevel           = store.list(ObjectUri.parse("s3://my-bucket/logs/"), recursive = false)
store.delete("s3://my-bucket/a.txt")

// Create only if absent (S3 If-None-Match, GCS ifGenerationMatch=0, Azure If-None-Match):
// Some(version) when this call created it, None if an object exists; at most one racer wins
val created: Option[String] = store.putIfAbsent(ObjectUri.parse("s3://my-bucket/v1.json"), "{}".getBytes("UTF-8"))

// Compare-and-swap (S3/Azure If-Match on the ETag, GCS ifGenerationMatch): head BEFORE get,
// then replace only if unchanged. Some(newVersion) chains the next swap; None = lost race
// (changed or missing). Other failures throw StorageException.
val pointer = ObjectUri.parse("s3://my-bucket/current.json")
store.put(pointer, "v1".getBytes("UTF-8"))
val swapped: Option[String] = store.head(pointer).flatMap(_.version).flatMap { v =>
  store.putIfMatch(pointer, "v2".getBytes("UTF-8"), v)
}

try store.get("s3://my-bucket/missing")
catch case e: StorageException if e.isNotFound => Array.emptyByteArray

store.close()
```

## Configuration

```scala
import wvlet.uni.cloud.AwsCredentialsProvider

val aws   = S3Config().withRegion("ap-northeast-1").withCredentials(AwsCredentialsProvider.profile("dev"))
val r2    = S3Config.r2("<account-id>").withCredentials("<key-id>", "<secret>")
val gcs   = S3Config.gcs.withCredentials("<hmac-key-id>", "<hmac-secret>") // GCS HMAC keys
val minio = S3Config.minio("http://localhost:9000").withCredentials("admin", "secret")
val anon  = S3Config().withRegion("us-west-2").noCredentials // public buckets
```

The default chain follows the AWS Java SDK v2 order: environment keys, web identity
(`AWS_WEB_IDENTITY_TOKEN_FILE` + `AWS_ROLE_ARN`, the EKS IRSA path), the profile, the
ECS / EKS Pod Identity endpoint, then EC2 IMDSv2. A profile may use
`role_arn` + `web_identity_token_file`, IAM Identity Center (`sso_session` or legacy
`sso_start_url`; reads `aws sso login`'s cache and refreshes `sso_session` tokens),
`role_arn` + `source_profile` / `credential_source` (STS AssumeRole, chainable),
`credential_process` (not in browsers), or static keys, first match in that order.
`mfa_serial` profiles are not supported. Only `AWS_ENDPOINT_URL_STS`, `_SSO`, and
`_SSO_OIDC` redirect credential calls, never the generic `AWS_ENDPOINT_URL`. A
`NoCredentials` error lists why each source failed, without secrets. Credential
errors are `wvlet.uni.cloud.CloudException`, the parent of `StorageException`.

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
import wvlet.uni.cloud.GoogleCredentialsProvider
import wvlet.uni.cloud.storage.GcsConfig

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
import wvlet.uni.cloud.AzureCredentialsProvider
import wvlet.uni.cloud.storage.AzureConfig

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

---
description: Read and write objects in AWS S3, S3-compatible stores (Cloudflare R2, MinIO), Google Cloud Storage, and Azure Blob Storage from Scala on the JVM, Scala.js, and Scala Native, with no AWS, Google, or Azure SDK. Covers credentials (including Google Application Default Credentials), ranged reads, listing, multipart and resumable uploads, presigned and signed URLs, and Parquet files in S3 and GCS.
---

# Object Storage

`wvlet.uni.storage` reads and writes objects in [AWS S3](https://aws.amazon.com/s3/), in
services that speak the S3 API ([Cloudflare R2](https://developers.cloudflare.com/r2/) and
self-hosted servers such as [MinIO](https://min.io/) or the
[Versity S3 Gateway](https://github.com/versity/versitygw)), in
[Google Cloud Storage](#google-cloud-storage) with Google credentials, and in
[Azure Blob Storage](#azure-blob-storage). It is part of the `uni` artifact and signs requests in
pure Scala (AWS Signature Version 4, Google OAuth 2.0 and RSA, Azure Shared Key and SAS), so the
same code runs on the JVM, Scala Native, and Scala.js, with no AWS, Google, or Azure SDK.

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

Every method takes an `s3://bucket/key` (or `gs://bucket/key`, `az://container/key`) string or an
`ObjectUri`. The S3, GCS, and Azure stores share the `ObjectStore` interface, so code written
against it works with any of them. `head` returns `None` for a missing object; the other methods
raise `StorageException`, whose `code` is the store's error code (`NoSuchKey`, `AccessDenied`,
`SlowDown`, …) and `statusCode` the HTTP status. `e.isNotFound` checks for a missing object on
every store. Credential failures (`NoCredentials`, `InvalidCredentials`) raise its parent,
`wvlet.uni.cloud.CloudException` (codes in `CloudErrorCode`), so catch `CloudException` to handle
both.

## Credentials and Region

`ObjectStore.s3()` finds credentials the way the AWS SDKs do, and uses the first source that has
them. The order is the AWS Java SDK v2 default chain:

1. `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`
2. Web identity: `AWS_WEB_IDENTITY_TOKEN_FILE` and `AWS_ROLE_ARN` (optionally
   `AWS_ROLE_SESSION_NAME`), exchanged through STS `AssumeRoleWithWebIdentity`. This is how EKS
   IAM Roles for Service Accounts (IRSA) work
3. The `AWS_PROFILE` (or `default`) profile in `~/.aws/credentials` or `~/.aws/config`
4. The ECS or EKS Pod Identity container endpoint (`AWS_CONTAINER_CREDENTIALS_*`)
5. The EC2 instance profile, through IMDSv2

The AWS CLI (botocore) uses almost the same order, except that it tries a profile's `role_arn`
before web identity from the environment.

A profile can get its credentials in several ways. Uni uses the first of these that the profile
sets, in the Java SDK v2 order:

| Profile settings | Source |
|------------------|--------|
| `role_arn` + `web_identity_token_file` | STS `AssumeRoleWithWebIdentity` |
| `sso_session` (or the legacy `sso_start_url` + `sso_region`), with `sso_account_id` and `sso_role_name` | IAM Identity Center: the token that `aws sso login` cached under `~/.aws/sso/cache` |
| `role_arn` + `source_profile` or `credential_source` (`Environment`, `Ec2InstanceMetadata`, `EcsContainer`) | STS `AssumeRole`, signed with the source's credentials; source profiles can chain |
| `credential_process` | The command's JSON output (JVM, Scala Native, and Node.js) |
| `aws_access_key_id` + `aws_secret_access_key` | Static keys |

```ini
# ~/.aws/config
[profile dev]
sso_session = corp
sso_account_id = 123456789012
sso_role_name = ReadOnly

[sso-session corp]
sso_region = us-east-1
sso_start_url = https://example.awsapps.com/start

[profile deploy]
role_arn = arn:aws:iam::123456789012:role/deploy
source_profile = dev

[profile vault]
credential_process = /usr/local/bin/vault-aws-creds --role analytics
```

Some notes on these sources:

- Role profiles honor `role_session_name`, `external_id`, and `duration_seconds`. Uni can't
  prompt for an MFA code, so profiles with `mfa_serial` aren't supported; pass temporary
  credentials for those.
- When an `sso_session` token expires, Uni refreshes it with the cached refresh token and writes
  the new token back to the cache file (owner-only), as the AWS CLI does. Legacy `sso_start_url`
  profiles can't be refreshed; run `aws sso login` again.
- `credential_process` runs through `sh -c` (`cmd.exe /C` on Windows) and must print the
  `Version: 1` JSON that the AWS CLI expects. Credentials without an `Expiration` are kept until
  the process ends.
- STS is called at `sts.<region>.amazonaws.com`, and IAM Identity Center at its `sso_region`.
  `AWS_ENDPOINT_URL_STS`, `AWS_ENDPOINT_URL_SSO`, and `AWS_ENDPOINT_URL_SSO_OIDC` override those
  endpoints. The generic `AWS_ENDPOINT_URL` does not, because it often points at an
  S3-compatible store that must not receive identity tokens.

Temporary credentials are refreshed before they expire. When no source has credentials, the
`NoCredentials` error lists why each configured source failed, such as "run `aws sso login
--profile dev`", without tokens or secrets. The region comes from `AWS_REGION`,
`AWS_DEFAULT_REGION`, or the profile's `region`, and defaults to `us-east-1`. Set either
explicitly with `S3Config`:

```scala
import wvlet.uni.storage.S3Config
import wvlet.uni.cloud.AwsCredentialsProvider

val tokyo = ObjectStore.s3(
  S3Config()
    .withRegion("ap-northeast-1")
    .withCredentials(AwsCredentialsProvider.profile("analytics"))
)

// A public bucket: send unsigned requests
val open = ObjectStore.s3(S3Config().withRegion("us-west-2").noCredentials)
```

`AwsCredentials.toString` hides the secret and the session token, so credentials don't leak into
logs. `AwsCredentialsProvider.webIdentity` picks web identity from the environment explicitly, and
`AwsCredentialsProvider.profile(name)` reads any of the profile kinds above.

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
import wvlet.uni.cloud.GoogleCredentialsProvider

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

## Azure Blob Storage

`ObjectStore.azure()` reads and writes [Azure Blob Storage](https://learn.microsoft.com/azure/storage/blobs/)
with the same methods, addressed as `az://container/path/to/blob` (`azure://` works too). The
storage account comes from the store's `AzureConfig`, the way an S3 store's endpoint picks the
service:

```scala
import wvlet.uni.storage.AzureConfig

val azure = ObjectStore.azure(AzureConfig().withSharedKey("myaccount", "<account-key>"))

azure.put("az://my-container/hello.txt", "Hello".getBytes("UTF-8"))
val azureHello: Array[Byte] = azure.get("az://my-container/hello.txt")
azure.list("az://my-container/logs/").foreach(o => println(o.uri))
azure.close()
```

`ObjectStore.azure()` with no arguments reads `AZURE_STORAGE_CONNECTION_STRING`, or takes the
account from `AZURE_STORAGE_ACCOUNT` and uses the first credential source that answers, in the
order Azure's SDKs use:

1. `AZURE_STORAGE_KEY` (Shared Key), or `AZURE_STORAGE_SAS_TOKEN`
2. A service principal's secret: `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, and `AZURE_CLIENT_SECRET`
3. Workload identity on AKS: `AZURE_FEDERATED_TOKEN_FILE` with `AZURE_TENANT_ID` and `AZURE_CLIENT_ID`
4. Managed identity: App Service and Functions, then the VM instance metadata service

Microsoft Entra ID tokens are cached until shortly before they expire. They are sent only over
HTTPS, and need a role such as *Storage Blob Data Contributor* on the account. Azure CLI logins
are not read; pass a token from `az account get-access-token --resource https://storage.azure.com/`
with `AzureCredentialsProvider.bearer(token)`. Sovereign clouds set `withEndpoint` and
`AZURE_AUTHORITY_HOST`.

```scala
import wvlet.uni.cloud.AzureCredentialsProvider

// A connection string from the Azure portal
val fromPortal = ObjectStore.azure(AzureConfig.fromConnectionString("<connection-string>"))

// A SAS token: requests carry it as is
val withSas = ObjectStore.azure(AzureConfig().withAccount("myaccount").withSasToken("<sas-token>"))

// A service principal
val app = ObjectStore.azure(
  AzureConfig()
    .withAccount("myaccount")
    .withCredentials(AzureCredentialsProvider.clientSecret("<tenant-id>", "<client-id>", "<secret>"))
)

// The Azurite emulator, with its well-known development account
val emulator = ObjectStore.azure(AzureConfig.azurite())
emulator.createContainer("my-container")
```

Missing blobs raise `StorageException` with Azure's own error code (`BlobNotFound`,
`ContainerNotFound`, `AuthenticationFailed`, …); `isNotFound` is true for them.

`newOutputStream` uploads 8 MiB blocks (set `withBlockSizeBytes`) with Put Block and commits them
on `close()`; a stream that never fills a block sends one PUT. A blob holds at most 50,000
blocks, so raise the block size for objects over about 390 GiB. `abort()` skips the commit: no blob
appears, an existing blob keeps its content, and Azure discards the uncommitted blocks after a
week.

`presign` returns a SAS URL. With an account key it is a service SAS; with Entra ID credentials,
a user delegation SAS, which costs one extra request for the delegation key and is valid for at
most 7 days. A store that holds only a SAS token can't mint narrower URLs, so `presign` raises
`NoCredentials` there; share a URL with that token instead. An upload to a PUT URL must send the
`x-ms-blob-type: BlockBlob` header, as Azure requires. SAS URLs also work with `ByteSource.ofUrl`.

In browsers, use SAS tokens, ideally short-lived user delegation SAS URLs handed out by your server,
and add a CORS rule to the storage account. Never ship an account key in a web page.

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

## Creating an Object Only If Absent

`putIfAbsent(uri, bytes)` creates the object only when nothing is stored at the key yet. It
returns `Some(version)`, the new object's [version](#replacing-an-object-only-if-unchanged), when
this call created the object, and `None` when one was already there. Of several writers racing for
the same key, at most one gets a version, so it can commit a version file, such as the
`vN.metadata.json` of an [Iceberg table](../text/parquet#writing-apache-iceberg-tables), or take a
lease. S3 sends `If-None-Match: *`, GCS `ifGenerationMatch=0`, and Azure `If-None-Match: *`.
`putIfAbsentAsync` returns an `Rx[Option[String]]`.

```scala
val versionFile  = ObjectUri.parse("s3://my-bucket/table/metadata/v2.metadata.json")
val metadataJson = """{"format-version": 2}"""
if store.putIfAbsent(versionFile, metadataJson.getBytes("UTF-8")).isEmpty then
  println("another writer committed v2 first")
```

## Replacing an Object Only If Unchanged

`putIfMatch(uri, bytes, version)` replaces the object only if it still has the version you read,
so two writers can't both win a read-modify-write. Use it to move a pointer to the current
snapshot or to renew a lease held in one object.

- `ObjectInfo.version`, from `head` or `list`, is the token to pass back: the ETag on S3 and
  Azure, the generation on GCS. Treat it as opaque.
- The call returns `Some(newVersion)` when it replaced the object. Pass that version to the next
  swap; you don't need another `head`.
- It returns `None` when the race is lost: the object changed or no longer exists. Read it again
  and retry, or give up.
- Any other failure, such as a missing bucket, denied access, or throttling, raises
  `StorageException`, so an outage never looks like a lost race.
- S3 and Azure send `If-Match`, and GCS sends `ifGenerationMatch`. `putIfMatchAsync` returns an
  `Rx[Option[String]]`.

Call `head` before `get`. If another writer lands in between, the swap then fails instead of
committing changes made to stale bytes:

```scala
val pointer = ObjectUri.parse("s3://my-bucket/table/current.json")
store.put(pointer, "1".getBytes("UTF-8"))

def advance(): Option[String] =
  store.head(pointer).flatMap(_.version).flatMap { version =>
    val current = String(store.get(pointer), "UTF-8").toInt
    store.putIfMatch(pointer, (current + 1).toString.getBytes("UTF-8"), version)
  }

// Retry a lost race a few times
val committed = Iterator.continually(advance()).take(5).collectFirst { case Some(v) => v }
```

GCS's S3-compatible API ignores `If-Match` on uploads, so an S3 store configured with
`S3Config.gcs` refuses `putIfMatch` with an `UnsupportedOperationException`. Use
`ObjectStore.gcs()` for `gs://` objects instead.

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

## Parquet Files in Object Storage

[Uni's Parquet reader](../text/parquet) reads Parquet files straight from S3 or GCS with ranged reads,
fetching only the footer and the column chunks it needs:

```scala
import wvlet.uni.cloud.parquet.Parquet
import wvlet.uni.weaver.Weaver

case class Event(id: Long, name: String) derives Weaver

val events: Seq[Event] = Parquet.openS3("s3://my-bucket/events.parquet").readAs[Event]().toSeq

// Write: the upload completes when the writer closes
val writer = Parquet.newWriter[Event](store.newOutputStream("s3://my-bucket/out.parquet"))
writer.writeAll(events)
writer.close()
```

`Parquet.openS3(uri, config)` takes an `S3Config` for R2 or MinIO, `Parquet.openGcs("gs://...")`
reads from Google Cloud Storage, `Parquet.openAzure("az://container/key")` from Azure Blob, and
`openS3Async` / `openGcsAsync` / `openAzureAsync` read in browsers. `ByteSource.ofObject(store, uri)`
reads from any store you already hold. A writer that fails, or whose `abort()` you call, aborts the
upload, so no partial file appears.

## When to Use Another Library

The [AWS SDK for Java](https://aws.amazon.com/sdk-for-java/) covers every S3 feature (server-side
encryption settings, object lock, transfer acceleration, and parallel transfers) and every
credential source, and is a good choice for JVM applications that use S3 heavily or other AWS
services too. The [Google Cloud Storage client library for Java](https://cloud.google.com/java/docs/reference/google-cloud-storage/latest/overview)
does the same for GCS, with every credential type (including workload identity federation) and
features such as parallel composite uploads. The
[Azure SDK for Java](https://learn.microsoft.com/azure/developer/java/sdk/) covers every Azure
Storage feature (Data Lake directories, leases, immutability policies, parallel transfers) and
every Entra ID credential type, including Azure CLI and developer tool logins. Choose `wvlet.uni.storage` for the common object
operations on Scala.js or Scala Native, in browsers, or with no dependencies beyond Uni.

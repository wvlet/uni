# Native Google Cloud Storage in wvlet.uni.storage

## Goals

- Users reach GCS with ordinary Google credentials (service-account keys, `gcloud auth application-default login`, or the GCE/GKE metadata server), not only HMAC keys through `S3Config.gcs`. Most GCP projects never create HMAC keys, and org policies often forbid them.
- The same provider-neutral `ObjectStore` API as S3 (head, get, getRange, put, list, delete, newOutputStream with abort, presign, the `...Async` variants) on the JVM, Scala Native, and Scala.js, with no Google SDK.
- uni-parquet reads and writes `gs://bucket/key` objects as it does `s3://` ones.

## Background

- #1025 added `wvlet.uni.storage` with `S3ObjectStore`, pure-Scala SHA-256/HMAC/SigV4, a credentials chain with caching, error mapping to `StorageException`, Node.js range chunking under `maxResponseBytes`, and uni-parquet's `ObjectSource`. Its ADR expected native GCS as a second `ObjectStore`.
- Google OAuth access tokens come from three sources in ADC order: `GOOGLE_APPLICATION_CREDENTIALS` (usually a service-account JSON), the gcloud ADC file (`authorized_user` with a refresh token), then the metadata server. Tokens live about one hour.
- A service account gets a token by signing a JWT with RS256 (RSA PKCS#1 v1.5 over SHA-256). V4 signed URLs with a service account need the same RSA signature. Scala Native has no `java.security`, but `java.math.BigInteger` (with `modPow`) and `java.util.Base64` exist on all three platforms.
- `presign` exists only on `S3ObjectStore` today, not on the `ObjectStore` trait.

## Design

Lane: Standard — new public API and credential handling (OAuth, private keys), an always-gate area.

User flow:
- `val store = ObjectStore.gcs()` finds credentials in ADC order; `store.get("gs://bucket/key")` works the same as S3. `GcsConfig().withCredentials(GoogleCredentials.serviceAccount(jsonPath))` or `.withAccessToken(token)` overrides it.
- `Parquet.openGcs("gs://bucket/data.parquet")` reads with ranged requests; `Parquet.newWriter[A](store.newOutputStream(uri))` writes, and a failed write aborts the upload.
- With no credentials, the first request fails with `StorageException(NoCredentials)` naming every source checked; a wrong project or bucket fails with `AccessDenied` / `NoSuchBucket` carrying Google's message.
- Local testing: `STORAGE_EMULATOR_HOST` (the convention Google's own libraries use) points the store at an emulator such as fake-gcs-server with anonymous requests.

Decisions:
- **JSON API, not XML.** Listings, metadata, and errors are JSON, which Uni already parses (the XML API would mean growing the private `S3Xml`). Media download (`alt=media`) honours `Range`. Large streams use **resumable uploads**: buffer 8 MiB chunks (a multiple of 256 KiB, as Google requires), `PUT` each with `Content-Range`, finish with the total size; a stream that never fills a chunk sends one simple upload. Google answers non-final chunks with 308 and a `Range` header that may keep fewer bytes than sent, so the store sends `X-GUploader-No-308: yes` (200 instead of 308, which browser `fetch` with manual redirects would hide), reads the committed offset, and resends the rest. `abort()` DELETEs the session URI.
- **Object names are one path segment** in the JSON API (`/storage/v1/b/{bucket}/o/{name}`, `/` sent as `%2F`), unlike S3's per-segment encoding; tests cover keys with `/`, spaces, and non-ASCII.
- **Session URIs are secrets.** Google treats a resumable session URI as an auth token, so it never appears in logs or exception messages, and the store refuses a `Location` on another host than the endpoint.
- **Pure-Scala RSA on every platform.** A small DER reader for PKCS#8 (and PKCS#1 `BEGIN RSA PRIVATE KEY`) plus PKCS#1 v1.5 signing with `BigInteger.modPow`, using the CRT parameters for a ~4x speedup and RSA blinding, since `modPow` is not constant-time. One code path on all platforms, so the signer the JVM tests cover is the one Native and JS run; it is checked on the JVM against `java.security.Signature` with generated keys, against RFC 7515's RS256 example (PKCS#1 v1.5 is deterministic), and the DER reader against the PEM key in Google's conformance `v4_signatures.json`. Limiting signing to JVM/Node would leave Native without service accounts and signed URLs, and on Node would need a separate `crypto` binding for the same result.
- **ADC files.** `GOOGLE_APPLICATION_CREDENTIALS`, then `$CLOUDSDK_CONFIG/application_default_credentials.json`, `~/.config/gcloud/…`, or `%APPDATA%\gcloud\…` on Windows. A file whose `type` is unsupported (`external_account`, `impersonated_service_account`) fails with `NoCredentials` naming the type, rather than silently falling through to the metadata server.
- **Credentials mirror the AWS side.** `GoogleCredentialsProvider` with `static(accessToken)`, `serviceAccount(json)`, `authorizedUser(json)`, `metadataServer`, `anonymous`, and `default` (the ADC chain). The chain remembers the provider that answered and rescans on failure; tokens are cached until 5 minutes before expiry and refreshed under a lock, so a 1000-chunk upload makes one token request. Metadata calls use 1–2 s timeouts, no retry, `Metadata-Flavor: Google`, and `GCE_METADATA_HOST`. The default scope is `devstorage.read_write`, configurable.
- **Secrets never reach logs.** `toString` masks access tokens, refresh tokens, client secrets, and private keys; error messages from token endpoints keep Google's `error` code but never echo the request body.
- **`presign` moves onto `ObjectStore`.** GCS signs V4 URLs (`GOOG4-RSA-SHA256`) in the XML-API form (`storage.googleapis.com/bucket/key`) with the service account's key, reusing SigV4's canonical request and query encoding. Metadata-server credentials sign through IAM `signBlob` (one POST with the instance's service-account email), so GKE/GCE users can share URLs too. User credentials can't sign and fail with `NoCredentials` saying why. fake-gcs-server doesn't verify signatures, so Google's conformance vectors are the check.
- **Shared pieces are factored, not copied.** Range chunking for Node.js, HTTP-error unwrapping, and `concat` move into a private helper used by both stores. Redirects stay off so a bearer token never goes to another host.
- **Errors.** Google's `{"error": {...}}` maps to `StorageException`: 404 → `NoSuchKey`, or `NoSuchBucket` when Google's message says the bucket does not exist (both carry reason `notFound`), 403 → `AccessDenied`, otherwise Google's `reason` (`rateLimitExceeded`, …). `head` returns `None` for 404 and `delete` treats 404 as success, as for S3.
- **Browsers** have no files, environment, or metadata server, so they take an explicit short-lived access token or signed URLs from a server. The docs warn against shipping service-account keys in a page.
- **Tests.** Unit tests cover JWT/RS256 and DER parsing (RFC 7515 vectors, JVM cross-check), V4 signed URLs against Google's conformance examples, ADC file parsing, and error mapping; integration tests run against a pinned, checksum-verified fake-gcs-server binary (`-scheme http -public-host`, so resumable session URIs point back at it) in the JVM CI job and are skipped unless `UNI_GCS_TEST_ENDPOINT` is set. fake-gcs-server checks no auth, so a fake token endpoint (a Uni `HttpServer` in the test) checks the JWT-bearer and refresh-token exchanges end to end.
- Docs (`docs/core/object-storage.md`), `for-ai-agents.md`, the plugin's `references/storage.md` (version bump), and an ADR.

Out of scope: workload identity federation (`external_account`), impersonated service accounts, `signBlob` for user credentials, bucket management, parallel chunk uploads.

## Alternatives and Why Not?

- **XML API with OAuth.** Same endpoints as the S3 path, but resumable uploads and listings would need more XML parsing and fake-gcs-server's XML support is thinner.
- **JWT signing only on JVM/Node.** Leaves Native users with only metadata or user credentials, and adds a JS-specific crypto binding; BigInteger RSA is small and runs everywhere.
- **Self-signed JWT used directly as the bearer token** (no token exchange). Saves a round trip, but only works for service accounts and Google's own docs treat it as an optimisation; the token exchange is the path every ADC source shares.
- **`gs://` routed through `S3ObjectStore` with OAuth.** Mixes two auth schemes into SigV4 code; a separate store keeps each signer simple.

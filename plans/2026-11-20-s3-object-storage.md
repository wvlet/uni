# S3-compatible object storage in Uni

## Goals

- Read and write objects in S3 and S3-compatible stores (Cloudflare R2, GCS through HMAC interop, MinIO) from Uni on JVM, Scala Native, and Scala.js, without the AWS SDK.
- Open Parquet files in S3 directly (`s3://bucket/key`) and write Parquet straight to S3.
- One protocol implementation should cover the common object stores; endpoints decide the provider.

## Background

- uni-parquet already reads remote files with HTTP Range requests (`ByteSource.ofUrl` / `ofUrlAsync`). An S3 read is the same ranged GET plus a signature.
- `HttpClientConfig.addRequestFilter` runs once before the retry loop, so a signed request survives retries; a signature stays valid for 15 minutes, longer than any retry backoff.
- Redirects do not re-run the filter: `handleRedirect` resends the signed headers to the new `Location` host, and redirects are followed by default. S3 answers a wrong-region request with 301/307.
- On Node.js the sync client caps responses at `maxResponseBytes` (16 MB by default; see the Node sync HTTP ADR).
- On Native the curl channel sends no body for an empty PUT/POST, so S3 may reject zero-byte PUTs and `POST ?uploads` with 411.
- Scala Native has no `java.security.MessageDigest`, so SHA-256 and HMAC-SHA256 must be pure Scala, following `uni/.../http/Sha1.scala`.
- S3 answers list, multipart, and error calls in XML. Uni has no XML parser.
- Uni keeps dependencies minimal, so the AWS SDK (and its transitive tree) is not an option.
- Parquet writers already accept a `java.io.OutputStream`, so S3 writes need only an upload stream.

## Design

Lane: Standard — new public API and credential handling (an always-gate area).

User flow:

- A user on EC2/ECS/a laptop with `~/.aws` calls `ObjectStore.s3()` (default credential chain and region) or passes an explicit config.
- They `get`, `head`, `put`, `list`, `delete` objects, or call `Parquet.openS3("s3://bucket/key")` / `ByteSource.ofS3(...)` to read Parquet.
- To write Parquet, they pass `store.newOutputStream("s3://bucket/out.parquet")` to `Parquet.newWriter`; the object appears when the writer closes.
- Failures surface as a `StorageException` carrying the S3 error code (`NoSuchKey`, `AccessDenied`, …) and HTTP status; `head` returns `None` for a missing object.
- For browsers or third parties, `store.presign(GET|PUT, uri, expiry)` returns a URL usable with `ByteSource.ofUrl` or a plain fetch.

Decisions:

- Package `wvlet.uni.storage` in the main `uni` artifact; it depends only on `wvlet.uni.http`.
- `ObjectStore` is a provider-neutral trait so Azure Blob and GCS OAuth can be added later without breaking callers; `S3ObjectStore` is the first implementation.
- `S3Config` follows the `withXXX` / `noXXX` convention: region, endpoint, path-style addressing, credentials provider, part size. Presets for R2 (account id), GCS (storage.googleapis.com, HMAC keys), and MinIO (URL, path-style).
- Sync API on JVM, Native, and Node.js; an `Rx` async API on every platform, including the browser. In the browser there is no env, file, or IMDS: it uses presigned URLs or explicitly passed (ideally temporary) keys, and the docs warn against shipping long-lived keys. Buckets need CORS.
- The S3 client turns redirects off (`noFollowRedirects`), so signed headers never reach another host; 301/307 become the wrong-region error below.
- Signed headers: `host`, `x-amz-*`, and `content-type` when the store sets it itself; `Host`/`Content-Length` are left to the channel (restricted on the JVM).
- Sync ranged reads are split into chunks below `maxResponseBytes`, so large column chunks and whole-object `get`s work on Node.js too.
- Fix the Native curl channel to send `Content-Length: 0` for empty PUT/POST bodies.
- Signing happens inside the store, which builds the wire URI itself, so the canonical request matches what is sent.
- Signing: SigV4 header signing with the real SHA-256 of the payload (bodies are in memory: single PUTs and buffered parts); query-string SigV4 for presigned URLs (expiry ≤ 7 days).
- Credentials chain, in AWS order: explicit → env (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`) → shared files (`AWS_PROFILE`, `AWS_SHARED_CREDENTIALS_FILE`, `~/.aws/config` for region) → ECS container endpoint → EC2 IMDSv2. Temporary credentials are cached and refreshed before expiry. An anonymous option reads public buckets.
- Credentials never appear in logs, `toString`, or exception messages.
- Multipart upload stream: buffers 8 MiB parts (configurable, ≥ 5 MiB), uploads parts sequentially, completes on `close()`, aborts the upload on failure. Objects smaller than one part use a single PUT.
- A small private XML reader handles only S3 responses (list results, multipart ids, errors); no public XML API.
- No automatic region discovery: a wrong-region error names the bucket's region from `x-amz-bucket-region`.
- uni-parquet adds `ByteSource.ofS3` / `ofS3Async` and `Parquet.openS3` / `openS3Async` on top of the store's ranged reads.
- Tests cover SHA-256 (NIST vectors), HMAC (RFC 4231), the SigV4 signer and presigner (AWS published examples), the credential chain against files and env, the XML reader, and an integration suite against an S3-compatible server in the JVM CI job (skipped when `UNI_S3_TEST_ENDPOINT` is unset).
- The CI server is the Versity S3 Gateway (pinned, checksum-verified release; verifies SigV4): MinIO no longer publishes community binaries or official images, and third-party mirrors are a supply-chain risk.
- Docs: a storage guide page, `for-ai-agents.md`, and the `uni` plugin skill (snippets compiled by `docSnippets`, `plugin.json` version bump, `claude plugin validate`).
- An ADR records the redirect policy, the credential chain, and the private-only XML reader.

Out of scope (later): Azure Blob, GCS OAuth, SSO / web-identity (EKS IRSA) / `credential_process` credentials, parallel part uploads.

## Alternatives and Why Not?

- **Wrap the AWS SDK.** Large dependency tree, JVM only; conflicts with minimal dependencies and cross-platform goals.
- **Separate `uni-storage` artifact.** Considered; the user chose to keep it in `uni` since it adds no dependencies.
- **`UNSIGNED-PAYLOAD` for all requests.** Saves hashing but is rejected over plain HTTP (local MinIO) and weakens integrity; bodies are already in memory, so hash them.
- **Use presigned URLs only.** Simple, but users still need list, delete, and multipart uploads from the server side.

# Azure Blob Storage in uni

## Context

`wvlet.uni.storage` shipped with an S3 store (`2026-11-20-s3-object-storage.md`), and users with data in Azure wanted the same `ObjectStore` API there, on the JVM, Scala Native, and Scala.js. The Azure SDK for Java runs only on the JVM and has a large dependency tree, so, as with S3, the protocol is implemented in Uni.

## Decision

- **`AzureObjectStore` implements `ObjectStore`, addressed as `az://container/blob`.** `azure://` also works and is kept as written, so listings return the caller's scheme. Other schemes are rejected. DuckDB, fsspec, and Polars use the same form. The storage account comes from `AzureConfig` (account, endpoint, connection string, or `AZURE_STORAGE_ACCOUNT`), the way an S3 store's endpoint picks the service. `abfss://container@account.dfs...` is left out: it names the Data Lake (DFS) API, and the Blob API works on every account, including hierarchical-namespace ones.
- **Built on `StorageSupport`**, the shared helpers that S3 and GCS use (`2026-11-20-gcs-object-storage.md`): HTTP-error unwrapping, lazily created clients, and range chunking under Node's response cap. An inheritance base for all stores was tried first, but GCS landed composition helpers in the meantime, and one mechanism is enough. On Node.js, a whole-object `get` checks the HEAD response itself, so a missing blob keeps Azure's code instead of becoming `NoSuchKey`.
- **Shared Key is signed inside the store, from the exact wire URI**, as SigV4 is:
  - The canonicalized resource is `/<account>` followed by the encoded wire path, so an emulator's path-style endpoint repeats the account, as Microsoft specifies.
  - A zero `Content-Length` is signed as empty.
  - The store sets `Content-Type` on every request that sends one, because the signature covers it. curl would otherwise add a form content type to PUT and POST bodies.
  - Ranges use `x-ms-range`.
  - `x-ms-version` is pinned to `2024-11-04`, which current Azurite releases accept.
  - Signer tests use the canonicalized-resource and string-to-sign examples from Microsoft's "Authorize with Shared Key" page, and check the HMAC against `javax.crypto.Mac` on the JVM.
- **Credentials.** Like the AWS chain, and unlike GCS's Application Default Credentials, a provider that fails falls through to the next one, as Azure's `DefaultAzureCredential` does; the final error names every provider that failed and why. The supported kinds are Shared Key, SAS tokens (appended to each request), Entra ID bearer tokens, and anonymous. The default chain follows the order Azure's SDKs use:
  - `AZURE_STORAGE_CONNECTION_STRING`, read by `AzureConfig.default`
  - `AZURE_STORAGE_KEY`, then `AZURE_STORAGE_SAS_TOKEN`
  - A client secret (`AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`)
  - Workload identity (`AZURE_FEDERATED_TOKEN_FILE`), with the file read again on each refresh
  - Managed identity: App Service `IDENTITY_ENDPOINT` first, then VM IMDS

  How the chain behaves:
  - It remembers the provider that answered. Tokens are cached until 5 minutes before expiry.
  - Metadata calls use 1–2 s timeouts and no retry.
  - `AZURE_AUTHORITY_HOST` selects sovereign clouds.
  - Bearer tokens are refused on plain-HTTP endpoints.
  - `toString` masks keys, SAS signatures, and tokens.

  Azure CLI logins are left out: reading them needs a subprocess, and the CLI's token cache is not a stable format. Users can pass `az account get-access-token` output to `AzureCredentialsProvider.bearer`.
- **`presign`** (on `ObjectStore` since the GCS store) on Azure:
  - A Shared Key store signs a service SAS.
  - An Entra ID store signs a user delegation SAS. The delegation key comes from one Get User Delegation Key call and is cached for up to 7 days.
  - A SAS-only store raises `NoCredentials`, because it can't mint a narrower token.
  - Expiry is capped at 7 days, as on S3.
- **Block uploads.**
  - `newOutputStream` uploads 8 MiB blocks with Put Block and commits them with Put Block List (`<Latest>`) on `close()`. A stream that never fills a block sends one Put Blob.
  - Block IDs are Base64 of 36 bytes (a random tag per upload, plus a counter). Azure needs every block ID of a blob to have the same length, including blocks left by earlier uploads, and the Azure SDKs use the same length, as UUID strings.
  - More than 50,000 blocks fails with a message to raise the block size.
  - `abort()` skips the commit. Azure has no call to drop uncommitted blocks, short of deleting the blob, which would destroy an existing object. They never become a blob, and Azure discards them after 7 days.
- **Errors keep Azure's codes** (`BlobNotFound`, `AuthenticationFailed`, …). They come from the `<Error>` body, or from `x-ms-error-code`, which HEAD responses carry instead of a body. Only the first line of the message is kept, without the RequestId and Time lines. Mapping them to S3 codes would be partial and lossy; `isNotFound` and `statusCode` are the portable checks.
- **The private `S3Xml` reader keeps attributes.** Azure listings mark names that XML can't carry with `Encoded="true"`, and those names are percent-decoded. The reader stays private, and keeps its name to avoid churn; the `x-ms-date` and SAS timestamp formatters likewise join `AwsTime`.
- **Parquet.** `Parquet.openAzure` / `openAzureAsync` and `ByteSource.ofAzure` / `ofAzureAsync` mirror the S3 helpers. They create the store from a config and close it with the file. `ByteSource.ofObject` works with either store.
- **`AzureObjectStore.createContainer` is public.** Tests and Azurite users need it on a fresh emulator. S3 has no bucket creation, because buckets there are usually provisioned elsewhere.
- **CI runs Azurite from Microsoft's official image**, pinned by digest, in the JVM job. The tests are skipped unless `UNI_AZURE_TEST_CONNECTION_STRING` is set (`UseDevelopmentStorage=true` for Azurite), so they also run against a real account. Azurite verifies Shared Key and SAS signatures. Its OAuth mode needs HTTPS and certificates, so bearer tokens are covered by unit tests against a local token endpoint rather than end to end.

## Consequences

- Code written against `ObjectStore` (including `presign`) runs on S3-compatible stores, GCS, and Azure Blob alike.
- The Azure integration tests passed on the JVM, Scala Native, and Node.js against Azurite. CI runs only the JVM job.
- Entra ID presigning and token flows have not been exercised against a real tenant in CI.
- Not covered: append and page blobs, leases, snapshots (deleting a blob that has snapshots fails with `SnapshotsPresent`), parallel block uploads, and Data Lake (DFS) APIs.

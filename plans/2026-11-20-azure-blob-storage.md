# Azure Blob Storage in wvlet.uni.storage

## Goals

- Read and write Azure Blob Storage objects through the same `ObjectStore` API that `S3ObjectStore` implements, on the JVM, Scala Native, and Scala.js, with no Azure SDK.
- Read and write Parquet files in Azure Blob from uni-parquet, as S3 already allows.
- Keep `ObjectStore` provider-neutral: code written against the trait should work unchanged on S3 or Azure.

## Background

- #1025 added `wvlet.uni.storage` with `S3ObjectStore`. The ADR (`adr/2026-11-20-s3-object-storage.md`) expected Azure to be "another `ObjectStore` with its own signer (SharedKey or SAS)".
- Reusable pieces: pure-Scala `Sha256.hmac` (Azure SharedKey and SAS are HMAC-SHA256 with a Base64 key), `wvlet.uni.util.Base64`, the private `S3Xml` reader, the error unwrapping, and the Node.js chunked-range logic.
- Azure addresses objects as account, then container, then blob. The account sits in the host name (`<account>.blob.core.windows.net`); Azurite and other emulators put it in the path (`http://127.0.0.1:10000/devstoreaccount1`).
- Azure's listing XML marks names that are not valid XML with an `Encoded="true"` attribute. `S3Xml` drops attributes, so it cannot see that marker.
- Azure has no "abort upload" call. Uncommitted blocks never become a blob and are garbage-collected after 7 days.
- Bearer-token (Entra ID) requests must use HTTPS. Azurite's OAuth mode needs HTTPS and a certificate, so CI can't easily exercise bearer tokens end to end.
- `presign` exists today only on `S3ObjectStore`, not on the `ObjectStore` trait.

## Design

Lane: Standard. This adds a public API and handles credentials, which is an always-gate area.

User flow:

- A user with a storage account calls `ObjectStore.azure()`. The default chain finds credentials from the environment, as the Azure CLI and SDKs do. They can also pass `AzureConfig` explicitly: account name and key, a SAS token, a connection string, or an Entra ID token provider.
- They call `get`, `head`, `getRange`, `put`, `list`, `delete`, or `newOutputStream` with `az://container/path/to/blob` URIs. They read Parquet with `Parquet.openAzure("az://container/data.parquet")` and write it with `Parquet.newWriter(store.newOutputStream(uri))`.
- Failures raise `StorageException` with Azure's error code (`BlobNotFound`, `AuthenticationFailed`, …) and the HTTP status. `isNotFound` is the portable check. `head` returns `None` for a missing blob.
- `presign` returns a SAS URL for sharing a download or accepting a browser upload.

Decisions:

- **URIs.** Use `az://container/blob`, the form DuckDB, fsspec/adlfs, and Polars use. `azure://` is accepted as an alias and kept as written, so listings return URIs in the caller's scheme. The Azure store rejects other schemes, such as `s3://`, with a clear error. The account comes from the store config, just as an S3 store's endpoint decides which service answers. `ObjectUri.bucket` holds the container. `abfs[s]://container@account.dfs...` and full `https://` blob URLs are out of scope. A `https://` SAS URL already works with `ByteSource.ofUrl`.
- **`AzureConfig`** has `withXXX`/`noXXX` methods for the account name, endpoint (defaults to `https://<account>.blob.core.windows.net`; sovereign clouds and Azurite set it), credentials, block size, and HTTP client. `AzureConfig.fromConnectionString(...)` parses `AccountName`/`AccountKey`/`SharedAccessSignature`/`BlobEndpoint`/`EndpointSuffix`/`DefaultEndpointsProtocol`. `AzureConfig.azurite` is a preset with the well-known emulator account.
- **The account name** comes from `withAccount`, a connection string, an explicit endpoint, or `AZURE_STORAGE_ACCOUNT`, whatever the credential type, because SAS and Entra ID credentials don't name it. A store with no account fails at construction with a message naming those options.
- **Credentials:**
  - SharedKey (account name and key)
  - SAS token (appended to every request)
  - Entra ID bearer tokens: client credentials (client secret), workload identity (federated token file, for AKS), and managed identity (App Service/Functions `IDENTITY_ENDPOINT`, then VM IMDS)
  - Anonymous, for public containers
- **The default chain**, in order:
  - `AZURE_STORAGE_CONNECTION_STRING`
  - `AZURE_STORAGE_KEY`, with the account from `AZURE_STORAGE_ACCOUNT`
  - `AZURE_STORAGE_SAS_TOKEN`
  - `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, and `AZURE_CLIENT_SECRET`
  - `AZURE_FEDERATED_TOKEN_FILE`
  - Managed identity

  The chain remembers which provider answered and caches tokens until 5 minutes before they expire, as the AWS chain does. Metadata probes use 1–2 s timeouts with no retry. `toString` masks keys, SAS signatures, and tokens.
- **Left out of the chain: Azure CLI login (`az account get-access-token`).** Uni would have to spawn a process, and the token cache is not a stable file format. Users can pass a token from it explicitly.
- **Signing.**
  - SharedKey builds the canonical headers and resource from the exact wire URI inside the store, as SigV4 does, not in a request filter.
  - The `Content-Length` and `Content-Type` that are signed are the ones the store sends.
  - The canonicalized resource is `/<account>` followed by the encoded wire path. With an emulator's path-style endpoint the account appears twice (`/devstoreaccount1/devstoreaccount1/container/blob`), as Microsoft specifies.
  - A zero `Content-Length` is signed as an empty string.
  - Every request sends the `x-ms-version` header. The version is pinned to one that Azurite supports.
- **Presign.**
  - SharedKey signs a service SAS for the blob, with permissions from the HTTP method.
  - Entra ID signs a user delegation SAS. The delegation key comes from one extra call and is cached.
  - A store with only a SAS token can't mint narrower URLs, so `presign` raises `NoCredentials` and says to share the SAS URL itself.
  - `presign(uri, method = GET, expiresSeconds = 3600)` moves up into the `ObjectStore` trait, with S3's current defaults, so callers stay portable. Anonymous stores raise `NoCredentials`, as S3 does today.
- **Large uploads.**
  - `newOutputStream` buffers 8 MiB blocks and sends each with Put Block. `close()` commits them with Put Block List.
  - A stream that never fills a block sends a single Put Blob.
  - `abort()` skips the commit, so no blob appears and Azure discards the blocks. An existing blob is left untouched.
  - Block IDs are Base64 of a fixed-width counter, because Azure requires every block ID of a blob, including blocks left uncommitted by an earlier aborted upload, to have the same length.
  - `withBlockSizeBytes` accepts 64 KiB to 2000 MiB (Azure allows 4000 MiB, but one block must fit in a byte array). A stream that would need more than 50,000 blocks (about 390 GiB at 8 MiB) fails with a message to raise the block size.
  - Blocks are uploaded one at a time, as S3 parts are.
- **Listing** uses `restype=container&comp=list` with `prefix`, a `/` delimiter for one-level listing, and `NextMarker` paging. Directory entries come from `BlobPrefix`. Names marked `Encoded="true"` are percent-decoded in both `Blob` and `BlobPrefix`.
- **Errors.**
  - Azure's codes pass through as sent, as S3's do: from the `<Error>` body, or from the `x-ms-error-code` header on HEAD responses, which have no body.
  - `head` maps 404 to `None`, and `delete` treats 404 as success.
  - Redirects are off, so a signed header never reaches another host.
- **XML.** Extend the private `S3Xml` reader to keep attributes (name kept to avoid churn). Azure needs them for `Encoded="true"` names. It stays private.
- **Shared internals.** Reuse the `StorageSupport` helpers that #1029 (GCS) introduced while this PR was open: client lifecycle, error unwrapping, and chunked ranges. (The first version extracted an inheritance base for S3 and Azure; it was dropped on rebase so there is one mechanism.)
- **`createContainer`** is public on `AzureObjectStore`, because a fresh Azurite has no containers. S3 has no bucket creation.
- **Parquet.** Add `Parquet.openAzure`/`openAzureAsync` and `ByteSource.ofAzure`/`ofAzureAsync`, mirroring the S3 helpers. They earn their place as the one-line common case: they create the store from the default chain and close it with the file, which `ByteSource.ofObject(store, uri)` leaves to the caller. `ofObject` works with either store.
- **Browsers.** The `...Async` methods work there. The docs recommend SAS URLs or user delegation SAS from a server, warn against shipping account keys, and note that the account needs a CORS rule.
- **Tests.**
  - SharedKey and SAS string-to-sign checked against Microsoft's documented examples, and HMAC checked against `javax.crypto.Mac` on the JVM
  - Parsing tests for connection strings, listings, and errors
  - Token-provider tests against a local HTTP server. The authority host honors `AZURE_AUTHORITY_HOST`, as Azure's SDKs do, which sovereign clouds need too
  - Integration tests against Azurite, from Microsoft's official image (`mcr.microsoft.com/azure-storage/azurite`, pinned by digest) in the JVM CI job. They are skipped unless `UNI_AZURE_TEST_CONNECTION_STRING` and `UNI_AZURE_TEST_CONTAINER` are set.
- **Docs.** Extend `docs/core/object-storage.md` with an Azure section, update `for-ai-agents.md`, `references/storage.md`, and `references/parquet.md` in the plugin skill, bump the plugin version, and add an ADR.

## Alternatives and Why Not?

- **The Azure SDK for Java.** It runs only on the JVM and has a large dependency tree, so it fails the minimal-dependency rule.
- **Reaching Azure through an S3 gateway.** Azure has no native S3 API, and a gateway is extra infrastructure.
- **`abfss://container@account.dfs.core.windows.net/` URIs.** That is the ADLS Gen2 (DFS) endpoint, a different REST API. `az://` addresses the Blob API, which works on every account, including hierarchical-namespace ones.
- **Mapping Azure codes to S3 codes (`BlobNotFound` → `NoSuchKey`).** The mapping would be partial and lossy. `isNotFound` and `statusCode` already give portable checks.
- **Deleting uncommitted blocks on abort.** Azure has no API for that, short of deleting the blob, which would destroy an existing object.
- **Running Azurite from npm in CI.** Microsoft's container image pinned by digest avoids resolving npm packages at CI time.

# ObjectStore conditional writes: putIfMatch and versioned putIfAbsent

- Status: Accepted
- Date: 2026-11-22
- Plan: `plans/2026-11-22-objectstore-put-if-match.md`

## Context

Catalogs built on object storage keep a snapshot pointer or a lease in one object and need to replace it only when no one else did first. `putIfAbsent` covered create-if-absent, but there was no replace-if-unchanged. The three services condition writes on different values: S3 and Azure take `If-Match` on the ETag, while GCS JSON uploads take only `ifGenerationMatch`, not the ETag. GCS's S3-compatible XML API ignores `If-Match` on uploads.

## Decision

- `ObjectInfo.version` is one opaque token per store: the ETag on S3 and Azure, the generation on GCS. `head` and `list` give the same string for the same object; Azure listing ETags are quoted to match HEAD and PUT answers. `etag` keeps its old meaning.
- `putIfMatch(uri, bytes, version)` and `putIfAbsent(uri, bytes)` both return `Option[String]`: the new version from the PUT response on success, `None` on a lost race. Returning the version lets a lease be taken and renewed with no `head` in between, which another writer could slip into. `putIfAbsent` changed from `Boolean` with no deprecated alias.
- A lost race is only a failed precondition: 412 on every store; S3 404 `NoSuchKey` (If-Match on a missing key) and S3 409 (a concurrent conditional write); Azure 409 `BlobAlreadyExists` for `putIfAbsent` only. Every other answer raises `StorageException`, so a retry loop never spins on an outage. Azure `putIfMatch` keeps 409 an error because Put Blob also answers 409 for immutability policies and rehydration.
- An `S3ObjectStore` whose endpoint host is `googleapis.com` or a subdomain refuses `putIfMatch` with `UnsupportedOperationException` instead of overwriting without a check. Use `ObjectStore.gcs()`.
- GCS `putIfMatch` rejects a version that is not a positive generation, since `ifGenerationMatch=0` would turn the swap into a create.
- Uni ships only the mechanism. What a pointer, lease, or epoch means belongs to the caller.

## Consequences

- Callers read with `head` before `get`, so a write landing in between fails the swap instead of committing changes to stale bytes.
- New `ObjectStore` implementations must provide `putIfMatch` and `putIfMatchAsync` with the same lost-race contract.
- A conditional delete (`deleteIfMatch`) is not provided yet.

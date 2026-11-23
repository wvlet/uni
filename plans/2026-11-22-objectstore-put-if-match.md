# ObjectStore.putIfMatch: replace an object only if it is unchanged

## Goals

- Let a caller replace an existing object only when it still holds the version the caller read, so two writers can't both win a read-modify-write.
- Wvlet's catalog needs this to move a snapshot pointer by compare-and-swap and to renew an epoch-fenced lease held in one object. Both are a loop: read the object and its version, compute the new bytes, replace only if the version still matches, retry on a lost race.
- Uni ships the mechanism only. What a pointer, lease, or epoch means stays in Wvlet.

## Background

- `putIfAbsent` already covers create-if-absent on S3 (`If-None-Match: *`), GCS (`ifGenerationMatch=0`), and Azure (`If-None-Match: *`). It returns `false` on a lost race instead of throwing, so callers retry without catching exceptions.
- All three services can make a write conditional on the current object, but on different values:
  - S3 and Azure compare the ETag (`If-Match`). S3 gained conditional `If-Match` writes in late 2024; R2 also supports them.
  - The GCS JSON upload API takes `ifGenerationMatch=<generation>`, not the ETag. The GCS ETag is a different string and can't be passed back.
- `ObjectInfo` exposes `etag` from `head` and `list`, but not the GCS generation. A put returns `Unit`, so after a write the caller must call `head` again to learn the new version, and another writer can slip in between.
- The CI test servers all support conditional PUTs: versitygw honors `If-Match` on PutObject (atomic since v1.8.0; CI pins v1.11.0), fake-gcs-server takes `ifGenerationMatch`, and Azurite takes `If-Match`.
- The `S3Config.gcs` preset sends S3 requests to GCS's XML API, which ignores `If-Match` on uploads. A swap sent that way would silently overwrite and report success.

## Design

Lane: Standard — a new public API on `ObjectStore` and a new field on `ObjectInfo`.

- **Caller flow.** `head(uri)` gives `ObjectInfo.version`, then `get(uri)` reads the bytes. Calling `head` first matters: if another writer lands in between, the swap fails safely instead of committing on stale bytes. The caller calls `putIfMatch(uri, bytes, version)`. On success it gets the new version and can chain the next swap without another `head`. On `None` it re-reads and retries, or gives up.
- **One opaque version token for all stores.** `ObjectInfo` gains `version: Option[String]`: the ETag on S3 and Azure, the generation on GCS. `head` and `list` produce the same string for the same object (Azure listings send the ETag without quotes, so it is normalized). The new version after a swap comes from the PUT response (the `ETag` header on S3 and Azure, `generation` in the GCS response body). Callers pass it back unchanged and never parse it. `etag` stays as it is.
- **`putIfMatch` returns `Option[String]`.** `Some(newVersion)` when this call replaced the object, `None` when the precondition failed. This follows `putIfAbsent`'s style (a lost race is a value, not an exception) but carries the new version, which a lease renewal loop needs on every round. `putIfMatchAsync` returns `Rx[Option[String]]`.
- **What counts as a lost race (`None`):** a version mismatch or a missing object (412 on all three stores; S3 can also answer 404 `NoSuchKey`), and a concurrent conditional write in flight (S3 409). Other 404s (`NoSuchBucket`, `ContainerNotFound`) and every other failure (auth, throttling, network) still raise `StorageException`, so a caller never mistakes an outage for a lost race.
- **Refuse what can't be safe.** On an S3 store pointed at `storage.googleapis.com`, `putIfMatch` fails by name and points to `ObjectStore.gcs()`, instead of overwriting without a check.
- **No conditional delete in this PR.** Lease release can write a released marker with `putIfMatch`. A `deleteIfMatch` can follow when Wvlet needs it.
- **`putIfAbsent` also returns `Option[String]`** (the new version, or `None` when an object exists), so a lease is acquired and then renewed with no `head` in between. This is a breaking change with no deprecation alias; internal callers switch to `.isDefined`.
- Tests: each store's integration test (env-gated, real emulators, no mocks) covers a successful swap, a stale-version swap returning `None`, a missing-object swap returning `None`, chaining two swaps with the returned version, and the async variant. The in-memory test store implements the same contract.
- Docs: a "Replacing an Object Only If Unchanged" section next to the `putIfAbsent` one in `docs/core/object-storage.md`, plus the AI-agent page and the `uni` skill's storage reference (with a plugin version bump).

## Alternatives and Why Not?

- **Take the ETag on every store.** GCS uploads can't be conditioned on the ETag, so GCS would need a hidden `head` to map ETag to generation, which reopens the race this API closes.
- **Return `Boolean` like `putIfAbsent`.** Forces a `head` after each success to learn the new version, which costs a round trip and can return a later writer's version.
- **Throw a `PreconditionFailed` exception.** Inconsistent with `putIfAbsent`, and retry loops would catch exceptions for the normal case.

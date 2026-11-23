# Data formats under `wvlet.uni.cloud`

Goal: put the cloud-native utilities in one package. Parquet, Iceberg, Delta, and Arrow become siblings under `wvlet.uni.cloud`, next to the credentials and signing code.

## This PR

1. Move `wvlet.uni.parquet` to `wvlet.uni.cloud.parquet` and `wvlet.uni.arrow` to `wvlet.uni.cloud.arrow` (main, test, and platform folders, `uni-npm`, and the benchmark).
2. Move the Iceberg code (`Iceberg*`, `Avro`) to `cloud.iceberg` and `DeltaLog` to `cloud.delta`. Internals shared across these packages become `private[cloud]`. `#1155` had widened some of them to `private[uni]`; those become `private[cloud]` too.
3. Move the Iceberg table operations from `Parquet.*Iceberg*` to a new `Iceberg` object (`alterTable`, `expireSnapshots`, `deleteRows`, `updateRows`, `compactTable`, `renameTable`, `dropTable`).
4. Move `ByteReader`'s static `intLE`, `longLE`, and `longBE` to `wvlet.uni.io.codec.Bytes`.
5. Update the docs, `for-ai-agents.md`, the plugin skill (with a version bump), and the ADRs. Keep the npm entry point `@wvlet/uni/parquet`. Make no deprecated aliases.

## Second PR (after #1161)

- Move `ObjectStore` and the S3, GCS, Azure, and HTTP stores to `cloud.storage`.
- Move `SettingStore` and its backends to `wvlet.uni.util.settings`.
- `wvlet.uni.storage` then goes away.

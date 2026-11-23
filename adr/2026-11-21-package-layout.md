# Package layout: group AI code under `wvlet.uni.ai`

## Context

`uni` had `wvlet.uni.ai` (LLM vendor APIs), `wvlet.uni.mcp`, and `wvlet.uni.agent` (one trait, `UiAgentApi`) as separate top-level packages, though all three are for AI agents. `wvlet.uni.mcp` also held `@description` and `JsonSchema`, which `http.openapi` imports, so OpenAPI depended on the MCP package. Plan: `plans/2026-11-21-package-consolidation.md`.

## Decision

- `wvlet.uni.ai` is the home for AI-facing code: `ai.mcp` (MCP server and client) and `ai.agent` (`UiAgentApi`) sit next to the vendor APIs. uni-ui's `wvlet.uni.ui.agent` stays in uni-ui, as `ui.telemetry` does next to `telemetry`.
- `@description` moves to `wvlet.uni.surface` in uni-core. It is metadata Surface captures, read by MCP tools, OpenAPI, and `UiAgentApi`, and an annotation with no dependencies fits uni-core. Readers still match any annotation named `description` with a `value`, so Airframe's keeps working.
- `JsonSchema` (`private[uni]`) moves to `wvlet.uni.json`; it turns Surfaces into JSON Schema for MCP and OpenAPI alike.
- Uni is early, so the moves are breaking with no deprecated aliases in the old packages.

## Consequences

- Imports change: `wvlet.uni.ai.mcp.MCP`, `wvlet.uni.surface.description`, `wvlet.uni.ai.agent.UiAgentApi`.
- A test class named `description` in package `wvlet.uni.surface` now shadows the real one; `AnnotationTest` uses `remark` instead.
- Next steps (cloud credentials out of `storage`, `SettingStore` into `storage`) are in the plan.

## Addendum: cloud credentials in `wvlet.uni.cloud`

`wvlet.uni.storage` held the AWS, Google, and Azure credential providers, SigV4, and RSA signing next to the object stores, and `ai` imported `storage` for Bedrock and Vertex auth.

- `wvlet.uni.cloud` has `AwsCredentials(Provider)` with the profile, SSO, and STS support, `GoogleCredentialsProvider` and its signer, `AzureCredentials(Provider)`, `SigV4`, `Rsa`, and an internal `XmlReader` (STS replies, S3 and Azure listings). `Sha256` (internal) is in `wvlet.uni.util`. Azure Shared Key and SAS signing (`AzureAuth`) stays in `storage`; only Blob Storage uses it.
- Credential errors throw `CloudException` with codes in `CloudErrorCode` (`NoCredentials`, `InvalidCredentials`, `AccessDenied`, `InvalidResponse`, `InvalidConfig`). `StorageException` extends it and `StorageErrorCode` keeps the store-only codes (`NoSuchKey`, `NoSuchBucket`, `WrongRegion`). A store call can therefore fail with a plain `CloudException` when credentials are missing; catch `CloudException` to handle both.

## Addendum: `SettingStore` in `wvlet.uni.storage`

`wvlet.uni.config` held only `SettingStore`, `SettingLocation`, and its backends (`StoreBackend`, `FileStoreBackend`, `InMemoryStoreBackend`, `LocalStorageBackend`, `NodeFileStoreBackend`): local persistence, not configuration. They move to `wvlet.uni.storage` next to the object stores, which leaves `config` free for configuration loading. The Scala.js rule in `2026-10-27-setting-store.md` (no `FileSystem` from `SettingStore` code on Scala.js) applies to that code; the package name has no effect on linking.

## Addendum: `UiAgentApi` in `wvlet.uni.ui.agent`

`wvlet.uni.ai.agent` read as a home for agent loops over `Llm`, but it held only `UiAgentApi`, the tools an agent uses to read and operate a uni-ui screen.

- `UiAgentApi` moves to `wvlet.uni.ui.agent`, the package of uni-ui's `UiAgent`, `AgentRegistry`, and `UiHistory`. One import brings in the trait and its implementation.
- It stays in the `uni` module (`2026-11-13-ui-agent.md`: an Electron main process or a JVM client uses it without uni-ui), so `wvlet.uni.ui.agent` is split across `uni` and `uni-ui`. Neither JVM nor Scala.js checks split packages here, since Uni doesn't use JPMS modules.
- `wvlet.uni.ai.agent` is free for agent code built on `Llm`.

## Addendum: data formats under `wvlet.uni.cloud`

`wvlet.uni.cloud` is the home for cloud-native utilities, not only credentials. Parquet and Arrow were top-level packages, and the Iceberg and Delta code lived inside `parquet`.

- `cloud.parquet` holds the Parquet format, the file reader and writer, `ColumnVector`/`ColumnBatch`, the schema model, filters, and datasets. `Parquet.dataset(root)` still detects Delta and Iceberg tables, and `Parquet.writeDataset` still writes Iceberg tables.
- `cloud.iceberg` holds the Iceberg code (`IcebergTable`, `IcebergWrite`, `IcebergDeletes`, and `Avro`, which only the manifests use). Its public types are `IcebergTableChange`, `IcebergCompactionOptions`, and `IcebergCompaction`. The table operations moved off `Parquet` to a new `Iceberg` object: `Parquet.alterIcebergTable` became `Iceberg.alterTable`, and likewise `expireSnapshots`, `deleteRows`, `updateRows`, `compactTable`, `renameTable`, and `dropTable`.
- `cloud.delta` holds `DeltaLog`. It has no public API yet; `ParquetTableFormat.Delta` stays in `parquet`, and Delta writes will add one.
- `cloud.arrow` holds the Arrow IPC writer.
- Datasets and table formats call each other (datasets plan Iceberg commits; Iceberg lists dataset files), so the internals they share are `private[cloud]`. Cyclic references between packages in one module are fine in Scala. Internals used only inside Parquet stay `private[parquet]`.
- `ColumnVector` is typed by `ParquetField` and the `format` enums, so a shared `columnar` package would take the whole schema model with it. That waits until a second consumer, such as an Arrow reader, needs it.
- The fixed-width reads that the Parquet reader and the Arrow writer share (`intLE`, `longLE`, `longBE`) are `wvlet.uni.io.codec.Bytes`. The `ByteReader` cursor stays in `parquet.io`, because it throws `ParquetException`.
- The npm entry point stays `@wvlet/uni/parquet`; it never named Scala packages.
- Next: the object stores move to `cloud.storage`, and `SettingStore` with its backends moves to `wvlet.uni.util.settings`. Once both have moved, `wvlet.uni.storage` is gone.

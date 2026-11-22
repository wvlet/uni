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

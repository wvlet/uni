# Package consolidation

Several top-level packages in `uni` are small or mix concerns. Uni is early, so the moves are breaking with no deprecated aliases.

## Steps (one `breaking/` PR each)

1. **AI under `wvlet.uni.ai`** (this PR)
   - `wvlet.uni.mcp` → `wvlet.uni.ai.mcp`
   - `wvlet.uni.agent.UiAgentApi` → `wvlet.uni.ai.agent.UiAgentApi` (uni-ui's `wvlet.uni.ui.agent` stays)
   - `@description` → `wvlet.uni.surface.description` (uni-core) and `JsonSchema` → `wvlet.uni.json.JsonSchema`: `http.openapi` uses both, so they are not MCP-specific
2. **Cloud credentials out of `storage`** → `wvlet.uni.cloud`: `AwsCredentials`, `AwsSso`, `AwsSts`, `AwsProfiles`, `SigV4`, `GoogleCredentials`, `AzureCredentials`, `AzureAuth`. `Sha256`/`Rsa` to `util` or `cloud`. `ai` (Bedrock, Vertex) stops importing `storage`.
3. **`SettingStore` into `storage`**: `wvlet.uni.config` → `wvlet.uni.storage` (with its backends), freeing `config` for config loading.

Not moving: `control`, `io`, `text`, `util`, `weaver`.

Each PR updates `docs/guide/for-ai-agents.md`, `plugins/uni/skills/uni/` (with a version bump), and the ADRs that name the package. Old `plans/` files keep the old names.

## Status

- Step 1: PR #1106. Step 2: PR #1107; it also adds `CloudException` (parent of `StorageException`) for credential errors and keeps `AzureAuth` in `storage`.

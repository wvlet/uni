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

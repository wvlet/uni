# UiAgent WebMCP against Chrome's real modelContext

## Goals

- Find out whether `agent.installWebMcp()` works against Chrome's real WebMCP, not only the fake `modelContext` from #905.
- Fix any mismatch, so an agent in Chrome gets usable tool results.
- Decide how a real-Chrome check can run: in CI, or as a documented opt-in.

## Background

- WebMCP spec (CG draft, Nov 2026) and Chrome docs: `document.modelContext.registerTool(tool, { signal })` returns a Promise. `execute(input, { signal })` may return any value. Chrome passes a string result through as-is and JSON-serializes anything else. A rejected `execute` reaches the agent only as "OperationError: Tool execution failed.", without its message.
- Chrome 150 replaced `navigator.modelContext` with `document.modelContext`. The origin trial started in 149, so every Chrome that still matters has `document.modelContext`.
- I probed both browsers over CDP with `--enable-features=WebMCP` on a localhost page (WebMCP needs a secure context):
  - **Chrome 159:** `document.modelContext` exists. Registration, `getTools`, `executeTool`, and abort-to-unregister all work with UiAgent's tool shape (name, description, inputSchema, execute, `annotations.readOnlyHint`).
  - **Chromium 149:** only `navigator.modelContext`, with an older shape (synchronous `registerTool`, JSON-string input). UiAgent correctly ignores it.
- Two real mismatches:
  - `registerTool` reports failures (for example a duplicate tool name) by rejecting its Promise. UiAgent's `try/catch` only catches synchronous throws, so a failed registration becomes an unhandled rejection instead of the intended console warning.
  - `execute` resolves with the MCP `CallToolResult` object. Chrome serializes it, so the agent sees `{"content":[{"type":"text","text":"[{\"ref\":...}]"}]}`. The snapshot JSON ends up double-encoded, which wastes tokens, and the agent has to know MCP's envelope.
- The build's Playwright (1.49) bundles Chromium 131, which has no WebMCP at all. A CI test against real WebMCP first needs a Playwright upgrade. That upgrade moves every browser test, re-baselines the screenshot goldens, and needs a new plugin release.

## Design

Lane: **Standard**. It changes what a WebMCP agent receives from a tool call (the tool surface). Uni's Scala API does not change.

- **Return the tool's text.** `execute` resolves with the text content of the MCP result, which is what Chrome's own examples return. MCP clients still get the full `CallToolResult`. Only the WebMCP bridge unwraps it.
- **Tool errors resolve with their message.** A UiAgent tool error (`isError`, for example a denied or unknown ref) resolves with its error text, not a rejection, because Chrome would replace a rejection's message with a generic one. Protocol errors still reject.
- **Handle async registration failures.** A rejected `registerTool` Promise logs the same console warning as a synchronous throw. A registration error never surfaces as an unhandled rejection.
- **The fake matches Chrome 159.** Its `registerTool` returns a Promise. The tests cover the string result, a tool error, and a rejected registration.
- **Real-browser test, active only where WebMCP exists.** A test in `UiAgentTest` registers tools through the browser's own `document.modelContext` and round-trips `getTools` and `executeTool` on a real uni-ui screen. Without native WebMCP it is skipped, which today includes CI's Chromium 131.
- **CI later, as a follow-up item:** upgrade Playwright to a Chromium ≥ 150 bundle and add `--enable-features=WebMCP` to uni-ui's test launch args. The test above then runs in CI with no further change.
- **Manual check now:** I verify a real uni-ui screen in Chrome 159 over CDP before merging and record the result in the PR. `docs/dom/agents.md` gets a short note on the flag and what the agent receives. The `ui.md` skill reference is updated only if its text covers the result shape.

## Alternatives and Why Not?

- Keep returning the `CallToolResult` object: it works, but double-encodes every snapshot and makes agents unwrap MCP's envelope.
- Reject on tool errors: the agent would see only "Tool execution failed." and could not recover.
- Upgrade Playwright in this PR so CI runs the test: it shifts every browser test and the screenshot goldens, and needs a plugin release. That belongs in its own deps PR.
- Fall back to `navigator.modelContext`: only pre-150 builds have it, with a different shape, and the spec removed it.

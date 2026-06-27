# Multi-layer testing for rich desktop apps (VSCode-style)

Goal: make Uni a foundation for building rich desktop apps that are testable at every
layer, mirroring VSCode's testing strategy. Provide first-class support for four layers:

1. **Unit tests** — pure logic, no DOM/Electron, run on JVM/JS/Native.
2. **UI (component) tests** — render `uni-dom` components in a real browser DOM, simulate
   user interaction (click/keyboard/input), assert on rendered output.
3. **Electron (integration) tests** — exercise RPC-over-IPC and main/renderer wiring.
4. **Plugin tests** — load and test pluggable extensions/contributions in isolation.

## How VSCode does it (reference model)

| VSCode layer        | Tooling                         | What it proves                                  |
| ------------------- | ------------------------------- | ----------------------------------------------- |
| Unit                | Mocha, no `vscode` API, no DOM  | individual functions/classes in isolation       |
| Integration         | `@vscode/test-electron`         | extension API in a real extension-host runtime  |
| UI / Smoke          | Playwright drives real Electron | full user flows, simulated input, screenshots   |
| Extension (plugin)  | activated in a test host        | contributions, activation events, commands      |

Key principle: a **testing pyramid** — many fast unit tests, fewer component/UI tests,
fewest full end-to-end tests. Each layer has ergonomic, purpose-built helpers so authors
write the *cheapest* test that still proves the behavior.

## Current state in Uni (2026-06-27)

- **Unit** — strong. `wvlet.uni.test.UniTest` (AirSpec-style): assertions, async (`Future`/`Rx`),
  property checks, lifecycle hooks, flaky handling, nested tests, JUnit-platform IDE integration.
  Runs on JVM/JS/Native.
- **UI** — partial. `uni-dom` is a full reactive DOM library; `uni-dom-test` runs in real
  headless Chromium (Playwright). But tests can only *construct* and `renderTo`, then read
  `textContent`. **No event simulation** (no click/keyboard/input dispatch), and the mount +
  cleanup boilerplate is copy-pasted in every test.
- **Electron** — `wvlet.uni.electron` RPC-over-IPC transport is unit-tested with *fake* IPC
  objects (`ElectronRPCTest`). The `examples/electron-app` has **zero tests**.
- **Plugin** — no plugin/extension model exists yet.

Gaps map cleanly onto the layers above: UI needs interaction, Electron needs a reusable
harness, plugin needs a model + harness first.

## Deliverables (incremental PRs)

### PR 1 — UI test toolkit (this PR)  ✅ in progress
Ship `wvlet.uni.dom.testing` in the **main** `uni` JS artifact so downstream desktop apps
get it for free:
- `mount(element)` → `Mounted` handle: detached container appended to `document.body`,
  rendered via `DomRenderer`, with `unmount()`/`AutoCloseable` cleanup.
- `fireEvent` — Testing-Library-style event simulation: `click`, `dblclick`, mouse/pointer,
  `input(el, value)`, `change`, `keyDown/keyUp/keyPress`, `submit`, `focus`/`blur`, `custom`.
- Query helpers (`Query` / on `Mounted`): `getByText`/`queryByText`, `getByTestId`,
  `querySelector(All)` wrappers returning `Option`/`Seq`.
- `DomTestSession` — framework-agnostic mount-tracker with `cleanupMounted()`.
- `DomTestSupport` (in `uni-dom-test`) wires `DomTestSession` into `UniTest.after`.
- New tests that actually drive interaction (button click increments a counter, typing into
  an input updates reactive state) to prove the toolkit and close the biggest gap.

### PR 2 — Electron integration test harness
Promote the fake-IPC scaffolding from `ElectronRPCTest` into a reusable
`wvlet.uni.electron.testing` harness (in-memory IPC pair: server + renderer channel) so apps
can integration-test their `CounterApi`-style services without a real Electron runtime. Add
tests to `examples/electron-app` using it.

### PR 3 — uni-test layer ergonomics
Add **test tags** to `UniTest` (e.g. `test("...", tags = Seq("ui"))`) + sbt filtering, so the
four layers can be selected/excluded in CI. Optionally per-test timeouts.

### PR 4 — Plugin model + plugin test harness
Design a minimal plugin/extension contract (activation, contribution registry, isolated
lifecycle) and a `loadPluginForTest(...)` harness. Largest, designed last.

## Notes / decisions
- Toolkit ships in main `uni.js` (no dependency on `uni-test`) so it is reusable in any
  context; only the `UniTest`-coupled `DomTestSupport` mixin lives in test scope.
- Rx propagation in `uni-dom` is synchronous, so `fireEvent` + assertion can be synchronous
  for the common case; async helpers (`findBy*`) added only where needed.
- Follow-up: consider a published `uni-dom-testkit` module so downstream apps can reuse
  `DomTestSupport` directly instead of re-declaring the 3-line trait.

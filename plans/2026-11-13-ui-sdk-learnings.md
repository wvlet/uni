# Ideas from UI SDK for the Uni UI stack

Date: 2026-11-13
Status: proposal. Nothing is implemented yet.

## Background

[UI SDK](https://ui-sdk.dev/) (`vercel-labs/ui`, Apache-2.0, beta) calls itself "a library of
behaviors for agents to build the next generation of user interfaces". It has a framework-free
TypeScript core (`@ui-sdk/core`) and adapters for React, Vue, Svelte, Solid and Web Components.
Its parts are headless and unstyled.

This note lists what is new in its approach, how each idea maps onto uni-dom / uni-ui, and which
ideas are worth adopting. Sources: the site's home page, Get Started, Introduction, Behaviors,
Style a Component, Handle Changes, Testing, the Connect an Agent pages (Guide, Permissions,
Reference, Observe Changes, History and Undo), WebMCP, Dark Mode, `llms.txt`, and the GitHub
README.

## What is unique in UI SDK

1. **The unit is a behavior, not a component.** There are 50+ composable behaviors:
   `createRovingFocus`, `createTypeahead`, `createSelection`, `createReorder`, `createPopup`,
   `createStickToBottom`, `createInputHistory`, `createFocusRecovery`, `announce`, and more. A
   `Select` is just one composition of them. An agent can use the same parts to build a combobox,
   a star rating, or "a new component we haven't thought of yet". The site's argument: agents
   should compose tested behaviors rather than hand-write keyboard, focus and ARIA logic.
2. **Agent bridge: agents operate the UI through semantics, not screenshots.**
   - `agent.snapshot()` returns compact JSON for every live component:
     `{ref:"c1", component:"input", label:"Project name", value:"Apollo", actions:["setValue","clear"]}`,
     plus `items`, `disabled`, `errors`, `row` (nearby row text), and `source` (`file:line` in dev
     builds).
   - `agent.act({steps:[{ref:"Project name", action:"setValue", args:["Atlas"]}]})` calls the
     component API directly instead of simulating input. A `ref` can be an id, a label, row text or
     a kind.
   - `agent.query({text, within, kind})` reads large lists without opening them.
   - The same three tools are exposed through **WebMCP** (`document.modelContext`).
   - Their self-published benchmark against browser tools: 69% fewer tool calls and 83% less text
     read.
3. **The page sets the permission model.**
   - An `allow(step)` callback can refuse a step before it changes anything.
   - `data-agent-confirm="..."` makes a step need a person to approve it.
   - `data-agent-hidden` hides components from the agent.
   - `data-agent-secret` redacts a value. Password, card and one-time-code fields are redacted
     automatically.
4. **Changes record where they came from.** Every change carries `ChangeDetails`:
   - `reason` names the interaction: `"press"`, `"escape-key"`, `"item-press"`, and so on.
   - `source` is one of `mouse | touch | pen | keyboard | virtual | programmatic | agent`.
   - `cancel()` refuses the change.

   This one mechanism supports analytics, focus decisions, permission checks for agent changes,
   and `agent.subscribe`. With `subscribe`, the app can collect what the human changed since the
   agent's last turn.
5. **History and undo built on the same change stream.**
   - `createHistory(agent)` records changes, and `describeEntry` turns each into a sentence such as
     "Agent set Project name to Atlas".
   - `undoSource("agent")` reverts only what the agent changed.
   - `stageChanges` applies suggested values that can be reverted together.
6. **Accessibility checks in the testing kit.**
   - `checkPattern(root, "tabs")` checks against the WAI-ARIA APG, stricter than the APG in places.
   - `checkDirection` tests RTL.
   - `checkServer` checks SSR purity.
   - `mount(...)` exposes `changes`, `announced` and `settle()`.
7. **Docs ship with the package, matched to its version.**
   - `node_modules/@ui-sdk/*/docs/llms.txt` and `core.md`, plus a skill at
     `@ui-sdk/core/skills/ui-sdk/SKILL.md`.
   - `llms.txt` is split by area: core, agents, guides, and one per framework.
   - Every page has a Markdown copy. The Quick Start prompt tells the agent to add a note to
     AGENTS.md.
8. **One styling contract.**
   - Each part has `data-slot="<primitive>-<slot>"`.
   - State appears as data or ARIA attributes such as `data-copied` and `aria-pressed`.
   - Dynamic values go into CSS variables.
   - No generated class names.

## Where Uni already stands

| UI SDK idea | Uni today |
|---|---|
| Headless behaviors | `uni-ui/.../base/` already has `RovingFocus`, `Dismiss`, `FocusScope`, `Anchored`, `Windowing`, `Fuzzy`, `TreeModel`, `DockModel`, but they are internal helpers with no catalog or docs |
| Unstyled parts | uni-ui is styled, using plain CSS in `@layer uni` and `--uni-*` tokens (ADR 2026-11-05). That is a deliberate choice, and apps can override it |
| State attributes | `data-state` on overlays, BEM modifiers (`uni-button--primary`). There is no `data-slot` convention |
| Change events | Callbacks are `() => Unit` / `A => Unit` (`onClick`, `onSelect`, `onDismiss`). They carry no reason, no source and no cancel |
| Controlled/uncontrolled | Covered by `Rx` / `RxVar`. Nothing to adopt |
| Agent bridge | None. But Uni has MCP (`wvlet.uni.mcp.MCPServer`), typed RPC over Electron IPC, and `Surface` for schemas. UI SDK has no equivalents to these |
| Testing | `DomTestSupport` with `Query`, `fireEvent`, `AwaitValue`, run in Playwright Chromium. There are no ARIA-pattern checks |
| Agent docs | `llms.txt` / `llms-full.txt`, the `uni` skill, `for-ai-agents.md`. But the skill has **no UI reference file**, and the Scala.js UI snippets are **not compile-checked** (`docSnippets` depends on `uni.jvm` only) |
| Color scheme | `ColorScheme` + `Storage.local`. Need to check whether it applies before first paint |

## Recommendations

Ordered by value for the cost.

### 1. Make the UI stack readable by agents (docs only, low risk)
- Add `plugins/uni/skills/uni/references/ui.md` covering:
  - the component catalog, with one builder example each;
  - the token list;
  - the `data-state` and `data-slot` hooks;
  - testing with `DomTestSupport`.
- Compile-check Scala.js snippets. Add a JS variant of `docSnippets` that depends on `uni.js` and
  `ui`, so `docs/dom/*.md` and the new reference stay correct.
- Split `llms.txt` by area: core, rx, http/rpc, dom/ui, agents. An agent working on UI then loads
  only the UI part.

### 2. `ChangeDetails` on uni-ui callbacks (small API change, and the base for 3–5)
- Add an overload alongside the current callbacks, e.g. `onSelect((value, d: ChangeDetails) => ...)`.
  Keep the existing `A => Unit` form.
- `ChangeDetails(reason: ChangeReason, source: ChangeSource)` with a `cancel()` method. The
  `ChangeSource` enum is Pointer, Keyboard, Programmatic or Agent.
- Uni's typed `Rx` gives us one advantage here: the source can travel with the update instead of
  needing `withSource` to keep it across an `await`.

### 3. Publish the behaviors layer
- Promote `uni-ui/base` (or move it to `wvlet.uni.dom.behavior`) to a documented public API with a
  catalog page. Agents can then build new widgets out of it.
- Add the behaviors our own apps need first. uni-harness needs most of these for chat and
  terminal:
  - `StickToBottom`: follow the stream, but keep the reader's place when they scroll up.
  - `InputHistory`: ArrowUp/Down recall, like a terminal.
  - `Typeahead`.
  - `Selection`: multi-select and ranges.
  - `FocusRecovery`.
  - `Announce`: an ARIA live region.
  - `Reorder`: pointer and keyboard. This can build on the existing `PointerDrag` / `DragDrop`.
- Add a `data-slot` attribute to every uni-ui part. It is a stable hook for CSS, tests and the
  agent bridge, and it removes the need to guess BEM class names.

### 4. `UiAgent`: a typed agent bridge (the most distinctive idea, and Uni can do it better)
- A registry of mounted uni-ui components. Each one reports `ref`, kind, accessible label, current
  value, items, disabled state, and the actions available now.
- Uni-specific advantages:
  - **Typed actions.** Argument types come from the component's value type or from `Surface`
    (e.g. a `Select[Region]` lists its items). Agents get a JSON Schema per action instead of the
    bridge guessing from method names such as `set*`/`open`/`toggle`.
  - **Transport we already have.**
    - Electron: the main process runs `MCPServer` and forwards `snapshot`/`act`/`query` to the
      renderer over the existing Electron RPC channel. Claude Code (or any MCP client) can then
      drive a running uni-harness without screenshots.
    - Browser: WebMCP registration where `document.modelContext` exists.
  - **Tests.** `DomTestSupport` can use the same `snapshot`/`act` API, so tests and agents operate
    the UI the same way.
- Copy UI SDK's permission rules:
  - an `allow` policy;
  - `data-agent-confirm`, `data-agent-hidden` and `data-agent-secret` (or typed modifiers such as
    `Button(...).agentConfirm("Delete project?")`);
  - automatic redaction of password fields;
  - changes the agent makes are tagged `ChangeSource.Agent`, which needs step 2.
- `subscribe(change => ...)` gives the "what did the human change since your last turn" feed.
  Write an ADR before building this. It is a new public surface.

### 5. Accessibility pattern checks in `DomTestSupport`
- `checkPattern(root, AriaPattern.Tabs | Menu | Listbox | Dialog | Tree | ...)`. Run it on every
  uni-ui component in its own tests, then ship it so apps can check their custom widgets.

### Later / maybe
- History and undo from the change stream: `undoSource(Agent)`, `stageChanges` for "agent
  suggests, human accepts". Worth it once step 4 exists.
- Preferences applied before first paint, to avoid a color-scheme flash in the web build.

### Not adopting
- **Multi-framework adapters / framework-free primitive DSL.** Uni has one renderer (uni-dom).
  Scala builder classes plus `Rx` already give the predictability the TS `definePrimitive` object
  gives.
- **Unstyled-only components.** uni-ui stays styled through tokens (ADR 2026-11-05). The
  behaviors layer (step 3) covers the headless use case.

## Proposed PR sequence

1. `docs/` — UI skill reference, JS `docSnippets`, split `llms.txt`.
2. `feature/` — `ChangeDetails` + `data-slot` across uni-ui.
3. `feature/` — public behaviors catalog, plus StickToBottom / InputHistory / Typeahead /
   Selection / FocusRecovery / Announce.
4. `feature/` — `checkPattern` in the dom testing kit.
5. ADR + `feature/` — `UiAgent` (snapshot / act / query / subscribe, permissions), the MCP bridge
   in uni-harness, and WebMCP.

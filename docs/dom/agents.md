---
description: Let an AI agent read and operate a uni-ui screen without screenshots — UiAgent snapshots, actions, permissions, and confirmations, served as MCP tools, WebMCP, or from an Electron app.
---

# Agents

`UiAgent` lets an AI agent read and operate the components on screen, without screenshots. It
is off until the app installs it. A snapshot lists each component with a `ref`, its kind,
label, value, items, and the actions it allows now. Each action carries a JSON Schema for its
value: a select's `select` action lists the option labels as an `enum`.

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions
import wvlet.uni.ui.agent.{AgentPolicy, UiAgent}

val agentRegion = Rx.variable("tokyo")

val form = div(
  Select(agentRegion, options = Seq("tokyo", "oregon"), optionLabel = s => s).withLabel("Region"),
  Button("Delete project").danger(UiAgent.confirm("Delete the project?")),
  Input(Rx.variable("sk-123")).withLabel("API key")(UiAgent.secret),
  div(UiAgent.hidden, Button("Billing"))
)

val agent = UiAgent.install(AgentPolicy.allowAll)
agent.snapshot()                                      // Seq[UiNode]: ref, kind, label, ...
agent.act("select:region", "select", Some("oregon")) // Rx[ActResult]
agent.changes(since = 0)                              // what people changed, and a cursor
```

- **Actions.** `press` (button, menu item), `setText` (text field), `select` (select, tabs,
  list, tree), `sort` (table), `move` (a list or table with `.withReorder`, as `"Review to 1"`),
  `open`/`close` (menu), and `close` (dialog). The agent's change
  goes through the component's `onChange` with `ChangeSource.Agent`, so a handler can cancel
  it, and `act` then reports `canceled`.
- **Policy.** `AgentPolicy.allowAll` (the default), `AgentPolicy.readOnly`, or
  `AgentPolicy.allow(t => t.kind != "button")`. Snapshots list only allowed actions, and a
  disabled component lists none.
- **Marks.** `UiAgent.ref("name")` sets a stable ref (otherwise `kind:label`).
  `UiAgent.hidden` removes a subtree; so do `hidden`, `inert`, and `aria-hidden`.
  `UiAgent.secret` shows the value as `<redacted>`, as every password field does.
  `UiAgent.confirm(message)` makes the agent wait while the human allows or denies the action
  in a dialog. Every other agent action is refused until they answer.
- **Item labels.** A list, tree, or table names its rows by `.withRowLabel(label)` in
  snapshot items, the `select` action, and the change feed. Without one, a list or tree falls
  back to its `.withTypeahead(label)` text, then every component to the key's `toString`.
  `withRowLabel` doesn't turn on typeahead, and typing still matches the typeahead text.
  Labels follow the rows: replaced rows and newly opened tree rows get theirs at the next
  snapshot.

  ```scala
  case class Doc(id: Int, title: String)
  val docs = Rx.variable(Seq(Doc(1, "Plan"), Doc(2, "Notes")))

  VirtualList(docs, rowHeight = 24, key = _.id, render = d => span(d.title))
    .withTypeahead(_.title)
    .withRowLabel(d => s"${d.title} (#${d.id})") // agents see "Plan (#1)"
  ```
- **Changes.** `agent.changes(cursor)` returns what people and app code changed since the
  cursor, without the agent's own changes, so an agent can catch up at the start of its turn.
  `agent.subscribe(c => ...)` sees every change as it happens.
- **Transports.** `agent.api` is the same API as JSON text (`wvlet.uni.agent.UiAgentApi`).
  `MCPServer().withTools[UiAgentApi](agent.api)` serves it as MCP tools, and
  `agent.installWebMcp()` registers it with the browser's WebMCP (`document.modelContext`)
  where the browser offers it. In Electron, `ElectronRenderer.serve(RPCRouter.of[UiAgentApi](agent.api))`
  serves it to the main process, which can expose it as an MCP endpoint
  (see [Calling the renderer from the main process](../http/electron#calling-the-renderer-from-the-main-process)).


## History and undo

`UiHistory` records the changes components make to app state, by people, app code, and agents,
so the app can show them and revert what the agent did. A stage collects the agent's
suggestions for the human to accept or revert together.

```scala
import wvlet.uni.ui.agent.{UiHistory, UndoStatus}

val history = UiHistory.record()
val stage   = history.stage("Suggested region")   // the agent's next changes join it
agent.act("select:region", "select", Some("tokyo"))
stage.entries.map(_.describe)                      // Seq("Agent set Region to tokyo")
stage.revert()                                     // or stage.accept()
val results = history.undoSource(ChangeSource.Agent)
results.filter(_.status == UndoStatus.Conflict)    // agent changes a person edited since
history.close()
```

- **Recorded.** Each change to app state a component writes through its gate: `selected`,
  `value`, `sort`, `expanded`, `order`, `open`, and so on, with its ref, label, before and after
  text, reason, and source. Button presses, menu-item actions, and other app callbacks are not
  changes, so they are not recorded and cannot be undone.
- **Undo.** `undoSource(ChangeSource.Agent)` and `undo(entry)` go newest first and write the
  previous value back through the component's `onChange` with `ChangeReason.Undo`, so the app can
  cancel it. Each `UndoResult` says `reverted`, `conflict` (the value changed since, so the newer
  value stays), `canceled`, `missing` (the component is not mounted; nothing is written),
  `unsupported`, or `skipped` (already undone or accepted).
- **Not undone.** `open` (overlay visibility), secret and password values, and components without
  an agent binding report `unsupported`. Secret values read `<redacted>` and are never kept.
  Components inside `UiAgent.hidden` are not recorded.
- **Stages.** One stage is open at a time, and only agent changes join it. `revert()` keeps the
  edits a person made meanwhile; `accept()` keeps the changes, and `undoSource` skips them later.
- **No loops, no agent replay.** A revert is not recorded as a new change; other listeners see it
  as a `Programmatic` change with reason `undo`. History is app-side only: `UiAgentApi` has no
  undo or redo, so an agent cannot use it to get around the policy or a confirmation.

## Driving an Electron app over MCP

[`examples/uni-harness`](https://github.com/wvlet/uni/tree/main/examples/uni-harness) shows the
whole path. `pnpm dev:agent` starts the app with `--agent-bridge`: the renderer installs `UiAgent`
and serves `agent.api` to the main process with `ElectronRenderer.serve`, and the main process
serves it as MCP tools (`snapshot`, `query`, `act`, `changes`) on `http://127.0.0.1:<port>/mcp`. It
prints the URL, a bearer token, and a `claude mcp add` line to register the endpoint with an agent.
The endpoint listens only on 127.0.0.1, rejects requests without the token, and picks a new port and
token on each launch; plain `pnpm dev` starts no endpoint.

## Testing with UiAgent

Tests can drive a view the same way an agent does: mount it with [`DomTestSupport`](./testing), then call `snapshot()` and `act`.

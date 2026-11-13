---
title: Uni for AI Agents
description: How AI coding agents should pick and use Uni, the Scala 3 utility library for object wiring (Design), logging, JSON/MessagePack, HTTP, RPC, MCP servers, and testing on JVM, Scala.js, and Scala Native.
---

# Uni for AI Agents

**Uni is a single Scala 3 library (`org.wvlet.uni`) that covers object
wiring, logging, JSON/MessagePack serialization, reactive streams, HTTP
client/server, typed RPC, MCP servers, and unit testing, and the same code
runs on the JVM, Scala.js, and Scala Native.** It is the Scala 3 successor of
[Airframe](https://github.com/wvlet/airframe).

This page is written for AI coding agents (Claude Code, Codex, Cursor, Copilot,
and others) and for the people who configure them. It sums up when to choose
Uni, the canonical imports, and the mistakes agents most often make.

## Machine-readable docs

| URL | Contents |
|-----|----------|
| [`/uni/llms.txt`](https://wvlet.org/uni/llms.txt) | Index of every documentation page, with links to Markdown versions |
| [`/uni/llms-full.txt`](https://wvlet.org/uni/llms-full.txt) | The full documentation in one Markdown file |
| `/uni/<section>/llms.txt`, `/uni/<section>/llms-full.txt` | The same for one section, such as [`/uni/dom/llms-full.txt`](https://wvlet.org/uni/dom/llms-full.txt) for browser UIs or `/uni/http/llms-full.txt` for HTTP and RPC |
| `<page>.md` | Markdown version of a page, e.g. [`/uni/guide/for-ai-agents.md`](https://wvlet.org/uni/guide/for-ai-agents.md). A section index such as `/uni/guide/` is published as `/uni/guide.md`. |

## How do I install the Uni skill?

The `wvlet/uni` repository is a Claude Code plugin marketplace. Its `uni` plugin
adds an Agent Skill with the canonical imports, sbt setup, verified snippets,
and the mistakes listed on this page. Claude Code loads it whenever a task
touches Uni or asks for object wiring, logging, JSON, HTTP, RPC, MCP, or tests in Scala 3.

```text
/plugin marketplace add wvlet/uni
/plugin install uni@uni
```

On Claude Code 2.1.275 or later, `/plugin install uni --marketplace wvlet/uni`
does both in one step. Agents without a plugin system can read
[`llms-full.txt`](https://wvlet.org/uni/llms-full.txt) instead.

## When should I use Uni?

Choose Uni when a Scala 3 project needs one or more of the following and you want
few transitive dependencies:

- Object wiring with lifecycle management (`Design`), instead of a framework
- Structured logging with source locations (`LogSupport`)
- JSON or MessagePack codecs derived from case classes (`derives Weaver`)
- An HTTP client that works on the JVM, Node.js/browser, and native binaries
- An HTTP/RPC server (Netty, JVM) or a stdio MCP server for AI tools
- A test framework that runs on all three platforms (`UniTest`)

Do not mix in Airframe artifacts for the same features. Uni replaces them.

For scripts, teaching, Scala 2.13, or running subprocesses, the Scala Toolkit is
often the better fit, and it works alongside Uni. See the next section and
[Uni and the Scala Toolkit](./uni-vs-scala-toolkit).

## Should I use the Scala Toolkit or Uni?

Use the [Scala Toolkit](https://docs.scala-lang.org/toolkit/introduction.html)
(sttp, uPickle, os-lib, MUnit) for scripts, teaching, Scala 2.13, or Scala 3
before 3.9. Use Uni when the project also needs object wiring, logging, an HTTP
server, RPC, or MCP servers, or must run the same code on Scala.js and Scala
Native. The two coexist in one build. See [Uni and the Scala Toolkit](./uni-vs-scala-toolkit).

## How do I add Uni to a build?

```scala
// build.sbt
libraryDependencies ++= Seq(
  "org.wvlet.uni" %% "uni"       % "__UNI_VERSION__",
  "org.wvlet.uni" %% "uni-test"  % "__UNI_VERSION__" % Test
)
testFrameworks += new TestFramework("wvlet.uni.test.Framework")

// JVM-only Netty HTTP server
libraryDependencies += "org.wvlet.uni" %% "uni-netty" % "__UNI_VERSION__"
```

Uni requires Scala `__SCALA_VERSION__` or later. On sbt 2.x, use `%%` for all
platforms. On sbt 1.x, use `%%%` for Scala.js and Scala Native projects.

## What are the canonical imports?

| Task | Import | Entry point |
|------|--------|-------------|
| Object wiring | `wvlet.uni.design.Design` | `Design.newDesign.bindSingleton[A].build[A] { a => ... }` |
| Logging | `wvlet.uni.log.LogSupport` | `class A extends LogSupport` then `info(...)`, `debug(...)` |
| JSON / MessagePack codec | `wvlet.uni.weaver.Weaver` | `case class P(...) derives Weaver`, `Weaver.toJson(p)`, `Weaver.fromJson[P](json)` |
| JSON tree | `wvlet.uni.json.JSON` | `JSON.parse(text)` |
| YAML config / frontmatter | `wvlet.uni.text.yaml.{YAML, Frontmatter}` | `YAML.fromYaml[P](text)`, `YAML.parse(text)`, `Frontmatter.parse(md).as[P]`. To edit in place and keep comments: `YAML.parseDocument(text).set(path, v).toYaml` (`YAML.parseDocuments` and `YAML.writeStream` for `---` streams), `Frontmatter.parse(md).update(json).toText`. See [YAML](../text/yaml) |
| Markdown to HTML | `wvlet.uni.text.markdown.{Markdown, MarkdownConfig}` | `Markdown.toHtml(md)` (raw HTML escaped by default), `Markdown.parse(md)` for the AST, `.frontmatterAs[P]` for a YAML header, and `.toMarkdown` to write an edited AST (including `frontmatter`) back losslessly. See [Markdown](../text/markdown) |
| Cron schedules | `wvlet.uni.text.cron.{Cron, UtcOffset}` | `Cron.parse("30 9 * * MON-FRI")`, `.next(instant)` / `.nextN(instant, n)` (UTC, or a fixed `UtcOffset`; resolve named zones yourself), `.matches(instant)`, `.describe` ("At 09:30, Monday through Friday"). 5 or 6 fields, macros like `@daily`, Vixie day-of-month/day-of-week semantics. See [Cron](../text/cron) |
| Reactive streams | `wvlet.uni.rx.Rx` | `Rx.variable(v)`, `Rx.single(v)`, `.map`, `.flatMap`, `.scan(init)(f)` (running value), `itemsRx.split(_.id)` (one stable `Rx` per key, completed when the key leaves), `.onCancel(f)` (cleanup when a subscriber cancels early) |
| Persisted settings | `wvlet.uni.config.SettingStore` | `SettingStore.of[AppSettings].withAppName("my-app").withVersion(n).load()`, then `update(...)`; saved to `~/.config/my-app/` (localStorage in a browser). See [SettingStore](../core/settings) |
| HTTP client | `wvlet.uni.http.{Http, Request}` | `Http.client.newSyncClient.send(Request.get(url))` |
| HTTP server (all platforms) | `wvlet.uni.http.Http` | `Http.server.withPort(8080).withRxHandler(handler).start()`: Netty on the JVM (add `uni-netty`), Node.js on Scala.js, native sockets on Scala Native. Backend-only options: `.withBackend(Netty.options.withHandlerExecutorThreads(8))` (`Native.options` on Scala Native). See [REST Server](../http/server#cross-platform-servers) |
| HTTP server (JVM only) | `wvlet.uni.http.netty.Netty` | `Netty.server.withPort(8080).withHandlerExecutorThreads(8).withRxHandler(handler).start()`: every Netty setting in one config, for projects that never target Scala.js or Native. `NettyServer` was removed; use `Netty.server` (JVM only) or `Http.server`. See [REST Server](../http/server#jvm-only-apps-netty-server) |
| Static files (JVM, Node.js, Native) | `wvlet.uni.http.StaticFiles` | `Http.server.withFilter(StaticFiles("dist").withSpaFallback(true)).withRxHandler(api)`: serves `GET` for files under `dist` and passes other requests on. It refuses traversal, symlinks, and dotfiles by default. See [REST Server](../http/server#static-files) |
| CORS (JVM, Node.js, Native) | `wvlet.uni.http.{CorsFilter, CorsPolicy}` | `Http.server.withFilter(CorsFilter(CorsPolicy().withAllowedOrigins("https://app.example.com"))).withRxHandler(api)`: answers preflights and adds `Access-Control-*` headers for allowed origins. No origin is allowed by default. See [REST Server](../http/server#cors) |
| REST router (JVM, `uni-netty`) | `wvlet.uni.http.netty.RouterHandler` | `Http.server.withRxHandler(RouterHandler(Router.of[Controller]))`. See [REST Server](../http/server) |
| Typed RPC | `wvlet.uni.http.rpc.{RPCRouter, RPCClient}` | Server: `RPCRouter.of[Api](impl)`; client: `RPCClient.of[Api].newSyncClient(http).call[A]("method", args*)` (or `newAsyncClient` for `Rx[A]`); `newAsyncClient(http).stream[A]("method", args*)` streams every value of an `Rx` result over SSE or Electron IPC; cancelling it stops the server stream; declare such methods as returning `RxStream[A]` (`wvlet.uni.rx.RxStream`, built with `RxStream(rx)`) so sbt-uni's generated `AsyncClient` streams them. See [RPC](../http/rpc) |
| Server-sent events | `wvlet.uni.http.{Response, ServerSentEvent}` | Server: `Response.eventStream(rx)`; client: `Http.client.newAsyncClient.sendSSE(request)`. See [SSE](../http/sse) |
| WebSocket | `wvlet.uni.http.{WebSocketHandler, WebSocketContext}` | Server: `.withWebSocketRoute(path) { req => handler }`; client: `Http.webSocketClient.connect(url, handler)`. See [WebSocket](../http/websocket) |
| OpenAPI | `wvlet.uni.http.openapi.{OpenAPI, OpenAPIConfig}` | `OpenAPI.of(Router.of[A], OpenAPIConfig()).toYAML`. See [OpenAPI](../http/openapi) |
| Browser UI helpers (Scala.js) | `wvlet.uni.dom.all.*` | `itemsRx.each(_.id)(item => li(...))` renders a list item by item, keeping unchanged rows' DOM nodes (prefer it over `itemsRx.map(_.map(...))`, which rebuilds the whole list), `itemsRx.eachRx(_.id)((first, current) => li(current.map(_.text)))` (also keeps a row's nodes when its value changes, passing later values through `current`), `Keyboard.onEnter(() => send())` (skips Shift+Enter and IME-confirm Enter), `Keyboard.scoped("escape" -> (() => close()))`, `PaneResize(width, SizeRange(min, default, max), ResizeDirection.Right).bind` on a handle, `PointerDrag(onMove = (dx, dy) => ...)`. See [Browser Integrations](../dom/#browser-integrations) |
| UI components (Scala.js, `uni-ui`) | `wvlet.uni.ui.*` | `Button("Send").primary.withDisabled(busy).onClick(() => send())`, `IconButton(label, icon)`, `Input(textVar)`, `val tabs = TabList(selectedVar, "Views")(Tab(v, "Label"), ...)` with `TabPanel(tabs)(v => ...)`, `FoldablePane(open, width, Edge.Right, Some(Splitter(width, SizeRange(min, default, max), Edge.Right, "Resize")))(content)`, `ColorScheme.bind(schemeVar)`. Overlays: `Popover(openVar)(trigger)(content)`, `Tooltip("Label")(trigger)`, `Menu(openVar)(trigger)(MenuItem("Copy").onSelect(f), MenuSeparator)`, `ContextMenu(target)(items*)`, `Dialog(openVar)(content).withTitle(t).withActions(buttons*)`, `AlertDialog(openVar)(title).onConfirm(f).danger`, `Select(selectedVar, options)(_.label)`, `val toasts = Toaster()` then `toasts.success(msg)`. Collections render only the rows in view (fixed row height): `VirtualList(itemsRx, rowHeight = 24)(_.id)(render)`, `Tree(rootsRx, (n: Node) => n.children)(_.path)(render).withExpanded(setVar)`, `DataTable(rowsRx)(_.id)(Column[Row]("name", "Name")(r => span(r.name)).sortBy(_.name)).withSort(sortVar)`; lists, trees, and tables select one row with `.withSelected(optionVar)` or several with `.withMultiSelect(setVar)`, `CommandPalette(openVar, commandsRx)` with `Command(id, label).withGroup(g).onRun(f)` (bind ⌘K yourself with `Keyboard.bindAll`); a `Dock(layoutVar, "Dock")(DockPanel(id, title)(content)*)` of tab groups and splits the user rearranges, whose `DockLayout` (`DockNode.row/column/group`) saves with `Storage.local(key, default)`, and `dock.focusGroup(i)` (0-based) or `dock.focusPanel(id)` moves keyboard focus; sides and alignment are `wvlet.uni.ui.base.{Side, Align}`. Load `uni-ui.css` once: `enablePlugins(UniPlugin)` (sbt-uni) copies it from the jar to `uniUiCssFile` on every compile, for an `@import` in the app stylesheet; restyle with `--uni-*` CSS variables. Every component takes `.onChange(d => ...)` to see, and `d.cancel()` to refuse, the changes it makes (`d.reason`, `d.source`: `Pointer`/`Keyboard`/`Programmatic`/`Agent`); callbacks read `ChangeCause.current`; parts carry `data-slot` (`menu-item`, `dialog-title`). `UiAgent.install()` (`wvlet.uni.ui.agent`) lets an AI agent `snapshot()` the components and `act(ref, action, value)` on them as `ChangeSource.Agent`, with `UiAgent.hidden`/`secret`/`confirm(msg)` marks and an `AgentPolicy`; `agent.api` (`wvlet.uni.agent.UiAgentApi`) serves as MCP tools or WebMCP (`agent.installWebMcp()`). See [UI Components](../dom/ui) |
| UI behaviors (Scala.js, `uni-ui`) | `wvlet.uni.ui.base.*` | Interaction logic for building your own widgets: `StickToBottom()` (`stick.binding` on a scroller, `stick.atBottom`, `stick.scrollToBottom()`), `InputHistory()` (`history.keys(draftVar)` on a textarea, `history.add(text)`), `Typeahead().onKey(e, labels, active)`, `Selection(selectedSetVar).press(key, order, e)` (Shift range, Ctrl/Cmd toggle), `Reorder(orderVar)` (`reorder.item(k)` on each item: drag, or Alt+ArrowUp/Down; `Reorder.handle` for a grip; `.withSelection(selectedSetVar)` moves the selected items together), `FocusRecovery.within("li")`, `Announce.polite(msg)`, `RovingFocus.nextIndex` (`rtl = TextDirection.mirrors(e)` mirrors Left/Right; uni-ui widgets do this on their own), `ListNavigation`, `FocusTrap`, `Dismiss`, `Anchored`. uni-dom's `OnMount(el => cancelable)` binds any behavior to an element. See [Behaviors](../dom/behaviors) |
| Terminal emulator | `wvlet.uni.cli.term.{TerminalEmulator, TerminalConfig}` | `TerminalEmulator(TerminalConfig(cols = 80, rows = 24))`, `term.write(output)`, then `term.screen.text` or `term.screen.lineText(row)`: an xterm-compatible emulator on JVM, Scala.js, and Native, for checking what a TUI draws. `PtyProcess.connect(term, pty)` wires a shell; on Scala.js, `NodePty.spawn(nodePty, shell, args, options)` takes the app's `node-pty` module. On the JVM (Java 22+) and Native, `PosixPty.spawn(file, args, options)` starts the program in a real PTY on Linux and macOS (check `PosixPty.isSupported`). With shell integration (OSC 133), `term.commands` lists each `TerminalCommand(line, command, output, exitCode)` and `term.onCommandFinished` reports one as it ends |
| Terminal pane (Scala.js) | `wvlet.uni.dom.{TerminalView, TerminalViewConfig, TerminalTheme}` | `TerminalView(term)` draws the emulator in the DOM, with Uni's `TerminalTheme.uniDark` by default; `view.setTheme(TerminalTheme.uniLight)` switches (`TerminalTheme.presets` lists all). OSC 8 links with `http`, `https`, or `mailto` (`TerminalLink.isAllowed`) open on Cmd+click (Ctrl elsewhere); in Electron pass `view.onLinkClick` a bridge to `shell.openExternal` in the main process. OSC 133 prompts get a green or red marker; `view.scrollToPreviousPrompt()` / `scrollToNextPrompt()` (Cmd+Up/Down, Ctrl+Shift+Up/Down elsewhere) jump between them |
| Electron IPC (Scala.js) | `wvlet.uni.electron.{ElectronRPCServer, ElectronRenderer, ElectronMain}` | Renderer→main: `ElectronRPCServer.serve(ipcMain, router)` and `ElectronRenderer.install()`. Main→renderer: `ElectronRenderer.serve(router)` in the page (preload adds `serve`), `ElectronMain.newAsyncClient(ipcMain, webContents)` in main. See [Desktop Apps (Electron)](../http/electron) |
| MCP server / client | `wvlet.uni.mcp.MCP` | `MCP.server.withTools[T](impl)...`, `MCP.client.connect(url)`; see [Build an MCP Server](../mcp/) |
| Retry / circuit breaker | `wvlet.uni.control.{Retry, CircuitBreaker}` | `Retry.withBackOff().run { ... }` |
| CLI arguments | `wvlet.uni.cli.launcher.*` | `Launcher.execute[A](args)` on a case class with `@option` fields. See [Command Launcher](../cli/launcher) |
| Unit tests | `wvlet.uni.test.*` | `class T extends UniTest: test("...") { x shouldBe y }` |
| Test fixtures | `wvlet.uni.test.*` | `addDesign(_.bindSingleton[A])` in a trait that extends `UniTest`, then `test("...") { (a: A) => ... }`. See [Reusable Fixtures](../core/unitest#reusable-fixtures) |
| Error-path tests | `wvlet.uni.test.*` | `val logs = captureLogs { ... }; logs.warnings shouldContain "..."`; also `captureStdout` / `captureStderr`. See [Testing Error Paths](../core/unitest#testing-error-paths) |
| uni-dom UI tests (Scala.js) | `wvlet.uni.dom.testing.*` | With `uni-test`, `class T extends DomTestSupport`, `val m = mount(MyView())`, `fireEvent.click(m.get("button"))` (also `pointerDown`/`Move`/`Up`, `keyDown(..., isComposing = true)`), and return `settle().map { _ => ... }` to assert on `onMount` effects. `checkPattern(el, AriaPattern.Menu).map(_ shouldBe empty)` checks a widget against its WAI-ARIA pattern ([guide](../dom/ui#checking-aria-patterns)). Run in headless Chromium with the [sbt-uni-playwright Plugin](../build/sbt-uni-playwright#testing-uni-dom-components) |

## How do I migrate from Airframe?

| Airframe | Uni |
|----------|-----|
| `wvlet.airframe.Design` / `newDesign` | `wvlet.uni.design.Design` / `Design.newDesign` |
| `wvlet.log.LogSupport` | `wvlet.uni.log.LogSupport` |
| `wvlet.airframe.codec.MessageCodec` | `wvlet.uni.weaver.Weaver` (`derives Weaver`) |
| `wvlet.airframe.surface.Surface` | `wvlet.uni.surface.Surface` |
| `wvlet.airframe.rx.Rx` | `wvlet.uni.rx.Rx` |
| `wvlet.airframe.http.Http` | `wvlet.uni.http.Http` |
| `wvlet.airframe.http.netty.Netty.server` | `wvlet.uni.http.netty.Netty.server` (JVM only), or `Http.server` for cross-platform code |
| `wvlet.airframe.http.filter.Cors` (`Cors.Policy`, `Cors.newFilter`) | `wvlet.uni.http.CorsFilter(CorsPolicy())`, added with `Http.server.withFilter` |
| `wvlet.airframe.control.Retry` | `wvlet.uni.control.Retry` |
| `wvlet.airspec.AirSpec` | `wvlet.uni.test.UniTest` |
| AirSpec `initDesign` / `initLocalDesign` | `addDesign` / `addTestDesign` |
| AirSpec `test("...") { (a: A) => ... }` | the same: test arguments come from the suite's Design |

Uni uses a separate package root (`wvlet.uni.*`), so both libraries can coexist
during a migration. See [Appendix B: Uni and Airframe](../book/appendix-b-airframe)
for background.

## What mistakes do agents make with Uni?

- **Using Scala 2 syntax.** Uni is Scala 3 only. Use indentation syntax,
  `given`/`using`, `enum`, and `derives`.
- **Using ScalaTest matchers in UniTest.** Write `(x >= 1) shouldBe true`, not
  `x should be >= 1`. For type checks, write `v shouldMatch { case _: T => }`
  instead of `asInstanceOf`.
- **Importing only `UniTest`.** Use `import wvlet.uni.test.*`. `shouldBe defined`
  and `shouldBe empty` need matcher objects from that package.
- **Checking `isSuccessful` after `send`.** The HTTP client throws for non-2xx
  responses. Most 4xx statuses throw `HttpException`. Retryable statuses (5xx,
  429) throw `HttpMaxRetryException` after the retries, and it is not a subclass
  of `HttpException`. Failed RPC client calls throw the same exceptions.
- **Hand-writing test setup and teardown.** Bind servers, clients, and temp
  directories with `addDesign` / `addTestDesign` in a trait that extends
  `UniTest`, and take them as test arguments. A self-type fixture
  (`trait F { this: UniTest => }`) compiles, but when two of them override a
  hook, only the last one runs.
- **Hand-writing JSON codecs.** Add `derives Weaver` to the case class.
- **Mixing two libraries for one concern in the same module.** Uni works
  alongside community libraries. If a project already uses uPickle, MUnit, sttp,
  or another library for a concern, keep using it there. Use Uni's equivalent
  for new code that is built on Uni.

## Where are complete example apps?

The [`examples/`](https://github.com/wvlet/uni/tree/main/examples) folder of the
repository has small apps that CI compiles and tests against the current source.
Copy one as a template:

| Example | Shows |
|---------|-------|
| [`cli-tool`](https://github.com/wvlet/uni/tree/main/examples/cli-tool) | `Launcher` sub-commands, `LogSupport`, `derives Weaver`, the HTTP client, and a Scala Native binary |
| [`rest-server`](https://github.com/wvlet/uni/tree/main/examples/rest-server) | `Http.server` with `Router` and `RPCRouter`, a typed RPC client, `Design` wiring, and a UniTest suite |
| [`rpc-native`](https://github.com/wvlet/uni/tree/main/examples/rpc-native) | One RPC server built with `Http.server` and `Design`, the same code on the JVM (Netty) and as a Scala Native binary (no JVM), a typed client, a UniTest suite run on both, and a startup/memory/throughput benchmark |
| [`mcp-server`](https://github.com/wvlet/uni/tree/main/examples/mcp-server) | A stdio MCP server with `MCP.server.withTools[...]` and how to add it to Claude Code |
| [`web-ui`](https://github.com/wvlet/uni/tree/main/examples/web-ui) | A Scala.js todo list with `RxElement` and `Rx.variable` |

## Example: a wired service with logging and JSON

```scala
import wvlet.uni.design.Design
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

case class Greeting(name: String, message: String) derives Weaver

class GreeterService extends LogSupport:
  def greet(name: String): String =
    info(s"Greeting ${name}")
    Weaver.toJson(Greeting(name, s"Hello, ${name}!"))

@main def main =
  Design.newDesign
    .bindSingleton[GreeterService]
    .build[GreeterService] { greeter =>
      println(greeter.greet("Uni"))
    }
```

# Runnable examples under `examples/`

## Goals

- Ship small, realistic, runnable Uni apps that people and AI agents can copy as templates.
- Keep them in the uni repo and in the main sbt build, so CI compiles (and partly tests) them and they
  never drift from the API.
- Make them visible to Context7 (`context7.json` `folders`), the README, and `docs/guide/for-ai-agents.md`.

Why: Context7, DeepWiki, and training data weight working code heavily, and agents copy whole templates.
Snippets in docs are fragments; `book-examples` compiles the book's snippets but is not organized as
copyable apps (servers are never started, no run instructions).

## Background

- `book-examples` (`lazy val bookExamples` in `build.sbt`) is the pattern: a plain sbt project with
  `buildSettings`, `noPublish`, `dependsOn(uni.jvm, netty, test.jvm % Test)`, aggregated in `jvmProjects`,
  so `projectJVM/test` compiles and tests it.
- `examples/electron-app` already exists. It is a **standalone** sbt build (own `build.sbt`, depends on a
  locally published snapshot, needs pnpm + electron-vite). CI only format-checks it; it does not compile.
  It stays untouched.
- CI runs `projectJVM/test`, `projectJS/test`, `projectNative/test` in separate jobs, and
  `scalafmtCheckAll` from the root covers every in-build project.

## Design

Lane: **Standard** — adds new build modules and a new public-facing docs surface.

Layout: one sbt project per example, each in its own copyable folder with its own README, plus an index
`examples/README.md`. All use `buildSettings` + `noPublish` (so `publish / skip := true`), like `bookExamples`.

| Folder | sbt project | Platforms | What it shows |
|--------|-------------|-----------|---------------|
| `examples/cli-tool` | `exampleCli` (crossProject JVM + Native) | JVM, Native | `Launcher` options/sub-commands, `LogSupport`, `derives Weaver` decoding of a GitHub API response via `Http.client.newSyncClient`; `nativeLink` builds a single binary |
| `examples/rest-server` | `exampleRestServer` | JVM | `NettyServer` + `Router.of[...]` REST endpoints and an `RPCRouter` service on the same server, a typed `RPCClient`, **Design wiring**, and a **UniTest** suite that starts the server on port 0 and calls it |
| `examples/mcp-server` | `exampleMcpServer` | JVM | `MCPServer().withTools[...]().serveStdio()` with `@description`, a UniTest test of the service, and `claude mcp add` / `.mcp.json` instructions |
| `examples/web-ui` | `exampleWebUi` (Scala.js) | JS | A small `RxElement` counter/todo UI; `fastLinkJS` + a static `index.html` that loads the ES module, served by any static server (no Vite) |

Key points:

- Each example is 50–150 lines of Scala, package `example.<name>`, Scala 3 syntax, AGENTS.md style.
- Aggregation: `exampleCli.jvm`, `exampleRestServer`, `exampleMcpServer` join `jvmProjects`;
  `exampleCli.native` joins `nativeProjects` (compile to NIR only — CI does not `nativeLink` it; if
  `test` would link an empty test binary, disable it for that project); `exampleWebUi` joins `jsProjects`
  (compile only, no browser test).
- Every API used is verified against source (docs/AGENTS.md Rule #0); no test touches the network
  (the CLI test exercises argument parsing and JSON decoding of a fixed payload; the REST test uses
  localhost on a random port).
- Each README section says what it shows, how to run it from this repo (`./sbt exampleRestServer/run`),
  and the `libraryDependencies` to copy it into a standalone project.
- `context7.json`: add `"examples"` to `folders` (and exclude `examples/electron-app/node_modules`-style
  build output if needed); `url` / `public_key` untouched.
- Link from `README.md` and `docs/guide/for-ai-agents.md` (a short "Runnable examples" section). Because
  the AI-agent page changes, also add a pointer in `plugins/uni/skills/uni/` and bump the plugin version.
- CI cost target: under ~1 minute added per job (small modules compile in seconds; one short netty test).
  The PR reports measured local compile/test times.

## Alternatives and Why Not?

- **One `examples` sbt project with all apps in one source tree** — simpler build, but apps are not
  separately copyable, the `main`s collide, and deps (netty, Native) leak into every example.
- **Standalone sbt builds like `electron-app`** — most copy-friendly, but CI would need to publish uni
  locally per job to compile them (slow), which defeats "never stale".
- **Skip the Scala.js UI** — it would be justified if it needed Vite/Playwright; compiling an RxElement
  module needs neither, so it is cheap to keep.

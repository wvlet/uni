# Runnable examples live in the main build

## Context

AI agents and people copy whole working templates, and Context7, DeepWiki, and training data weight example code heavily. `book-examples` compiles the book's snippets, but it is not a set of copyable apps: servers are never started and there are no run instructions. `examples/electron-app` is a standalone sbt build against a published snapshot, so CI only format-checks it and it can drift from the API.

## Decision

- Each example under `examples/` (`cli-tool`, `rest-server`, `mcp-server`, `web-ui`) is its own project in the root `build.sbt` (`exampleCli` cross JVM/Native, `exampleRestServer`, `exampleMcpServer`, `exampleWebUi`). Each is aggregated into `projectJVM`, `projectJS`, or `projectNative`, so the existing CI jobs compile and test it. They use `noPublish`.
- One project per folder, not a single `examples` project, so each folder stays self-contained (its sources, tests, README) and its dependencies (netty, Native, Scala.js) do not leak into the others.
- Native and Scala.js examples are compile-only in CI: `Test / loadedTestFrameworks := Def.uncached(Map.empty)` stops `test` from linking an empty test binary or module. JVM-only tests go in `.jvm/src/test`.
- `exampleMcpServer` forks `run` with `connectInput` and `outputStrategy := Some(StdoutOutput)`, so `./sbt --error exampleMcpServer/run` works directly as an MCP stdio command.
- `exampleWebUi` links to `examples/web-ui/target/js`, so a static `index.html` can load `main.js` with no bundler.

Rejected: standalone builds like `electron-app` (CI would have to `publishLocal` uni in every job to compile them) and a single `examples` project (examples could not be copied independently, and their `main`s and dependencies would mix).

## Consequences

- An API change that breaks an example fails CI. Fix the example in the same PR.
- A new example should follow the same pattern: a folder with a README, a project in `build.sbt` added to the right aggregate, `noPublish`, and no network access in tests.
- The typed RPC client in `rest-server` is written by hand to mirror sbt-uni's generated code, because the main build does not load the sbt-uni plugin. Keep it in sync with `RPCClient` if that API changes.

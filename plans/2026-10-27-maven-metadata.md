# Maven Central / Scaladex metadata for discoverability

## Goals

- Make Uni findable on Maven Central, Scaladex, and by AI agents that read POM metadata.
- POM `<description>` values are currently terse ("Scala unified core library") and say little about what each artifact contains.
- POM `<url>` points to GitHub, not the documentation site (https://wvlet.org/uni/), which now carries llms.txt and AI-agent docs.

## Background

- All published modules (uni-core, uni, uni-test, uni-netty) share `buildSettings` in build.sbt, which sets `homepage`, `scmInfo`, `licenses`, and `developers`. Each module overrides `description`.
- sbt-uni and sbt-uni-crossproject are separate builds with their own metadata; nothing there reads the main build's homepage.

## Design

Lane: Express — metadata strings only in build.sbt, no logic, no versions or credentials.

- `homepage` → `https://wvlet.org/uni/`; `scmInfo` stays on GitHub.
- Rewrite descriptions to keyword-rich text that is checked against each module's sources:
  - uni-core: logging, Rx, file I/O (gzip/zip, file watching), terminal colors, text scanner.
  - uni: DI (Design), Surface reflection, JSON/MessagePack, Weaver serialization, Rx, HTTP client/router, RPC, OpenAPI, MCP servers, retry/circuit breaker/cache, CLI tools, reactive DOM UI and Electron (Scala.js).
  - uni-test: AirSpec-style testing for all three platforms, property-based testing, JUnit Platform IDE integration.
  - uni-netty: Netty HTTP/WebSocket server for Uni routers and RPC.
- Root default description: matches the uni summary for the platforms.
- Verify with `makePom` that the generated POMs contain the new values.

## Alternatives and Why Not?

- Updating the sbt plugin builds' homepage too: out of scope; their GitHub homepage is accurate for plugin users and is not inherited.

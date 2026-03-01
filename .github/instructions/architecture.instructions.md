---
applyTo: "**/build.sbt,**/plugin.sbt"
---

# Uni Module Architecture

## Module Dependency Graph

```
uni-core  (no deps, pure Scala, cross-platform: JVM/JS/Native)
  ├── uni-test          (testing framework, cross-platform: JVM/JS/Native)
  ├── uni               (main library, cross-platform: JVM/JS/Native)
  │     ├── uni-agent        (JVM only, depends: airframe)
  │     │     └── uni-agent-bedrock  (JVM only, depends: AWS SDK)
  │     │           └── uni-integration-test  (JVM only, requires AWS creds)
  │     ├── uni-netty        (JVM only, depends: Netty)
  │     └── uni-dom-test     (JS only, JSDOM environment)
  └── (uni-test is used as Test dependency by all modules above)
```

## Dependency Rules

- Dependencies flow downward only: a module may depend on modules above it, never below
- `uni-core` must remain dependency-free (pure Scala only)
- `uni` must have minimal external dependencies
- External dependencies (airframe, AWS SDK, Netty) are isolated in leaf modules
- Cross-platform modules (`uni-core`, `uni`, `uni-test`) must not depend on JVM-only libraries

## Cross-Platform Patterns

- Platform-specific code goes in `.jvm/`, `.js/`, `.native/` directories
- Shared code uses `CrossType.Pure` (no platform-specific source dirs by default)
- Scala.js requires ES modules (`ModuleKind.ESModule`) except `uni-dom-test` (JSDOM needs `NoModule`)
- Scala Native links against `-lcurl` and `-lz` for HTTP and compression

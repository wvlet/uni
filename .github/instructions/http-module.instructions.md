---
applyTo: "uni-netty/**/*.scala,uni/src/**/http/**/*.scala"
---

# HTTP Module Development Guide

## Module Structure

- `uni/src/**/http/`: Cross-platform HTTP client and routing (JVM/JS/Native)
- `uni-netty/`: Netty-based HTTP server (JVM only)

## Dependencies

- HTTP client code in `uni` must remain cross-platform compatible
- Netty server code is JVM-only; keep Netty imports isolated to `uni-netty/`
- Scala Native HTTP uses libcurl (`-lcurl` linked at build time)

## Patterns

- Router definitions use the uni RPC/REST router pattern
- HTTP client config uses `withXXX(...)` pattern for configuration
- Retry logic should use `wvlet.uni.control.Retry`, not custom implementations

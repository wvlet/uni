# uni-netty

Netty-based HTTP server. JVM only.

## Constraints

- Depends on `uni` (JVM) and Netty
- Keep all Netty imports and APIs isolated to this module
- HTTP client code belongs in `uni/`, not here — this is server-side only
- Netty epoll transports are included for Linux (x86_64 and aarch64)

## Testing

```bash
./sbt netty/test
```

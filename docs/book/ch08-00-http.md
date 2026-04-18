# 8. HTTP Clients and Servers

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

You met the HTTP client in Chapter 2. This chapter covers it
properly, alongside the server that serves requests from the other
side.

Concepts introduced:

- Sync vs. async clients: when to use each, and why most code should
  start with sync.
- Configuring the client: timeouts, retry, rate limiting, connection
  pooling.
- The REST server: defining endpoints, binding them to a Design, and
  running them.
- The Router: composing endpoints into a larger service, including
  sub-routers.
- Server-sent events and streaming responses.
- Interop: how the client behaves identically on JVM, Scala.js, and
  Scala Native.

## Reference you can read now

- [HTTP — Overview](/http/)
- [HTTP Client](/http/client)
- [REST Server](/http/server)
- [Router](/http/router)
- [Server-Sent Events](/http/sse)
- [Retry Strategies](/http/retry)

[← 7. Retry, Circuit Breakers, Resources](./ch07-00-control) | [Next → 9. Typed RPC](./ch09-00-rpc)

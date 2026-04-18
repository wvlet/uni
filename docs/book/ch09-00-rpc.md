# 9. Typed RPC

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Uni's RPC layer lets two services share a trait definition and get a
working client + server pair, without per-endpoint glue code. This
chapter walks through defining, implementing, and consuming an RPC
service.

Concepts introduced:

- RPC traits: declaring an interface once, using it on both sides.
- The wire format and why MessagePack is a sensible default.
- Error handling across process boundaries: what your exception looks
  like on the other side.
- Evolving an RPC API without breaking existing clients.
- When RPC is the right tool — and when to use plain HTTP / REST
  instead.

## Reference you can read now

- [RPC](/http/rpc)

[← 8. HTTP Clients and Servers](./ch08-00-http) | [Next → 10. Cross-Platform Development](./ch10-00-cross-platform)

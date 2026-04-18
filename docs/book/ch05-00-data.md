# 5. Data In, Data Out — JSON & MessagePack

::: warning Coming soon
This chapter is an outline. The full draft ships in a follow-up PR.
:::

## What this chapter will cover

Every service eats and produces data. This chapter covers how Uni
turns bytes on the wire into Scala 3 case classes, and back, without
per-type boilerplate.

Concepts introduced:

- `JSON` for parsing, mutation, and generation of JSON trees.
- Case-class codecs via `Surface` — no annotation or companion
  boilerplate required.
- When to reach for MessagePack instead of JSON (size, performance,
  binary-safe payloads).
- Streaming: parsing documents larger than memory, and producing them
  incrementally.
- Error handling: what happens when the bytes do not match the type.
- Interop with existing JSON libraries when you must speak someone
  else's schema.

## Reference you can read now

- [JSON Processing](/core/json)
- [MessagePack](/core/msgpack)
- [Type Introspection (Surface)](/core/surface)

[← 4. Logging That Finds You](./ch04-00-logging) | [Next → 6. Rx, the Composable Stream](./ch06-00-rx)

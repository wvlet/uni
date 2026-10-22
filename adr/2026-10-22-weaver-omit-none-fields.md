# Weaver can omit None-valued Option fields

## Context

`CaseClassWeaver` writes every constructor parameter, so an Option field holding None becomes nil (`"cache_control": null` in JSON). Airframe omits such keys. Apps migrating from Airframe that must keep their public wire shape (td-llm-proxy's Anthropic-compatible API) cannot send explicit nulls.

## Decision

- `WeaverConfig.omitNoneFields` (default `false`, set with `withOmitNoneFields(true)`) skips case-class fields whose parameter surface is an Option and whose value is None. The map header counts only the fields written, so MessagePack stays well-formed.
- The flag rides on the `WeaverConfig` already passed through `pack`, so nested case classes, collections of them, and sealed-trait children (which repack the child's map) follow it with no extra plumbing.
- Only case-class fields are affected. `Some(None)`, explicit nulls inside `Map[String, Any]`, and a top-level None still pack as nil, since both Airframe and uni preserve those.
- `RouterHandler.withWeaverConfig` passes the config to `ResponseConverter`, so controller return values can use it. `RPCHandler` is unchanged.
- Unpacking is unchanged: an absent Option field already reads as None.

Rejected alternative: making omission the default. It would silently change the JSON every existing uni user emits.

## Consequences

- Apps that want Airframe's shape must opt in at every encoding site (`toJson(v, config)`, `RouterHandler.withWeaverConfig`).
- With the flag on, a reader can no longer tell an absent field from an explicit null for case-class Option fields; uni treats both as None.

# YAML writer cleanups

## Goals

- Remove the `[E121]` warning in `YAMLWriter.flowCollection`, with no change in behavior.
- Keep the comment on a block scalar's header line (`a: | # note`) when `set` changes the value. A test confirmed that the comment was dropped.

## Background

- `YAMLSource.parsed` is `Product | Null`, so `case _` after `case p: Product` only matches null, which is what the compiler warns about.
- The header comment sits inside the scalar's `raw`, and `YAMLEdit.replace` drops `raw` (`detached`), so the writer never sees the comment.

## Design

- Lane: **Express**. A bug fix with a confirmed diagnosis plus a warning cleanup. No public API change and no always-gate area.
- Warning: write `case null =>` instead of `case _ =>`.
- Header comment: a same-line comment belongs to the node, as in Wvlet's attachment pattern. `replace` copies the comment into a new private `YAMLSource.header` field, and the writer prints it after the regenerated header.
- If the new value is not written as a block scalar (a number, or text that needs quotes), the comment goes after the value.
- A `#` starts the comment only after a space, so a `#` in a tag or anchor is not treated as one.
- The ADR gets one paragraph on this rule.

## Alternatives and Why Not?

- Have the parser put the header comment in `comment`: unchanged nodes would then print it twice or in the wrong place, and the round trip would change.

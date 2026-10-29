# Keep a block scalar's header comment when a collection replaces it

## Goals

- When `set` replaces a block scalar (`a: | # note`) with a collection, keep the header comment, as #826 did for scalar replacements.

## Design

- Lane: **Express**. A small extension of the #826 rule, with no public API change and no always-gate area.
- `YAMLEdit.replace` extracts the header comment once, for every kind of replacement, and stores it in the private `YAMLSource.header` field.
- Flow collection: the comment goes after the value (`a: [] # note`).
- Block collection: the comment stays on the key or dash line (`a: # note`, `- # note`). A collection under a dash then starts on the next line instead of after the dash.
- The comment stays through later replacements. The ADR paragraph is updated.

# Row labels for VirtualList and Tree

Follow-up to #909 (`DataTable.withRowLabel`), on the single-parameter-list API from #913.

## Goals

- Let an app name VirtualList and Tree rows for agents without turning on typeahead, as `DataTable.withRowLabel` does for tables.
- One label source per component, used the same way by snapshots, the `select` action, and the change feed.

## Background

- `ListCore` already takes `itemLabel` and resolves `itemLabel`, then `typeaheadLabel`, then the key's `toString` (#909). Only DataTable passes it.
- VirtualList and Tree can name rows today only through `withTypeahead`, which also changes keyboard behaviour.
- Labels are cached against the current row sequence (by reference), so they rebuild when rows change, such as when a tree row opens.
- Reorder announcements ("Moved X to position 2 of 5") use the typeahead label or the key; they ignore `itemLabel`, so a DataTable with `withRowLabel` still announces keys.

## Design

Lane: **Standard** (adds public API); the direction is given by the work item, so it proceeds without a design gate. No visual change, so no prototype gate.

- **User flow:** an app author adds `.withRowLabel(f => f.name)` to a VirtualList or Tree. `UiAgent.snapshot` then lists those labels as items, `select` accepts them, and the change feed reports selections (and list reorders) by them. Without the call, nothing changes.
- **API:** `withRowLabel(A => String)` and `noRowLabel()` on VirtualList and Tree, matching DataTable's names. Tree's function takes the item `A`, not the internal row.
- **Fallback:** row label, then typeahead text, then the key's `toString`, documented once in docs/dom/agents.md.
- **Typeahead stays independent:** `withRowLabel` doesn't enable typeahead, and typing still matches the typeahead text when both are set.
- **Reorder announcements** use the same resolved label, so screen readers and agents name a moved row the same way. This also fixes DataTable.
- **Tests:** snapshot/select/change feed with a row label on both components, typeahead still using its own text, labels following replaced rows and newly opened tree rows.
- **Docs:** docs/dom/agents.md, docs/dom/collections.md, docs/guide/for-ai-agents.md, the plugin's `references/ui.md` (compiled by docSnippetsJS), and a plugin version bump.

## Alternatives and Why Not?

- **A new name such as `withItemLabel`:** DataTable already uses `withRowLabel`; one name across the three collections is easier to learn.
- **Reading labels from rendered rows:** rows are virtualized DomNodes, so off-screen rows have no text.

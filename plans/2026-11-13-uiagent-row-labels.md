# UiAgent row labels for DataTable, and cheaper list labels

Follow-up to #905 (UiAgent) and plans/2026-11-13-ui-sdk-learnings.md.

## Goals

- When an agent reads a DataTable, it should see readable row text ("Alice — Engineering") instead of ids ("42").
- With an agent installed, a selection change in a large list or table should not scan every row.

## Background

- All three list components (VirtualList, Tree, DataTable) share `ListCore`. Its agent binding labels an item with the `withTypeahead` function, or the key's `toString` when there is none.
- DataTable passes `typeaheadLabel = None`, so its rows always fall back to the key.
- A column's `cell` is `A => DomNode`. Text can't be read from a DomNode without mounting it, and a virtualized table mounts only the visible rows. So "the first column's text" is not available as a default.
- On every selection change, `format` (called for both the previous and new value) builds a `key -> label` map over all rows. That's O(rows) even when one row changed. `ListCore` already keeps a `key -> index` map that it could look up instead.
- Snapshots also recompute the deduplicated label list once for `items` and again for `actions`.

## Design

Lane: **Standard**. It adds public API (`DataTable.withRowLabel`).

- **User flow:** an app author adds `.withRowLabel(u => s"${u.name} (${u.team})")` to a DataTable. After that, `UiAgent.snapshot` lists those labels as the table's items, the `select` action accepts them, and the change feed reports selections by label. Without the call, the table works as it does today (key `toString`).
- **API:** add `DataTable.withRowLabel(A => String)` and `noRowLabel()`, following the config-class convention. It only sets the label that agents (and later, assistive text) see. It doesn't turn on typeahead in the table.
- **ListCore:** add an `itemLabel` parameter, resolved as `itemLabel`, then `typeaheadLabel`, then the key. VirtualList and Tree keep using `withTypeahead` as their label. They get no new method, so the public API doesn't grow where a source already exists.
- **Default stays the key:** we don't guess from the first column. Rendering cells to text is costly, and the result is wrong for icon or action columns.
- **Performance:** `format` looks up only the changed keys through the existing `index` map, so a change costs O(selected) instead of O(rows). Labels are cached against the current row sequence (`current`, compared by reference), so a snapshot computes them once and rebuilds them only when the rows change.
- **Docs:** add a sentence to the DataTable and agent sections of docs/dom/ui.md, update docs/guide/for-ai-agents.md and plugins/uni/skills/uni/references/ui.md, and bump the plugin version. Snippets compile in docSnippetsJS.
- **Tests:** UiAgentTest covers a DataTable with and without `withRowLabel` (snapshot items, `select` by label, change-feed text), plus a large table where selection changes don't call the label function for every row.

## Alternatives and Why Not?

- **Default to the first column's text:** cells are DomNodes, which can't be read off-screen. It would also make the label depend on column order.
- **`Column.withText(A => String)` and joining columns:** more flexible, but it adds API to every column for one agent-facing string. `withRowLabel` can do the join itself.
- **A shared `withItemLabel` on VirtualList and Tree as well:** typeahead text already serves as a readable label there. We can add this later if someone needs labels that differ from the typeahead text.
- **Enable typeahead in DataTable from `withRowLabel`:** that changes keyboard behaviour, which belongs in the separate behaviour-polish session.

# uni-ui construction API: one parameter list of named, required arguments

## Context

Many uni-ui components were built with curried parameter lists: `VirtualList(items, rowHeight)(key)(render)`, `Popover(open)(trigger)(content*)`, `DataTable(rows)(key)(columns*)`. At a call site nothing says which list holds what, so people and AI agents had to open the source to tell the key from the renderer or the trigger from the content. Currying was a Scala 2 habit: a later list fixes the type parameter before the compiler types the `key`/`render` lambdas. Scala 3 types function literals after the other arguments, so the habit no longer pays for itself.

## Decision

- A component takes **exactly one parameter list**, holding what it cannot work without, in the order state → data → key → render/trigger → entries/content.
- Entries and content are required arguments too (a menu without entries makes no sense). A multi-valued argument is a named `Seq` (`entries`, `content`, `columns`, `tabs`, `panels`), never varargs and never a second list.
- Everything optional is a `withXXX`/`noXXX` builder. `FoldablePane`'s splitter moved from a default parameter to `.withSplitter(s)`.
- Docs, the `uni` skill, and examples name every function, trigger, and multi-value argument, plus any argument whose type does not show its role (`title = "..."`, `label = "..."`). Plain leading values such as `Column("name", "Name", ...)` or `Splitter(size, range, edge, label)` stay positional.
- `Tree`'s `children: A => Seq[A]` is a function over the app's data, not a children list, so it is a named argument like `key` and `render`.
- `Column` is a per-row template (the table's renderer), so `columns` is an argument, not children.
- Components whose content is the component itself keep it as trailing varargs, with no other multi-value argument to confuse it with: `Button(content*)`, `Tab(value, content*)`, `Badge(content*)`, `StatusBar(content*)`.
- The curried forms were removed without deprecated forwarders, so AI agents see only one shape.

## Alternatives

- **A second list for children** (`Menu(open, trigger = b)(items*)`): entries are required, and splitting required arguments across lists brings back "which list holds what?"
- **Trailing varargs in the one list** (`Menu(open, trigger = b, MenuItem("Copy"), ...)`): shorter, but the entries are unnamed and `Popover(open, trigger = b, Form())` blurs where the trigger ends.
- **Builder-only** (`VirtualList(items).withKey(...).withRender(...)`): a missing required argument would surface at runtime, and `K` cannot be inferred until `withKey`.
- **A props case class per component**: an extra type per component, still positional unless named.

## Consequences

- `TabPanel(tabs) { case ... }` became `TabPanel(tabs, render = { case ... })`.
- Overloaded `Select` cannot infer from `identity`; write `optionLabel = s => s`.
- Single-element content needs a `Seq`: `Dialog(open, content = Seq(form))`.
- A `Column` infers its row type from `columns = Seq(...)` only when nothing is chained on it; with `.sortBy(...)` or `.withWidth(...)` write `Column[Row](...)`, since the chained call types the column first.

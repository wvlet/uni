---
description: uni-ui controls for Scala.js — Button, IconButton, Input, Textarea, tabs, badges, and key caps — bound to Rx variables.
---

# Controls

Buttons, text fields, tabs, badges, and key caps. Each takes the `Rx` values it reads; options are
`withXXX` methods (see [Conventions](./ui#conventions)).

```scala
import wvlet.uni.dom.all.*
import wvlet.uni.dom.all.given
import wvlet.uni.rx.Rx
import wvlet.uni.ui.*
import scala.language.implicitConversions

val draft = Rx.variable("")
val busy  = Rx.variable(false)

div(
  Textarea(draft).withRows(4).withPlaceholder("Write a message"),
  Button("Send").primary.withDisabled(busy).onClick(() => send(draft.get)),
  IconButton("Close", closeIcon).onClick(() => close())
)
```

| Component | What it does |
|-----------|--------------|
| `Button(content*)` | `<button type="button">`. Variants are `.primary`, `.ghost`, and `.danger`, plus `.small`; set the state with `.withDisabled(rx)` and the action with `.onClick(f)` |
| `IconButton(label, icon)` | A square ghost button; `label` is its accessible name |
| `Input(text)` / `Textarea(text)` | Bound both ways to an `RxVar[String]`. Use `.withPlaceholder`, `.withLabel`, `.withInvalid(rx)` (sets `aria-invalid`), and `.withDisabled` |
| `TabList(selected, label = "...", tabs = Seq(Tab(value, content*)))` | Tabs bound to an `RxVar[A]`. Arrow keys, Home, and End move the selection |
| `TabPanel(tabs, render = a => element)` | Renders the selected tab's content for the `TabList` `tabs`, linked to it with `aria-controls` and `aria-labelledby` |
| `Badge(content*)`, `Badge.dot(label)` | A count or a status dot; `.withTone(BadgeTone.Accent)` |
| `Kbd(key)` | A key cap, `<kbd>` |

# Uni.dom Improvements Plan

## Summary

Add CSS Style DSL and CSS Class utilities to uni.dom following React/Vue conventions for familiar developer experience.

## Research: How Other Libraries Handle Styles

| Framework | CSS Properties Syntax | Raw String | `<style>` Tag |
|-----------|----------------------|------------|---------------|
| **React** | `style={{ display: 'flex', gap: '8px' }}` | Same | Separate in `<head>` |
| **Vue** | `:style="{ display: 'flex', gap: '8px' }"` | Same | Separate in `<head>` |
| **ScalaTags** | `styles.display` or top-level import | `style := "..."` | `style` tag |
| **Laminar** | Top-level `display := "flex"` | `styleAttr := "..."` | `styleTag` |

**Key insight**: React/Vue use `style` for CSS because it's far more common than the `<style>` tag. The `<style>` tag typically goes in `<head>`, not in components.

## Design Decision

Follow **React/Vue convention**:
- Use `style` object for CSS properties with grouped syntax
- Rename `<style>` HTML tag to `styleTag` in exports

## Implementation

### 1. CSS Style DSL (`CssStyles.scala`)

The `style` object extends `DomElement` to serve dual purposes:
1. CSS style attribute builder (React/Vue style)
2. `<style>` tag element

```scala
// uni/.js/src/main/scala/wvlet/uni/dom/CssStyles.scala
package wvlet.uni.dom

case class StyleValue(name: String, value: String)

case class StyleProperty(name: String):
  def :=(value: String): StyleValue = StyleValue(name, value)

// Extends DomElement so it can override HtmlTags.style
object style extends DomElement("style", DomNamespace.xhtml, Nil):
  // Grouped CSS properties (React/Vue style)
  // Requires at least one StyleValue to avoid ambiguity with inherited apply(DomNode*)
  def apply(first: StyleValue, rest: StyleValue*): DomAttribute =
    DomAttribute("style", (first +: rest).map(v => s"${v.name}: ${v.value};").mkString(" "), append = true)

  // Raw string (Tailwind-friendly)
  def :=(value: String): DomAttribute = DomAttribute("style", value, append = true)

  // Properties
  val display = StyleProperty("display")
  val gap = StyleProperty("gap")
  val color = StyleProperty("color")
  // ... 60+ CSS properties
```

**Usage**:
```scala
import wvlet.uni.dom.all.*

// Grouped CSS properties (React/Vue style)
div(style(style.display := "flex", style.gap := "8px", style.color := "blue"))

// Raw style string (Tailwind-friendly)
div(style := "display: flex; gap: 8px;")

// <style> tag element (inherits from DomElement)
head(style("body { margin: 0; }"))
```

### 2. CSS Class Utilities (`CssClass.scala`)

```scala
// uni/.js/src/main/scala/wvlet/uni/dom/CssClass.scala
package wvlet.uni.dom

import wvlet.uni.rx.Rx

case class ClassToggle(className: String):
  infix def when(condition: Boolean): DomNode =
    if condition then DomAttribute("class", className, append = true)
    else DomNode.empty

  // Uses reactive attribute binding to avoid text node markers
  infix def when(rx: Rx[Boolean]): DomNode =
    DomAttribute("class", rx.map(cond => if cond then className else ""), append = true)
```

**Modify HtmlAttrs.scala**:
```scala
object cls extends DomAttributeOf("class"):
  def :=[V: EmbeddableAttribute](v: V): DomNode = apply(v)
  def toggle(className: String): ClassToggle = ClassToggle(className)
```

**Usage**:
```scala
val isActive = Rx.variable(false)

div(
  cls := "base-class",
  cls.toggle("active") when isActive,      // reactive
  cls.toggle("disabled") when isDisabled   // static
)
```

### 3. Handle `<style>` Tag Conflict (`all.scala`)

Since `style` object extends `DomElement`, it can override `HtmlTags.style`:

```scala
// Override style from HtmlTags with our CSS style object (which extends DomElement)
// This allows: style(style.display := "flex") for CSS, and style("...") for <style> tag
override lazy val style: wvlet.uni.dom.style.type = wvlet.uni.dom.style
```

## Files to Create/Modify

| File | Action |
|------|--------|
| `uni/.js/src/main/scala/wvlet/uni/dom/CssStyles.scala` | Create - `style` object with properties |
| `uni/.js/src/main/scala/wvlet/uni/dom/CssClass.scala` | Create - `ClassToggle` helper |
| `uni/.js/src/main/scala/wvlet/uni/dom/HtmlAttrs.scala` | Modify - `cls` object with `toggle` |
| `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` | Modify - Export `style`, rename tag to `styleTag` |
| `uni-dom-test/src/test/scala/wvlet/uni/dom/CssStylesTest.scala` | Create - Tests |
| `uni-dom-test/src/test/scala/wvlet/uni/dom/CssClassTest.scala` | Create - Tests |

## CSS Properties (~65 total)

- **Layout**: display, position, top, right, bottom, left, zIndex, overflow, visibility
- **Box Model**: width, height, min/max variants, margin/padding (all sides)
- **Flexbox**: flexDirection, flexWrap, justifyContent, alignItems, gap
- **Grid**: gridTemplateColumns/Rows, gridColumn/Row, placeItems
- **Typography**: fontSize, fontWeight, fontFamily, lineHeight, textAlign, color
- **Background**: backgroundColor, backgroundImage, backgroundSize
- **Border**: border, borderRadius, borderColor, outline
- **Effects**: transform, transition, animation, opacity
- **Interaction**: cursor, pointerEvents, userSelect

## Verification

1. `./sbt uniJS/compile`
2. `./sbt domTest/test`
3. `./sbt scalafmtAll`

## Test Cases

```scala
// Grouped style
test("grouped style properties"):
  val elem = div(style(display := "flex", gap := "8px"))
  html shouldContain "display: flex"
  html shouldContain "gap: 8px"

// Raw string
test("raw style string"):
  val elem = div(style := "margin: 10px;")
  html shouldContain "margin: 10px"

// Class toggle
test("class toggle when true"):
  val elem = div(cls.toggle("active") when true)
  html shouldContain "active"

test("reactive class toggle"):
  val isActive = Rx.variable(false)
  val elem = div(cls.toggle("active") when isActive)
  // responds to isActive changes
```

## Out of Scope (Future Work)

- DOM Properties (value, checked)
- Keyed collections / split operator
- Two-way binding
- ARIA attributes

## Review Learnings

### Codex Review Findings (Fixed)

1. **Reactive class toggle text node markers** - Original implementation using `Embedded(rx.map(...))` inserted placeholder text nodes that could break CSS `:empty` selectors. Fixed by using direct reactive attribute binding: `DomAttribute("class", rx.map(...), append = true)`.

2. **`style()` overload ambiguity** - The `apply(values: StyleValue*)` signature conflicted with inherited `apply(nodes: DomNode*)` for zero-argument calls. Fixed by requiring at least one StyleValue: `apply(first: StyleValue, rest: StyleValue*)`.

### Design Evolution

- Started with top-level CSS properties (like Laminar) but user feedback led to namespacing under `style` object
- Originally used `css` for raw styles but changed to `style := "..."` for React/Vue familiarity
- The `style` object extending `DomElement` allows dual use as both CSS attribute builder and `<style>` tag

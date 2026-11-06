# Uni.dom Phase 2: DOM Properties, Two-Way Binding, and ARIA Attributes

## Summary

Add DOM Properties, Two-Way Binding, and ARIA attributes to uni-dom for complete form handling support.

## Features

### 1. DOM Properties (value, checked, disabled)

**Problem**: Current implementation uses `setAttribute()` which only sets initial values. For form inputs, we need DOM **properties** that reflect current state.

**Solution**: Add property detection in DomRenderer to use direct property assignment for `value`, `checked`, `disabled`, `selected`.

**API** (unchanged syntax, improved semantics):
```scala
input(tpe -> "text", value -> "initial")           // Sets property, not attribute
input(tpe -> "checkbox", checked -> true)          // Sets .checked property
button(disabled -> isDisabled)                     // Reactive property binding
```

### 2. Two-Way Binding

**Problem**: Need to sync Rx.variable with DOM form inputs bidirectionally.

**Solution**: Add `bind` method to value/checked properties.

**API**:
```scala
val username = Rx.variable("")
val isChecked = Rx.variable(false)

// Text input two-way binding
input(tpe -> "text", value.bind(username))

// Checkbox two-way binding
input(tpe -> "checkbox", checked.bind(isChecked))

// Select two-way binding
val selected = Rx.variable("opt1")
select(value.bind(selected),
  option(value -> "opt1", "Option 1"),
  option(value -> "opt2", "Option 2")
)
```

### 3. ARIA Attributes

**Problem**: Current `aria("suffix")` method requires string suffix. No type-safe attribute names.

**Solution**: Add 25+ common ARIA attributes as lazy vals.

**API**:
```scala
// Type-safe ARIA attributes
button(
  ariaLabel -> "Close dialog",
  ariaExpanded -> isExpanded,
  ariaControls -> "dialog-content"
)

// Dynamic for uncommon attributes
div(aria("atomic") -> "true")
```

## Implementation

### Files to Create

| File | Description |
|------|-------------|
| `uni/.js/src/main/scala/wvlet/uni/dom/DomProperty.scala` | Property binding types |
| `uni/.js/src/main/scala/wvlet/uni/dom/AriaAttrs.scala` | ARIA attribute definitions |
| `uni-dom-test/src/test/scala/wvlet/uni/dom/DomPropertyTest.scala` | Property tests |
| `uni-dom-test/src/test/scala/wvlet/uni/dom/AriaAttrsTest.scala` | ARIA tests |

### Files to Modify

| File | Changes |
|------|---------|
| `uni/.js/src/main/scala/wvlet/uni/dom/DomRenderer.scala` | Add property handling in `addAttribute()` |
| `uni/.js/src/main/scala/wvlet/uni/dom/HtmlAttrs.scala` | Add `bind` methods to value/checked, extend AriaAttrs |
| `uni/.js/src/main/scala/wvlet/uni/dom/all.scala` | Export new types |

### Implementation Details

#### 1. DomProperty.scala

```scala
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.RxVar

// Marker for DOM properties (vs attributes)
case class DomProperty(name: String, value: Any) extends DomNode

// Two-way binding for string values (text inputs, select)
case class ValueBinding(variable: RxVar[String], useChangeEvent: Boolean = false) extends DomNode

// Two-way binding for boolean values (checkboxes)
case class CheckedBinding(variable: RxVar[Boolean]) extends DomNode
```

#### 2. DomRenderer Property Handling

In `addAttribute()`, detect property names and use direct assignment:

```scala
a.name match
  case "value" | "checked" | "disabled" | "selected" =>
    // Set as property: element.asInstanceOf[js.Dynamic].updateDynamic(name)(value)
  case "style" => // existing style handling
  case _ => // existing setAttribute handling
```

For two-way bindings, add traverse case:

```scala
case vb: ValueBinding =>
  // 1. Subscribe to RxVar -> update DOM property
  // 2. Add input/change event listener -> update RxVar
  // 3. Use guard flag to prevent infinite loops
```

#### 3. AriaAttrs.scala

```scala
package wvlet.uni.dom

trait AriaAttrs:
  import HtmlTags.attr

  // Labels and descriptions
  lazy val ariaLabel: DomAttributeOf = attr("aria-label")
  lazy val ariaLabelledby: DomAttributeOf = attr("aria-labelledby")
  lazy val ariaDescribedby: DomAttributeOf = attr("aria-describedby")

  // State
  lazy val ariaHidden: DomAttributeOf = attr("aria-hidden")
  lazy val ariaExpanded: DomAttributeOf = attr("aria-expanded")
  lazy val ariaSelected: DomAttributeOf = attr("aria-selected")
  lazy val ariaChecked: DomAttributeOf = attr("aria-checked")
  lazy val ariaDisabled: DomAttributeOf = attr("aria-disabled")
  lazy val ariaPressed: DomAttributeOf = attr("aria-pressed")
  lazy val ariaBusy: DomAttributeOf = attr("aria-busy")
  lazy val ariaCurrent: DomAttributeOf = attr("aria-current")

  // Relationships
  lazy val ariaOwns: DomAttributeOf = attr("aria-owns")
  lazy val ariaControls: DomAttributeOf = attr("aria-controls")
  lazy val ariaFlowto: DomAttributeOf = attr("aria-flowto")

  // Live regions
  lazy val ariaLive: DomAttributeOf = attr("aria-live")
  lazy val ariaAtomic: DomAttributeOf = attr("aria-atomic")
  lazy val ariaRelevant: DomAttributeOf = attr("aria-relevant")

  // Widget values
  lazy val ariaValuemin: DomAttributeOf = attr("aria-valuemin")
  lazy val ariaValuemax: DomAttributeOf = attr("aria-valuemax")
  lazy val ariaValuenow: DomAttributeOf = attr("aria-valuenow")
  lazy val ariaValuetext: DomAttributeOf = attr("aria-valuetext")

  // Popup
  lazy val ariaHaspopup: DomAttributeOf = attr("aria-haspopup")
  lazy val ariaModal: DomAttributeOf = attr("aria-modal")

  // Position
  lazy val ariaLevel: DomAttributeOf = attr("aria-level")
  lazy val ariaSetsize: DomAttributeOf = attr("aria-setsize")
  lazy val ariaPosinset: DomAttributeOf = attr("aria-posinset")

  // Dynamic for uncommon attributes
  def aria(suffix: String): DomAttributeOf = attr(s"aria-${suffix}")
```

#### 4. Modify HtmlAttrs

Change `value` and `checked` from lazy val to object with `bind` method:

```scala
object value extends DomAttributeOf("value"):
  def bind(variable: RxVar[String]): DomNode = ValueBinding(variable)
  def bindOnChange(variable: RxVar[String]): DomNode = ValueBinding(variable, useChangeEvent = true)

object checked extends DomAttributeOf("checked"):
  def bind(variable: RxVar[Boolean]): DomNode = CheckedBinding(variable)
```

### Preventing Infinite Loops in Two-Way Binding

The implementation uses two mechanisms:
1. **Guard flag**: `isUpdating` flag prevents re-entrant updates
2. **RxVar deduplication**: RxVar only propagates when `currentValue != newValue`

```scala
var isUpdating = false

// DOM -> Rx
element.addEventListener("input", (e) => {
  if !isUpdating then
    isUpdating = true
    variable := element.value
    isUpdating = false
})

// Rx -> DOM
variable.foreach { newValue =>
  if !isUpdating then
    isUpdating = true
    element.value = newValue
    isUpdating = false
}
```

## Verification

```bash
./sbt uniJS/compile
./sbt domTest/test
./sbt scalafmtAll
```

## Test Cases

```scala
// DOM Properties
test("value property sets current value"):
  val input = input(tpe -> "text", value -> "hello")
  // Verify .value property is set (not attribute)

test("checked property for checkbox"):
  val cb = input(tpe -> "checkbox", checked -> true)
  // Verify .checked is true

// Two-Way Binding
test("value.bind syncs input with RxVar"):
  val v = Rx.variable("initial")
  val elem = input(value.bind(v))
  // Verify: change v -> input updates
  // Verify: type in input -> v updates

test("checked.bind syncs checkbox with RxVar"):
  val c = Rx.variable(false)
  val elem = input(tpe -> "checkbox", checked.bind(c))
  // Verify bidirectional sync

// ARIA Attributes
test("ariaLabel sets aria-label"):
  val btn = button(ariaLabel -> "Close")
  // Verify aria-label="Close"

test("ariaExpanded with reactive value"):
  val expanded = Rx.variable(false)
  val btn = button(ariaExpanded -> expanded)
  // Verify updates when expanded changes
```

## Out of Scope

- Form validation helpers (future work)
- Keyed list rendering (separate PR)
- Complex form state management

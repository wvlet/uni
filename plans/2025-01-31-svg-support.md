# SVG Support with Context-Based Namespace Inheritance

## Problem

The current airframe-rx-html requires users to explicitly import and use separate `svgTags.*` for SVG elements. This is verbose and error-prone because:

1. Tags like `title`, `a`, `style` exist in both HTML and SVG with different namespaces
2. Users must remember to use `svgTags.title` inside SVG vs `html.title` in HTML
3. Every SVG child element must be explicitly imported from the SVG namespace

## Goal

Allow using the same tags for both HTML and SVG, with DomRenderer automatically determining the correct namespace based on parent context:

```scala
import wvlet.uni.dom.all.*

html(
  head(title("Page Title")),        // HTML namespace
  body(
    svg(
      circle(cx -> 50, cy -> 50, r -> 40),
      title("SVG Circle tooltip")   // SVG namespace (inherited from svg parent)
    )
  )
)
```

## Solution: Rendering-Time Namespace Inference

Pass parent namespace as context during rendering. When creating child elements:
- If child has explicit non-XHTML namespace, use it
- If child has default XHTML namespace and parent is SVG, inherit SVG namespace
- Special case: `foreignObject` resets context back to XHTML for its children

### Why This Approach?

1. **Minimal API changes** - existing code works unchanged
2. **Single tag definition** - `title` defined once, works in both contexts
3. **Follows browser behavior** - mirrors how browsers parse SVG
4. **Immutability preserved** - DomElement remains a pure case class

## Implementation

### 1. Modify DomRenderer.scala

Add `parentNamespace` parameter to rendering methods:

```scala
private def resolveNamespace(elem: DomElement, parentName: String, parentNs: DomNamespace): DomNamespace =
  elem.name match
    case "svg" => DomNamespace.svg
    case _ if parentName == "foreignObject" => DomNamespace.xhtml
    case _ if elem.namespace == DomNamespace.xhtml && parentNs == DomNamespace.svg =>
      DomNamespace.svg
    case _ => elem.namespace

private def createDomNode(e: DomElement, parentName: String, parentNs: DomNamespace): dom.Node =
  val ns = resolveNamespace(e, parentName, parentNs)
  ns match
    case DomNamespace.xhtml => dom.document.createElement(e.name)
    case _ => dom.document.createElementNS(ns.uri, e.name)
```

Update `renderToInternal` to pass namespace context through recursive calls.

### 2. Create SvgTags.scala

Define SVG-specific elements (~60 tags):

```scala
trait SvgTags:
  import SvgTags.svgTag

  lazy val svg: DomElement = svgTag("svg")
  lazy val circle: DomElement = svgTag("circle")
  lazy val rect: DomElement = svgTag("rect")
  lazy val path: DomElement = svgTag("path")
  lazy val g: DomElement = svgTag("g")
  // ... ~55 more SVG elements
```

### 3. Create SvgAttrs.scala

Define SVG-specific attributes (~80 attributes):

```scala
trait SvgAttrs:
  lazy val cx: DomAttributeOf = attr("cx")
  lazy val cy: DomAttributeOf = attr("cy")
  lazy val r: DomAttributeOf = attr("r")
  lazy val viewBox: DomAttributeOf = attr("viewBox")
  lazy val fill: DomAttributeOf = attr("fill")
  lazy val stroke: DomAttributeOf = attr("stroke")
  lazy val xlinkHref: DomAttributeOf = attrOf("href", DomNamespace.svgXLink)
  // ... more SVG attributes
```

### 4. Update all.scala

Mix in SVG traits and handle name conflicts:

```scala
object all extends HtmlTags with HtmlAttrs with SvgTags with SvgAttrs:
  // Prefer HTML versions for shared names
  override lazy val style: DomElement = HtmlTags.tag("style")
  override lazy val title: DomElement = HtmlTags.tag("title")

  // Explicit SVG versions when disambiguation needed
  lazy val svgTitle: DomElement = SvgTags.svgTag("title")
  lazy val svgStyle: DomElement = SvgTags.svgTag("style")
  lazy val svgA: DomElement = SvgTags.svgTag("a")
```

### 5. Add Tests

Create SvgElementTest.scala with tests for:
- Basic SVG element creation
- Namespace inheritance
- foreignObject namespace reset
- SVG attributes including xlink

## Files to Modify

| File | Changes |
|------|---------|
| `DomRenderer.scala` | Add namespace context parameter, resolveNamespace function |
| `DomNamespace.scala` | Add helper for namespace resolution (optional) |
| `SvgTags.scala` | New file - SVG element definitions |
| `SvgAttrs.scala` | New file - SVG attribute definitions |
| `all.scala` | Mix in SvgTags, SvgAttrs, handle conflicts |
| `SvgElementTest.scala` | New file - SVG tests |

## Edge Cases

1. **Nested SVG** - Inner `<svg>` stays in SVG namespace
2. **foreignObject** - Children use XHTML namespace
3. **Explicit namespace** - Elements with non-XHTML namespace always use their own
4. **xlink attributes** - Use `DomNamespace.svgXLink` for href, title

## Verification

```bash
./sbt "dom/test"  # Run all uni-dom tests including new SVG tests
```

Test cases:
- `svg(circle(...))` creates elements with SVG namespace
- `svg(title("..."))` - title inherits SVG namespace
- `svg(foreignObject(div(...)))` - div uses XHTML namespace
- SVG attributes like `viewBox`, `fill`, `stroke` work correctly
- `xlinkHref` uses correct XLink namespace

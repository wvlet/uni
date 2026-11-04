/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.dom

/**
  * Represents a CSS style name-value pair.
  */
case class StyleValue(name: String, value: String)

/**
  * Type-safe CSS style property builder.
  */
case class StyleProperty(name: String):
  /**
    * Create a style value pair.
    */
  def :=(value: String): StyleValue = StyleValue(name, value)

/**
  * CSS style object that serves dual purposes:
  *
  *   1. As a `<style>` tag element (inherits from DomElement)
  *   2. As a CSS style attribute builder with type-safe properties
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // CSS style attribute (React/Vue style)
  *   div(style(display := "flex", gap := "8px", color := "blue"))
  *
  *   // Raw style string for Tailwind CSS etc.
  *   div(style := "display: flex; gap: 8px;")
  *
  *   // <style> tag element
  *   head(style("body { margin: 0; }"))
  * }}}
  */
object style extends DomElement("style", DomNamespace.xhtml, Nil):
  /**
    * Create a style attribute from grouped CSS properties (React/Vue style). When called with
    * StyleValue arguments, returns a DomAttribute for inline styles.
    *
    * Note: Requires at least one StyleValue to avoid ambiguity with the inherited `apply(DomNode*)`
    * from DomElement. For empty `<style>` tags, use `style` directly or `style("...")` with string
    * content.
    */
  def apply(first: StyleValue, rest: StyleValue*): DomAttribute = DomAttribute(
    "style",
    (first +: rest).map(v => s"${v.name}: ${v.value};").mkString(" "),
    append = true
  )

  /**
    * Set raw style string directly. Useful for Tailwind CSS or multiple styles.
    */
  def :=(value: String): DomAttribute = DomAttribute("style", value, append = true)

  // Layout
  val display: StyleProperty    = StyleProperty("display")
  val position: StyleProperty   = StyleProperty("position")
  val top: StyleProperty        = StyleProperty("top")
  val right: StyleProperty      = StyleProperty("right")
  val bottom: StyleProperty     = StyleProperty("bottom")
  val left: StyleProperty       = StyleProperty("left")
  val zIndex: StyleProperty     = StyleProperty("z-index")
  val overflow: StyleProperty   = StyleProperty("overflow")
  val overflowX: StyleProperty  = StyleProperty("overflow-x")
  val overflowY: StyleProperty  = StyleProperty("overflow-y")
  val visibility: StyleProperty = StyleProperty("visibility")
  val float: StyleProperty      = StyleProperty("float")
  val clear: StyleProperty      = StyleProperty("clear")

  // Box Model - Dimensions
  val width: StyleProperty     = StyleProperty("width")
  val height: StyleProperty    = StyleProperty("height")
  val minWidth: StyleProperty  = StyleProperty("min-width")
  val maxWidth: StyleProperty  = StyleProperty("max-width")
  val minHeight: StyleProperty = StyleProperty("min-height")
  val maxHeight: StyleProperty = StyleProperty("max-height")

  // Box Model - Margin
  val margin: StyleProperty       = StyleProperty("margin")
  val marginTop: StyleProperty    = StyleProperty("margin-top")
  val marginRight: StyleProperty  = StyleProperty("margin-right")
  val marginBottom: StyleProperty = StyleProperty("margin-bottom")
  val marginLeft: StyleProperty   = StyleProperty("margin-left")

  // Box Model - Padding
  val padding: StyleProperty       = StyleProperty("padding")
  val paddingTop: StyleProperty    = StyleProperty("padding-top")
  val paddingRight: StyleProperty  = StyleProperty("padding-right")
  val paddingBottom: StyleProperty = StyleProperty("padding-bottom")
  val paddingLeft: StyleProperty   = StyleProperty("padding-left")

  // Box Model - Box
  val boxSizing: StyleProperty = StyleProperty("box-sizing")
  val boxShadow: StyleProperty = StyleProperty("box-shadow")

  // Flexbox
  val flexDirection: StyleProperty  = StyleProperty("flex-direction")
  val flexWrap: StyleProperty       = StyleProperty("flex-wrap")
  val flexFlow: StyleProperty       = StyleProperty("flex-flow")
  val justifyContent: StyleProperty = StyleProperty("justify-content")
  val alignItems: StyleProperty     = StyleProperty("align-items")
  val alignContent: StyleProperty   = StyleProperty("align-content")
  val alignSelf: StyleProperty      = StyleProperty("align-self")
  val flex: StyleProperty           = StyleProperty("flex")
  val flexGrow: StyleProperty       = StyleProperty("flex-grow")
  val flexShrink: StyleProperty     = StyleProperty("flex-shrink")
  val flexBasis: StyleProperty      = StyleProperty("flex-basis")
  val order: StyleProperty          = StyleProperty("order")
  val gap: StyleProperty            = StyleProperty("gap")
  val rowGap: StyleProperty         = StyleProperty("row-gap")
  val columnGap: StyleProperty      = StyleProperty("column-gap")

  // Grid
  val gridTemplateColumns: StyleProperty = StyleProperty("grid-template-columns")
  val gridTemplateRows: StyleProperty    = StyleProperty("grid-template-rows")
  val gridTemplateAreas: StyleProperty   = StyleProperty("grid-template-areas")
  val gridTemplate: StyleProperty        = StyleProperty("grid-template")
  val gridColumn: StyleProperty          = StyleProperty("grid-column")
  val gridColumnStart: StyleProperty     = StyleProperty("grid-column-start")
  val gridColumnEnd: StyleProperty       = StyleProperty("grid-column-end")
  val gridRow: StyleProperty             = StyleProperty("grid-row")
  val gridRowStart: StyleProperty        = StyleProperty("grid-row-start")
  val gridRowEnd: StyleProperty          = StyleProperty("grid-row-end")
  val gridArea: StyleProperty            = StyleProperty("grid-area")
  val gridAutoColumns: StyleProperty     = StyleProperty("grid-auto-columns")
  val gridAutoRows: StyleProperty        = StyleProperty("grid-auto-rows")
  val gridAutoFlow: StyleProperty        = StyleProperty("grid-auto-flow")
  val placeItems: StyleProperty          = StyleProperty("place-items")
  val placeContent: StyleProperty        = StyleProperty("place-content")
  val placeSelf: StyleProperty           = StyleProperty("place-self")

  // Typography
  val color: StyleProperty          = StyleProperty("color")
  val fontSize: StyleProperty       = StyleProperty("font-size")
  val fontWeight: StyleProperty     = StyleProperty("font-weight")
  val fontFamily: StyleProperty     = StyleProperty("font-family")
  val fontStyle: StyleProperty      = StyleProperty("font-style")
  val lineHeight: StyleProperty     = StyleProperty("line-height")
  val textAlign: StyleProperty      = StyleProperty("text-align")
  val textDecoration: StyleProperty = StyleProperty("text-decoration")
  val textTransform: StyleProperty  = StyleProperty("text-transform")
  val letterSpacing: StyleProperty  = StyleProperty("letter-spacing")
  val wordSpacing: StyleProperty    = StyleProperty("word-spacing")
  val whiteSpace: StyleProperty     = StyleProperty("white-space")
  val textOverflow: StyleProperty   = StyleProperty("text-overflow")
  val verticalAlign: StyleProperty  = StyleProperty("vertical-align")
  val textShadow: StyleProperty     = StyleProperty("text-shadow")

  // Background
  val background: StyleProperty           = StyleProperty("background")
  val backgroundColor: StyleProperty      = StyleProperty("background-color")
  val backgroundImage: StyleProperty      = StyleProperty("background-image")
  val backgroundSize: StyleProperty       = StyleProperty("background-size")
  val backgroundPosition: StyleProperty   = StyleProperty("background-position")
  val backgroundRepeat: StyleProperty     = StyleProperty("background-repeat")
  val backgroundAttachment: StyleProperty = StyleProperty("background-attachment")

  // Border
  val border: StyleProperty        = StyleProperty("border")
  val borderWidth: StyleProperty   = StyleProperty("border-width")
  val borderStyle: StyleProperty   = StyleProperty("border-style")
  val borderColor: StyleProperty   = StyleProperty("border-color")
  val borderRadius: StyleProperty  = StyleProperty("border-radius")
  val borderTop: StyleProperty     = StyleProperty("border-top")
  val borderRight: StyleProperty   = StyleProperty("border-right")
  val borderBottom: StyleProperty  = StyleProperty("border-bottom")
  val borderLeft: StyleProperty    = StyleProperty("border-left")
  val outline: StyleProperty       = StyleProperty("outline")
  val outlineWidth: StyleProperty  = StyleProperty("outline-width")
  val outlineStyle: StyleProperty  = StyleProperty("outline-style")
  val outlineColor: StyleProperty  = StyleProperty("outline-color")
  val outlineOffset: StyleProperty = StyleProperty("outline-offset")

  // Transform & Animation
  val transform: StyleProperty                = StyleProperty("transform")
  val transformOrigin: StyleProperty          = StyleProperty("transform-origin")
  val transition: StyleProperty               = StyleProperty("transition")
  val transitionProperty: StyleProperty       = StyleProperty("transition-property")
  val transitionDuration: StyleProperty       = StyleProperty("transition-duration")
  val transitionTimingFunction: StyleProperty = StyleProperty("transition-timing-function")
  val transitionDelay: StyleProperty          = StyleProperty("transition-delay")
  val animation: StyleProperty                = StyleProperty("animation")
  val animationName: StyleProperty            = StyleProperty("animation-name")
  val animationDuration: StyleProperty        = StyleProperty("animation-duration")
  val animationTimingFunction: StyleProperty  = StyleProperty("animation-timing-function")
  val animationDelay: StyleProperty           = StyleProperty("animation-delay")
  val animationIterationCount: StyleProperty  = StyleProperty("animation-iteration-count")
  val animationDirection: StyleProperty       = StyleProperty("animation-direction")
  val animationFillMode: StyleProperty        = StyleProperty("animation-fill-mode")
  val animationPlayState: StyleProperty       = StyleProperty("animation-play-state")

  // Effects
  val opacity: StyleProperty        = StyleProperty("opacity")
  val filter: StyleProperty         = StyleProperty("filter")
  val backdropFilter: StyleProperty = StyleProperty("backdrop-filter")
  val mixBlendMode: StyleProperty   = StyleProperty("mix-blend-mode")

  // Interaction
  val cursor: StyleProperty        = StyleProperty("cursor")
  val pointerEvents: StyleProperty = StyleProperty("pointer-events")
  val userSelect: StyleProperty    = StyleProperty("user-select")
  val resize: StyleProperty        = StyleProperty("resize")

  // Scroll
  val scrollBehavior: StyleProperty  = StyleProperty("scroll-behavior")
  val scrollSnapType: StyleProperty  = StyleProperty("scroll-snap-type")
  val scrollSnapAlign: StyleProperty = StyleProperty("scroll-snap-align")

  // Misc
  val content: StyleProperty        = StyleProperty("content")
  val listStyle: StyleProperty      = StyleProperty("list-style")
  val listStyleType: StyleProperty  = StyleProperty("list-style-type")
  val objectFit: StyleProperty      = StyleProperty("object-fit")
  val objectPosition: StyleProperty = StyleProperty("object-position")
  val aspectRatio: StyleProperty    = StyleProperty("aspect-ratio")

end style

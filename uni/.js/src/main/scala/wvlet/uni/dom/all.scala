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
  * Convenience import for all DOM utilities including SVG.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   val app = div(
  *     cls -> "container",
  *     h1("Hello World"),
  *     svg(
  *       viewBox -> "0 0 100 100",
  *       circle(cx -> 50, cy -> 50, r -> 40, fill -> "blue")
  *     )
  *   )
  *
  *   app.renderTo("app")
  * }}}
  *
  * SVG elements inside an `svg` tag automatically inherit the SVG namespace during rendering. Tags
  * that exist in both HTML and SVG (like `title`, `a`, `style`) will use the appropriate namespace
  * based on their parent context.
  */
object all extends HtmlTags with HtmlAttrs with SvgTags with SvgAttrs:

  // Resolve conflicts between HtmlTags and HtmlAttrs by preferring tags
  // Use styleAttr and titleAttr for the attribute versions
  override lazy val title: DomElement = HtmlTags.tag("title")
  override lazy val form: DomElement  = HtmlTags.tag("form")
  override lazy val span: DomElement  = HtmlTags.tag("span")

  // Override style from HtmlTags with our CSS style object (which extends DomElement)
  // This allows: style(display := "flex") for CSS, and style("...") for <style> tag
  override lazy val style: wvlet.uni.dom.style.type = wvlet.uni.dom.style

  /**
    * Re-export core types for convenience.
    */
  export wvlet.uni.dom.DomNode
  export wvlet.uni.dom.DomElement
  export wvlet.uni.dom.DomAttribute
  export wvlet.uni.dom.DomNamespace
  export wvlet.uni.dom.RxElement
  export wvlet.uni.dom.RawHtml
  export wvlet.uni.dom.EntityRef
  export wvlet.uni.dom.Embedded
  export wvlet.uni.dom.DomRenderer
  export wvlet.uni.dom.ClassToggle
  export wvlet.uni.dom.StyleValue
  export wvlet.uni.dom.StyleProperty
  export wvlet.uni.dom.ValueBinding
  export wvlet.uni.dom.CheckedBinding

  // Browser API integrations
  export wvlet.uni.dom.Storage
  export wvlet.uni.dom.StorageVar
  export wvlet.uni.dom.StorageCodec
  export wvlet.uni.dom.Portal
  export wvlet.uni.dom.PortalNode
  export wvlet.uni.dom.PortalToBody
  export wvlet.uni.dom.PortalToElement
  export wvlet.uni.dom.Intersection
  export wvlet.uni.dom.IntersectionConfig
  export wvlet.uni.dom.IntersectionEntry
  export wvlet.uni.dom.IntersectionBinding
  export wvlet.uni.dom.IntersectionEntryBinding
  export wvlet.uni.dom.IntersectionOnceBinding
  export wvlet.uni.dom.Resize
  export wvlet.uni.dom.ResizeEntry
  export wvlet.uni.dom.ResizeBinding
  export wvlet.uni.dom.ResizeEntryBinding
  export wvlet.uni.dom.ResizeBindingDebounced

  // Ref system for direct DOM access
  export wvlet.uni.dom.DomRef
  export wvlet.uni.dom.RefBinding
  export wvlet.uni.dom.ref

  // Responsive design
  export wvlet.uni.dom.MediaQuery

  // Animation
  export wvlet.uni.dom.AnimationFrame

  // Network status
  export wvlet.uni.dom.NetworkStatus

  // Window events
  export wvlet.uni.dom.WindowScroll
  export wvlet.uni.dom.WindowVisibility
  export wvlet.uni.dom.WindowDimensions

  // Routing
  export wvlet.uni.dom.Router
  export wvlet.uni.dom.RouterInstance
  export wvlet.uni.dom.Route
  export wvlet.uni.dom.RouteParams
  export wvlet.uni.dom.Location

  // Keyboard shortcuts
  export wvlet.uni.dom.Keyboard
  export wvlet.uni.dom.KeyCombination
  export wvlet.uni.dom.Modifiers

  // Focus management
  export wvlet.uni.dom.Focus

  // Clipboard
  export wvlet.uni.dom.Clipboard

  // Drag and Drop
  export wvlet.uni.dom.DragDrop
  export wvlet.uni.dom.DragData
  export wvlet.uni.dom.DragState

  // Geolocation
  export wvlet.uni.dom.Geolocation
  export wvlet.uni.dom.GeoPosition
  export wvlet.uni.dom.GeoError
  export wvlet.uni.dom.GeoOptions

  // Form validation
  export wvlet.uni.dom.Validate
  export wvlet.uni.dom.ValidationState
  export wvlet.uni.dom.ValidationRule
  export wvlet.uni.dom.FieldValidation
  export wvlet.uni.dom.FormValidation

  // Transitions
  export wvlet.uni.dom.Transition
  export wvlet.uni.dom.TransitionConfig

  // Click outside detection
  export wvlet.uni.dom.ClickOutside
  export wvlet.uni.dom.ClickOutsideBinding

  /**
    * Re-export helper functions.
    */
  export wvlet.uni.dom.when
  export wvlet.uni.dom.unless
  export wvlet.uni.dom.embedAsNode

  /**
    * Implicit conversions for embedding common types as DomNodes.
    */
  import scala.language.implicitConversions
  import wvlet.uni.rx.{Rx, RxOption}

  given stringToDomNode: Conversion[String, DomNode]                           = s => Embedded(s)
  given intToDomNode: Conversion[Int, DomNode]                                 = i => Embedded(i)
  given longToDomNode: Conversion[Long, DomNode]                               = l => Embedded(l)
  given floatToDomNode: Conversion[Float, DomNode]                             = f => Embedded(f)
  given doubleToDomNode: Conversion[Double, DomNode]                           = d => Embedded(d)
  given booleanToDomNode: Conversion[Boolean, DomNode]                         = b => Embedded(b)
  given charToDomNode: Conversion[Char, DomNode]                               = c => Embedded(c)
  given rxToDomNode[A: EmbeddableNode]: Conversion[Rx[A], DomNode]             = rx => Embedded(rx)
  given rxOptionToDomNode[A: EmbeddableNode]: Conversion[RxOption[A], DomNode] = rx => Embedded(rx)
  given optionToDomNode[A: EmbeddableNode]: Conversion[Option[A], DomNode] = opt => Embedded(opt)
  given seqToDomNode[A: EmbeddableNode]: Conversion[Seq[A], DomNode]       = seq => Embedded(seq)
  given listToDomNode[A: EmbeddableNode]: Conversion[List[A], DomNode]     = lst => Embedded(lst)

  /**
    * Extension method to render an RxElement to the DOM.
    */
  extension (element: RxElement)
    /**
      * Render this element to a DOM node with the given id.
      *
      * @param nodeId
      *   The id of the target element
      * @return
      *   A RxDomNode containing the rendered node and a Cancelable for cleanup
      */
    def renderTo(nodeId: String): RxDomNode =
      val (node, cancelable) = DomRenderer.renderToNode(nodeId, element)
      RxDomNode(node, cancelable)

end all

/**
  * Import for HTML tags only.
  */
object tags extends HtmlTags

/**
  * Import for HTML attributes only.
  */
object attrs extends HtmlAttrs

/**
  * Import for SVG tags only.
  */
object svgElements extends SvgTags

/**
  * Import for SVG attributes only.
  */
object svgProperties extends SvgAttrs

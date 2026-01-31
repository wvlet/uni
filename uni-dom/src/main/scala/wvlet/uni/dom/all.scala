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
  override lazy val style: DomElement = HtmlTags.tag("style")
  override lazy val title: DomElement = HtmlTags.tag("title")
  override lazy val form: DomElement  = HtmlTags.tag("form")
  override lazy val span: DomElement  = HtmlTags.tag("span")

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

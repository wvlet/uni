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
  * Factory methods for creating DOM elements and attributes.
  */
object HtmlTags:
  /**
    * Create an HTML element with the given tag name.
    */
  def tag(name: String): DomElement = DomElement(name)

  /**
    * Create an element with a specific namespace.
    */
  def tagOf(name: String, namespace: DomNamespace): DomElement = DomElement(name, namespace)

  /**
    * Create an attribute builder.
    */
  def attr(name: String): DomAttributeOf = DomAttributeOf(name)

  /**
    * Create an attribute builder with a specific namespace.
    */
  def attrOf(name: String, namespace: DomNamespace): DomAttributeOf = DomAttributeOf(
    name,
    namespace
  )

  /**
    * Create an event handler builder.
    */
  def handler[E](name: String): DomEventHandlerOf[E] = DomEventHandlerOf[E](name)

end HtmlTags

/**
  * Common HTML tags.
  */
trait HtmlTags:
  import HtmlTags.*

  // Document structure
  lazy val html: DomElement  = tag("html")
  lazy val head: DomElement  = tag("head")
  lazy val body: DomElement  = tag("body")
  lazy val title: DomElement = tag("title")

  // Container elements
  lazy val div: DomElement     = tag("div")
  lazy val span: DomElement    = tag("span")
  lazy val main: DomElement    = tag("main")
  lazy val header: DomElement  = tag("header")
  lazy val footer: DomElement  = tag("footer")
  lazy val nav: DomElement     = tag("nav")
  lazy val section: DomElement = tag("section")
  lazy val article: DomElement = tag("article")
  lazy val aside: DomElement   = tag("aside")

  // Text content
  lazy val h1: DomElement         = tag("h1")
  lazy val h2: DomElement         = tag("h2")
  lazy val h3: DomElement         = tag("h3")
  lazy val h4: DomElement         = tag("h4")
  lazy val h5: DomElement         = tag("h5")
  lazy val h6: DomElement         = tag("h6")
  lazy val p: DomElement          = tag("p")
  lazy val pre: DomElement        = tag("pre")
  lazy val code: DomElement       = tag("code")
  lazy val blockquote: DomElement = tag("blockquote")
  lazy val hr: DomElement         = tag("hr")
  lazy val br: DomElement         = tag("br")

  // Inline text
  lazy val strong: DomElement = tag("strong")
  lazy val em: DomElement     = tag("em")
  lazy val b: DomElement      = tag("b")
  lazy val i: DomElement      = tag("i")
  lazy val u: DomElement      = tag("u")
  lazy val small: DomElement  = tag("small")
  lazy val sub: DomElement    = tag("sub")
  lazy val sup: DomElement    = tag("sup")
  lazy val mark: DomElement   = tag("mark")
  lazy val abbr: DomElement   = tag("abbr")

  // Links and media
  lazy val a: DomElement      = tag("a")
  lazy val img: DomElement    = tag("img")
  lazy val video: DomElement  = tag("video")
  lazy val audio: DomElement  = tag("audio")
  lazy val source: DomElement = tag("source")
  lazy val iframe: DomElement = tag("iframe")
  lazy val canvas: DomElement = tag("canvas")

  // Lists
  lazy val ul: DomElement = tag("ul")
  lazy val ol: DomElement = tag("ol")
  lazy val li: DomElement = tag("li")
  lazy val dl: DomElement = tag("dl")
  lazy val dt: DomElement = tag("dt")
  lazy val dd: DomElement = tag("dd")

  // Tables
  lazy val table: DomElement    = tag("table")
  lazy val thead: DomElement    = tag("thead")
  lazy val tbody: DomElement    = tag("tbody")
  lazy val tfoot: DomElement    = tag("tfoot")
  lazy val tr: DomElement       = tag("tr")
  lazy val th: DomElement       = tag("th")
  lazy val td: DomElement       = tag("td")
  lazy val caption: DomElement  = tag("caption")
  lazy val colgroup: DomElement = tag("colgroup")
  lazy val col: DomElement      = tag("col")

  // Forms
  lazy val form: DomElement     = tag("form")
  lazy val input: DomElement    = tag("input")
  lazy val button: DomElement   = tag("button")
  lazy val select: DomElement   = tag("select")
  lazy val option: DomElement   = tag("option")
  lazy val optgroup: DomElement = tag("optgroup")
  lazy val textarea: DomElement = tag("textarea")
  lazy val label: DomElement    = tag("label")
  lazy val fieldset: DomElement = tag("fieldset")
  lazy val legend: DomElement   = tag("legend")
  lazy val datalist: DomElement = tag("datalist")
  lazy val output: DomElement   = tag("output")
  lazy val progress: DomElement = tag("progress")
  lazy val meter: DomElement    = tag("meter")

  // Script and style
  lazy val script: DomElement   = tag("script")
  lazy val noscript: DomElement = tag("noscript")
  lazy val link: DomElement     = tag("link")
  lazy val style: DomElement    = tag("style")
  lazy val meta: DomElement     = tag("meta")

  // Other elements
  lazy val template: DomElement   = tag("template")
  lazy val slot: DomElement       = tag("slot")
  lazy val details: DomElement    = tag("details")
  lazy val summary: DomElement    = tag("summary")
  lazy val dialog: DomElement     = tag("dialog")
  lazy val figure: DomElement     = tag("figure")
  lazy val figcaption: DomElement = tag("figcaption")
  lazy val address: DomElement    = tag("address")
  lazy val time: DomElement       = tag("time")
  lazy val data: DomElement       = tag("data")
  lazy val wbr: DomElement        = tag("wbr")

end HtmlTags

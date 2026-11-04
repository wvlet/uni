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

import wvlet.uni.rx.RxVar

/**
  * Global HTML attributes that can be used on any element.
  */
trait GlobalAttrs:
  import HtmlTags.attr

  // Core attributes
  lazy val id: DomAttributeOf        = attr("id")
  lazy val `class`: DomAttributeOf   = attr("class")
  lazy val className: DomAttributeOf = `class`

  /**
    * CSS class attribute with toggle support.
    *
    * Usage:
    * {{{
    *   div(cls := "base-class")
    *   div(cls.toggle("active") when isActive)
    * }}}
    */
  object cls extends DomAttributeOf("class"):
    /**
      * Alias for apply. Allows `cls := "value"` syntax.
      */
    def :=[V: EmbeddableAttribute](v: V): DomNode = apply(v)

    /**
      * Create a class toggle for conditional class application.
      */
    def toggle(className: String): ClassToggle = ClassToggle(className)

  lazy val styleAttr: DomAttributeOf       = attr("style")
  lazy val titleAttr: DomAttributeOf       = attr("title")
  lazy val lang: DomAttributeOf            = attr("lang")
  lazy val dir: DomAttributeOf             = attr("dir")
  lazy val tabindex: DomAttributeOf        = attr("tabindex")
  lazy val accesskey: DomAttributeOf       = attr("accesskey")
  lazy val contenteditable: DomAttributeOf = attr("contenteditable")
  lazy val draggable: DomAttributeOf       = attr("draggable")
  lazy val spellcheck: DomAttributeOf      = attr("spellcheck")
  lazy val translate: DomAttributeOf       = attr("translate")

  // Boolean attributes
  lazy val hidden: DomBooleanAttribute = attr("hidden").boolean
  lazy val inert: DomBooleanAttribute  = attr("inert").boolean

  // Data attributes
  def data(suffix: String): DomAttributeOf = attr(s"data-${suffix}")

end GlobalAttrs

/**
  * Input-related HTML attributes.
  */
trait InputAttrs extends GlobalAttrs:
  import HtmlTags.attr

  // Type attributes
  lazy val `type`: DomAttributeOf = attr("type")
  lazy val tpe: DomAttributeOf    = `type`

  // Value attributes
  lazy val name: DomAttributeOf         = attr("name")
  lazy val placeholder: DomAttributeOf  = attr("placeholder")
  lazy val defaultValue: DomAttributeOf = attr("defaultValue")

  /**
    * Value attribute/property with two-way binding support.
    *
    * Usage:
    * {{{
    *   // One-way binding (Rx -> DOM)
    *   input(value -> "initial")
    *   input(value -> someRx)
    *
    *   // Two-way binding (Rx <-> DOM)
    *   val username = Rx.variable("")
    *   input(value.bind(username))
    * }}}
    */
  object value extends DomAttributeOf("value"):
    /**
      * Create a two-way binding between an RxVar and the input's value property. Updates flow
      * bidirectionally: changes to the RxVar update the DOM, and user input updates the RxVar.
      *
      * @param variable
      *   The RxVar to sync with the input value
      */
    def bind(variable: RxVar[String]): DomNode = ValueBinding(variable)

    /**
      * Create a two-way binding that uses the "change" event instead of "input" event. Useful for
      * select elements where you want updates only on selection change.
      */
    def bindOnChange(variable: RxVar[String]): DomNode = ValueBinding(
      variable,
      useChangeEvent = true
    )

  // Validation attributes
  lazy val min: DomAttributeOf       = attr("min")
  lazy val max: DomAttributeOf       = attr("max")
  lazy val step: DomAttributeOf      = attr("step")
  lazy val minlength: DomAttributeOf = attr("minlength")
  lazy val maxlength: DomAttributeOf = attr("maxlength")
  lazy val pattern: DomAttributeOf   = attr("pattern")

  // Boolean input attributes
  lazy val disabled: DomBooleanAttribute  = attr("disabled").boolean
  lazy val readonly: DomBooleanAttribute  = attr("readonly").boolean
  lazy val required: DomBooleanAttribute  = attr("required").boolean
  lazy val autofocus: DomBooleanAttribute = attr("autofocus").boolean
  lazy val multiple: DomBooleanAttribute  = attr("multiple").boolean

  // For backward compatibility: checked without arguments sets the boolean attribute
  lazy val checkedAttr: DomBooleanAttribute = attr("checked").boolean

  /**
    * Checked attribute/property with two-way binding support.
    *
    * Usage:
    * {{{
    *   // Boolean attribute (no value) - use checkedAttr or checked -> true
    *   input(tpe -> "checkbox", checkedAttr)
    *   input(tpe -> "checkbox", checked -> true)
    *
    *   // Two-way binding (Rx <-> DOM)
    *   val isChecked = Rx.variable(false)
    *   input(tpe -> "checkbox", checked.bind(isChecked))
    * }}}
    */
  object checked extends DomAttributeOf("checked"):
    /**
      * Create a two-way binding between an RxVar and the checkbox's checked property.
      *
      * @param variable
      *   The RxVar[Boolean] to sync with the checkbox state
      */
    def bind(variable: RxVar[Boolean]): DomNode = CheckedBinding(variable)

  // Other input attributes
  lazy val autocomplete: DomAttributeOf   = attr("autocomplete")
  lazy val autocapitalize: DomAttributeOf = attr("autocapitalize")
  lazy val inputmode: DomAttributeOf      = attr("inputmode")
  lazy val list: DomAttributeOf           = attr("list")
  lazy val accept: DomAttributeOf         = attr("accept")
  lazy val size: DomAttributeOf           = attr("size")
  lazy val rows: DomAttributeOf           = attr("rows")
  lazy val cols: DomAttributeOf           = attr("cols")
  lazy val wrap: DomAttributeOf           = attr("wrap")

end InputAttrs

/**
  * Link and media-related HTML attributes.
  */
trait LinkAttrs:
  import HtmlTags.attr

  lazy val href: DomAttributeOf           = attr("href")
  lazy val src: DomAttributeOf            = attr("src")
  lazy val alt: DomAttributeOf            = attr("alt")
  lazy val rel: DomAttributeOf            = attr("rel")
  lazy val target: DomAttributeOf         = attr("target")
  lazy val download: DomAttributeOf       = attr("download")
  lazy val hreflang: DomAttributeOf       = attr("hreflang")
  lazy val media: DomAttributeOf          = attr("media")
  lazy val ping: DomAttributeOf           = attr("ping")
  lazy val referrerpolicy: DomAttributeOf = attr("referrerpolicy")

  // Image attributes
  lazy val width: DomAttributeOf       = attr("width")
  lazy val height: DomAttributeOf      = attr("height")
  lazy val loading: DomAttributeOf     = attr("loading")
  lazy val decoding: DomAttributeOf    = attr("decoding")
  lazy val srcset: DomAttributeOf      = attr("srcset")
  lazy val sizes: DomAttributeOf       = attr("sizes")
  lazy val crossorigin: DomAttributeOf = attr("crossorigin")
  lazy val usemap: DomAttributeOf      = attr("usemap")
  lazy val ismap: DomBooleanAttribute  = attr("ismap").boolean

  // Media attributes
  lazy val autoplay: DomBooleanAttribute = attr("autoplay").boolean
  lazy val controls: DomBooleanAttribute = attr("controls").boolean
  lazy val loop: DomBooleanAttribute     = attr("loop").boolean
  lazy val muted: DomBooleanAttribute    = attr("muted").boolean
  lazy val poster: DomAttributeOf        = attr("poster")
  lazy val preload: DomAttributeOf       = attr("preload")

end LinkAttrs

/**
  * Form-related HTML attributes.
  */
trait FormAttrs:
  import HtmlTags.attr

  lazy val action: DomAttributeOf   = attr("action")
  lazy val method: DomAttributeOf   = attr("method")
  lazy val enctype: DomAttributeOf  = attr("enctype")
  lazy val `for`: DomAttributeOf    = attr("for")
  lazy val forId: DomAttributeOf    = `for`
  lazy val formAttr: DomAttributeOf = attr(
    "form"
  ) // HTML form attribute (for associating with a form)
  lazy val formaction: DomAttributeOf          = attr("formaction")
  lazy val formmethod: DomAttributeOf          = attr("formmethod")
  lazy val formenctype: DomAttributeOf         = attr("formenctype")
  lazy val formtarget: DomAttributeOf          = attr("formtarget")
  lazy val formnovalidate: DomBooleanAttribute = attr("formnovalidate").boolean
  lazy val novalidate: DomBooleanAttribute     = attr("novalidate").boolean

end FormAttrs

/**
  * Table-related HTML attributes.
  */
trait TableAttrs:
  import HtmlTags.attr

  lazy val colspan: DomAttributeOf = attr("colspan")
  lazy val rowspan: DomAttributeOf = attr("rowspan")
  lazy val headers: DomAttributeOf = attr("headers")
  lazy val scope: DomAttributeOf   = attr("scope")
  lazy val colSpan: DomAttributeOf = attr("span") // HTML span attribute for col/colgroup

end TableAttrs

/**
  * Script and style HTML attributes.
  */
trait ScriptAttrs:
  import HtmlTags.attr

  lazy val async: DomBooleanAttribute    = attr("async").boolean
  lazy val defer: DomBooleanAttribute    = attr("defer").boolean
  lazy val integrity: DomAttributeOf     = attr("integrity")
  lazy val nomodule: DomBooleanAttribute = attr("nomodule").boolean
  lazy val nonce: DomAttributeOf         = attr("nonce")
  lazy val charset: DomAttributeOf       = attr("charset")

end ScriptAttrs

/**
  * Meta and link HTML attributes.
  */
trait MetaAttrs:
  import HtmlTags.attr

  lazy val content: DomAttributeOf   = attr("content")
  lazy val httpEquiv: DomAttributeOf = attr("http-equiv")

end MetaAttrs

/**
  * Other miscellaneous HTML attributes.
  */
trait MiscAttrs:
  import HtmlTags.attr

  lazy val open: DomBooleanAttribute      = attr("open").boolean
  lazy val selected: DomBooleanAttribute  = attr("selected").boolean
  lazy val `default`: DomBooleanAttribute = attr("default").boolean
  lazy val datetime: DomAttributeOf       = attr("datetime")
  lazy val cite: DomAttributeOf           = attr("cite")
  lazy val sandbox: DomAttributeOf        = attr("sandbox")
  lazy val srcdoc: DomAttributeOf         = attr("srcdoc")
  lazy val allow: DomAttributeOf          = attr("allow")

end MiscAttrs

/**
  * Combined HTML attributes trait.
  */
trait HtmlAttrs
    extends InputAttrs
    with LinkAttrs
    with FormAttrs
    with TableAttrs
    with ScriptAttrs
    with MetaAttrs
    with MiscAttrs
    with HtmlEvents
    with AriaAttrs

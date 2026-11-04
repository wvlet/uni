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
  * Type-safe ARIA (Accessible Rich Internet Applications) attributes.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   button(
  *     ariaLabel -> "Close dialog",
  *     ariaExpanded -> isExpanded,
  *     ariaControls -> "dialog-content"
  *   )
  *
  *   // For uncommon attributes, use the dynamic method
  *   div(aria("atomic") -> "true")
  * }}}
  */
trait AriaAttrs:
  import HtmlTags.attr

  // Role attribute (defines the semantic role of an element)
  lazy val role: DomAttributeOf = attr("role")

  // Labels and descriptions
  lazy val ariaLabel: DomAttributeOf       = attr("aria-label")
  lazy val ariaLabelledby: DomAttributeOf  = attr("aria-labelledby")
  lazy val ariaDescribedby: DomAttributeOf = attr("aria-describedby")
  lazy val ariaPlaceholder: DomAttributeOf = attr("aria-placeholder")

  // State attributes
  lazy val ariaHidden: DomAttributeOf   = attr("aria-hidden")
  lazy val ariaExpanded: DomAttributeOf = attr("aria-expanded")
  lazy val ariaSelected: DomAttributeOf = attr("aria-selected")
  lazy val ariaChecked: DomAttributeOf  = attr("aria-checked")
  lazy val ariaDisabled: DomAttributeOf = attr("aria-disabled")
  lazy val ariaPressed: DomAttributeOf  = attr("aria-pressed")
  lazy val ariaBusy: DomAttributeOf     = attr("aria-busy")
  lazy val ariaCurrent: DomAttributeOf  = attr("aria-current")
  lazy val ariaInvalid: DomAttributeOf  = attr("aria-invalid")
  lazy val ariaRequired: DomAttributeOf = attr("aria-required")
  lazy val ariaReadonly: DomAttributeOf = attr("aria-readonly")

  // Relationship attributes
  lazy val ariaOwns: DomAttributeOf             = attr("aria-owns")
  lazy val ariaControls: DomAttributeOf         = attr("aria-controls")
  lazy val ariaFlowto: DomAttributeOf           = attr("aria-flowto")
  lazy val ariaActivedescendant: DomAttributeOf = attr("aria-activedescendant")
  lazy val ariaErrormessage: DomAttributeOf     = attr("aria-errormessage")
  lazy val ariaDetails: DomAttributeOf          = attr("aria-details")

  // Live region attributes
  lazy val ariaLive: DomAttributeOf     = attr("aria-live")
  lazy val ariaAtomic: DomAttributeOf   = attr("aria-atomic")
  lazy val ariaRelevant: DomAttributeOf = attr("aria-relevant")

  // Widget value attributes
  lazy val ariaValuemin: DomAttributeOf  = attr("aria-valuemin")
  lazy val ariaValuemax: DomAttributeOf  = attr("aria-valuemax")
  lazy val ariaValuenow: DomAttributeOf  = attr("aria-valuenow")
  lazy val ariaValuetext: DomAttributeOf = attr("aria-valuetext")

  // Popup attributes
  lazy val ariaHaspopup: DomAttributeOf = attr("aria-haspopup")
  lazy val ariaModal: DomAttributeOf    = attr("aria-modal")

  // Position attributes
  lazy val ariaLevel: DomAttributeOf    = attr("aria-level")
  lazy val ariaSetsize: DomAttributeOf  = attr("aria-setsize")
  lazy val ariaPosinset: DomAttributeOf = attr("aria-posinset")
  lazy val ariaColcount: DomAttributeOf = attr("aria-colcount")
  lazy val ariaColindex: DomAttributeOf = attr("aria-colindex")
  lazy val ariaColspan: DomAttributeOf  = attr("aria-colspan")
  lazy val ariaRowcount: DomAttributeOf = attr("aria-rowcount")
  lazy val ariaRowindex: DomAttributeOf = attr("aria-rowindex")
  lazy val ariaRowspan: DomAttributeOf  = attr("aria-rowspan")

  // Sort attribute
  lazy val ariaSort: DomAttributeOf = attr("aria-sort")

  // Orientation
  lazy val ariaOrientation: DomAttributeOf = attr("aria-orientation")

  // Autocomplete
  lazy val ariaAutocomplete: DomAttributeOf = attr("aria-autocomplete")

  // Multiline/multiselectable
  lazy val ariaMultiline: DomAttributeOf       = attr("aria-multiline")
  lazy val ariaMultiselectable: DomAttributeOf = attr("aria-multiselectable")

  /**
    * Dynamic ARIA attribute for uncommon attributes.
    *
    * @param suffix
    *   The suffix after "aria-" (e.g., "roledescription" for aria-roledescription)
    */
  def aria(suffix: String): DomAttributeOf = attr(s"aria-${suffix}")

end AriaAttrs

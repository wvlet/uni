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
  * Factory methods for creating SVG elements.
  */
object SvgTags:
  /**
    * Create an SVG element with the given tag name.
    */
  def svgTag(name: String): DomElement = DomElement(name, DomNamespace.svg)

/**
  * SVG element definitions.
  *
  * These elements are explicitly defined with SVG namespace. When used inside an `svg` element,
  * child elements with default XHTML namespace will automatically inherit SVG namespace during
  * rendering.
  */
trait SvgTags:
  import SvgTags.svgTag

  // Root element
  lazy val svg: DomElement = svgTag("svg")

  // Container elements
  lazy val g: DomElement         = svgTag("g")
  lazy val defs: DomElement      = svgTag("defs")
  lazy val symbol: DomElement    = svgTag("symbol")
  lazy val use: DomElement       = svgTag("use")
  lazy val svgSwitch: DomElement = svgTag("switch")

  // Basic shapes
  lazy val circle: DomElement   = svgTag("circle")
  lazy val ellipse: DomElement  = svgTag("ellipse")
  lazy val line: DomElement     = svgTag("line")
  lazy val path: DomElement     = svgTag("path")
  lazy val polygon: DomElement  = svgTag("polygon")
  lazy val polyline: DomElement = svgTag("polyline")
  lazy val rect: DomElement     = svgTag("rect")

  // Text elements
  lazy val svgText: DomElement  = svgTag("text")
  lazy val tspan: DomElement    = svgTag("tspan")
  lazy val textPath: DomElement = svgTag("textPath")

  // Gradient and pattern
  lazy val linearGradient: DomElement = svgTag("linearGradient")
  lazy val radialGradient: DomElement = svgTag("radialGradient")
  lazy val stop: DomElement           = svgTag("stop")
  lazy val svgPattern: DomElement     = svgTag("pattern")

  // Clipping and masking
  lazy val clipPath: DomElement = svgTag("clipPath")
  lazy val svgMask: DomElement  = svgTag("mask")
  lazy val marker: DomElement   = svgTag("marker")

  // Filter effects
  lazy val svgFilter: DomElement           = svgTag("filter")
  lazy val feBlend: DomElement             = svgTag("feBlend")
  lazy val feColorMatrix: DomElement       = svgTag("feColorMatrix")
  lazy val feComponentTransfer: DomElement = svgTag("feComponentTransfer")
  lazy val feComposite: DomElement         = svgTag("feComposite")
  lazy val feConvolveMatrix: DomElement    = svgTag("feConvolveMatrix")
  lazy val feDiffuseLighting: DomElement   = svgTag("feDiffuseLighting")
  lazy val feDisplacementMap: DomElement   = svgTag("feDisplacementMap")
  lazy val feDistantLight: DomElement      = svgTag("feDistantLight")
  lazy val feFlood: DomElement             = svgTag("feFlood")
  lazy val feFuncA: DomElement             = svgTag("feFuncA")
  lazy val feFuncB: DomElement             = svgTag("feFuncB")
  lazy val feFuncG: DomElement             = svgTag("feFuncG")
  lazy val feFuncR: DomElement             = svgTag("feFuncR")
  lazy val feGaussianBlur: DomElement      = svgTag("feGaussianBlur")
  lazy val feImage: DomElement             = svgTag("feImage")
  lazy val feMerge: DomElement             = svgTag("feMerge")
  lazy val feMergeNode: DomElement         = svgTag("feMergeNode")
  lazy val feMorphology: DomElement        = svgTag("feMorphology")
  lazy val feOffset: DomElement            = svgTag("feOffset")
  lazy val fePointLight: DomElement        = svgTag("fePointLight")
  lazy val feSpecularLighting: DomElement  = svgTag("feSpecularLighting")
  lazy val feSpotLight: DomElement         = svgTag("feSpotLight")
  lazy val feTile: DomElement              = svgTag("feTile")
  lazy val feTurbulence: DomElement        = svgTag("feTurbulence")

  // Animation elements
  lazy val animate: DomElement          = svgTag("animate")
  lazy val animateMotion: DomElement    = svgTag("animateMotion")
  lazy val animateTransform: DomElement = svgTag("animateTransform")
  lazy val svgSet: DomElement           = svgTag("set")
  lazy val mpath: DomElement            = svgTag("mpath")

  // Other elements
  lazy val foreignObject: DomElement = svgTag("foreignObject")
  lazy val svgImage: DomElement      = svgTag("image")
  lazy val desc: DomElement          = svgTag("desc")
  lazy val metadata: DomElement      = svgTag("metadata")
  lazy val svgView: DomElement       = svgTag("view")

  // Explicit SVG-namespaced versions for disambiguation
  lazy val svgTitle: DomElement  = svgTag("title")
  lazy val svgStyle: DomElement  = svgTag("style")
  lazy val svgA: DomElement      = svgTag("a")
  lazy val svgScript: DomElement = svgTag("script")

end SvgTags

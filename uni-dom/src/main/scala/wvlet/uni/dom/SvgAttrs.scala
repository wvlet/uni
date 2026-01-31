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
  * SVG-specific attributes.
  */
trait SvgAttrs:
  import HtmlTags.{attr, attrOf}

  // Positioning attributes
  lazy val cx: DomAttributeOf = attr("cx")
  lazy val cy: DomAttributeOf = attr("cy")
  lazy val r: DomAttributeOf  = attr("r")
  lazy val rx: DomAttributeOf = attr("rx")
  lazy val ry: DomAttributeOf = attr("ry")
  lazy val x: DomAttributeOf  = attr("x")
  lazy val y: DomAttributeOf  = attr("y")
  lazy val x1: DomAttributeOf = attr("x1")
  lazy val y1: DomAttributeOf = attr("y1")
  lazy val x2: DomAttributeOf = attr("x2")
  lazy val y2: DomAttributeOf = attr("y2")
  lazy val dx: DomAttributeOf = attr("dx")
  lazy val dy: DomAttributeOf = attr("dy")

  // Path data
  lazy val d: DomAttributeOf          = attr("d")
  lazy val points: DomAttributeOf     = attr("points")
  lazy val pathLength: DomAttributeOf = attr("pathLength")

  // Presentation attributes
  lazy val fill: DomAttributeOf             = attr("fill")
  lazy val fillOpacity: DomAttributeOf      = attr("fill-opacity")
  lazy val fillRule: DomAttributeOf         = attr("fill-rule")
  lazy val stroke: DomAttributeOf           = attr("stroke")
  lazy val strokeWidth: DomAttributeOf      = attr("stroke-width")
  lazy val strokeOpacity: DomAttributeOf    = attr("stroke-opacity")
  lazy val strokeLinecap: DomAttributeOf    = attr("stroke-linecap")
  lazy val strokeLinejoin: DomAttributeOf   = attr("stroke-linejoin")
  lazy val strokeDasharray: DomAttributeOf  = attr("stroke-dasharray")
  lazy val strokeDashoffset: DomAttributeOf = attr("stroke-dashoffset")
  lazy val strokeMiterlimit: DomAttributeOf = attr("stroke-miterlimit")
  lazy val svgOpacity: DomAttributeOf       = attr("opacity")

  // Transform
  lazy val svgTransform: DomAttributeOf      = attr("transform")
  lazy val gradientTransform: DomAttributeOf = attr("gradientTransform")
  lazy val patternTransform: DomAttributeOf  = attr("patternTransform")

  // ViewBox and coordinate system
  lazy val viewBox: DomAttributeOf             = attr("viewBox")
  lazy val preserveAspectRatio: DomAttributeOf = attr("preserveAspectRatio")

  // Gradient attributes
  lazy val gradientUnits: DomAttributeOf = attr("gradientUnits")
  lazy val spreadMethod: DomAttributeOf  = attr("spreadMethod")
  lazy val fx: DomAttributeOf            = attr("fx")
  lazy val fy: DomAttributeOf            = attr("fy")
  lazy val offset: DomAttributeOf        = attr("offset")
  lazy val stopColor: DomAttributeOf     = attr("stop-color")
  lazy val stopOpacity: DomAttributeOf   = attr("stop-opacity")

  // Text attributes
  lazy val textAnchor: DomAttributeOf       = attr("text-anchor")
  lazy val dominantBaseline: DomAttributeOf = attr("dominant-baseline")
  lazy val fontFamily: DomAttributeOf       = attr("font-family")
  lazy val fontSize: DomAttributeOf         = attr("font-size")
  lazy val fontWeight: DomAttributeOf       = attr("font-weight")
  lazy val letterSpacing: DomAttributeOf    = attr("letter-spacing")
  lazy val textDecoration: DomAttributeOf   = attr("text-decoration")

  // Marker attributes
  lazy val markerStart: DomAttributeOf  = attr("marker-start")
  lazy val markerMid: DomAttributeOf    = attr("marker-mid")
  lazy val markerEnd: DomAttributeOf    = attr("marker-end")
  lazy val markerWidth: DomAttributeOf  = attr("markerWidth")
  lazy val markerHeight: DomAttributeOf = attr("markerHeight")
  lazy val markerUnits: DomAttributeOf  = attr("markerUnits")
  lazy val orient: DomAttributeOf       = attr("orient")
  lazy val refX: DomAttributeOf         = attr("refX")
  lazy val refY: DomAttributeOf         = attr("refY")

  // Clip and mask
  lazy val clipPathAttr: DomAttributeOf     = attr("clip-path")
  lazy val clipPathUnits: DomAttributeOf    = attr("clipPathUnits")
  lazy val clipRule: DomAttributeOf         = attr("clip-rule")
  lazy val maskAttr: DomAttributeOf         = attr("mask")
  lazy val maskUnits: DomAttributeOf        = attr("maskUnits")
  lazy val maskContentUnits: DomAttributeOf = attr("maskContentUnits")

  // Filter attributes
  lazy val filterAttr: DomAttributeOf     = attr("filter")
  lazy val filterUnits: DomAttributeOf    = attr("filterUnits")
  lazy val primitiveUnits: DomAttributeOf = attr("primitiveUnits")
  lazy val in: DomAttributeOf             = attr("in")
  lazy val in2: DomAttributeOf            = attr("in2")
  lazy val svgResult: DomAttributeOf      = attr("result")
  lazy val stdDeviation: DomAttributeOf   = attr("stdDeviation")
  lazy val svgMode: DomAttributeOf        = attr("mode")
  lazy val svgValues: DomAttributeOf      = attr("values")

  // XLink attributes (with namespace)
  lazy val xlinkHref: DomAttributeOf  = attrOf("href", DomNamespace.svgXLink)
  lazy val xlinkTitle: DomAttributeOf = attrOf("title", DomNamespace.svgXLink)

  // Animation attributes
  lazy val attributeName: DomAttributeOf = attr("attributeName")
  lazy val attributeType: DomAttributeOf = attr("attributeType")
  lazy val begin: DomAttributeOf         = attr("begin")
  lazy val dur: DomAttributeOf           = attr("dur")
  lazy val svgEnd: DomAttributeOf        = attr("end")
  lazy val repeatCount: DomAttributeOf   = attr("repeatCount")
  lazy val repeatDur: DomAttributeOf     = attr("repeatDur")
  lazy val svgFrom: DomAttributeOf       = attr("from")
  lazy val svgTo: DomAttributeOf         = attr("to")
  lazy val by: DomAttributeOf            = attr("by")
  lazy val calcMode: DomAttributeOf      = attr("calcMode")
  lazy val keyTimes: DomAttributeOf      = attr("keyTimes")
  lazy val keySplines: DomAttributeOf    = attr("keySplines")
  lazy val keyPoints: DomAttributeOf     = attr("keyPoints")

  // Other common SVG attributes
  lazy val version: DomAttributeOf           = attr("version")
  lazy val xmlns: DomAttributeOf             = attr("xmlns")
  lazy val xmlnsXlink: DomAttributeOf        = attr("xmlns:xlink")
  lazy val baseProfile: DomAttributeOf       = attr("baseProfile")
  lazy val contentScriptType: DomAttributeOf = attr("contentScriptType")
  lazy val contentStyleType: DomAttributeOf  = attr("contentStyleType")

end SvgAttrs

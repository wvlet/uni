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
  * Represents XML/HTML namespace for DOM elements and attributes.
  */
case class DomNamespace(uri: String)

object DomNamespace:
  /**
    * Default XHTML namespace for HTML elements.
    */
  val xhtml: DomNamespace = DomNamespace("http://www.w3.org/1999/xhtml")

  /**
    * SVG namespace for SVG elements.
    */
  val svg: DomNamespace = DomNamespace("http://www.w3.org/2000/svg")

  /**
    * XLink namespace for SVG xlink attributes.
    */
  val svgXLink: DomNamespace = DomNamespace("http://www.w3.org/1999/xlink")

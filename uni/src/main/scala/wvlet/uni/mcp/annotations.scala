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
package wvlet.uni.mcp

import scala.annotation.StaticAnnotation

/**
  * Describes an MCP tool (on a method) or a tool parameter (on a method argument). Surface captures
  * no Scaladoc, so descriptions shown to MCP clients must be provided through this annotation:
  *
  * {{{
  * trait WeatherService:
  *   @description("Return the current temperature in Celsius for a city")
  *   def temperature(@description("City name, e.g. Tokyo") city: String): Double
  * }}}
  */
class description(val value: String = "") extends StaticAnnotation

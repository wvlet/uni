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
package wvlet.uni.http.router

import wvlet.uni.http.HttpMethod
import wvlet.uni.surface.{MethodSurface, Surface}
import wvlet.uni.weaver.Weaver

/**
  * Represents a single HTTP route with its method, path pattern, and associated controller method.
  *
  * @param method
  *   The HTTP method for this route
  * @param pathPattern
  *   The original path pattern string (e.g., "/users/:id")
  * @param pathComponents
  *   The parsed path components for matching
  * @param controllerSurface
  *   The Surface of the controller class
  * @param methodSurface
  *   The MethodSurface of the endpoint method
  * @param paramWeavers
  *   Weavers resolved at compile time for parameters that may be read from the request body, by
  *   parameter name. They include custom `given` Weavers, which a runtime `Weaver.fromSurface`
  *   cannot see. Parameters without an entry fall back to `Weaver.fromSurface`
  */
case class Route(
    method: HttpMethod,
    pathPattern: String,
    pathComponents: Seq[PathComponent],
    controllerSurface: Surface,
    methodSurface: MethodSurface,
    paramWeavers: Map[String, Weaver[?]] = Map.empty
):
  /**
    * Returns a human-readable description of this route
    */
  def description: String = s"${method.name} ${pathPattern} -> ${methodSurface.name}"

end Route

/**
  * Represents a single component of a URL path
  */
sealed trait PathComponent

object PathComponent:
  /**
    * A literal path component that must match exactly (e.g., "users" in "/users/123")
    */
  case class Literal(value: String) extends PathComponent:
    override def toString: String = value

  /**
    * A parameter path component that captures a value (e.g., ":id" in "/users/:id")
    */
  case class Parameter(name: String) extends PathComponent:
    override def toString: String = s":${name}"

  /**
    * Split a path string into segments.
    *
    * @param path
    *   The path to split (e.g., "/users/123/posts")
    * @return
    *   A sequence of path segments
    */
  def splitPath(path: String): Seq[String] =
    val normalizedPath =
      if path.startsWith("/") then
        path.substring(1)
      else
        path
    if normalizedPath.isEmpty then
      Seq.empty
    else
      normalizedPath.split("/").toSeq

  /**
    * Parse a path string into a sequence of PathComponents.
    *
    * @param path
    *   The path pattern to parse (e.g., "/users/:id/posts/:postId")
    * @return
    *   A sequence of PathComponent instances
    */
  def parse(path: String): Seq[PathComponent] = splitPath(path).map { segment =>
    if segment.startsWith(":") then
      Parameter(segment.substring(1))
    else
      Literal(segment)
  }

end PathComponent

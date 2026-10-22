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
import scala.annotation.StaticAnnotation

/**
  * Annotation for marking controller methods as HTTP endpoints.
  *
  * @param method
  *   The HTTP method (GET, POST, PUT, DELETE, etc.)
  * @param path
  *   The URL path pattern. Can include path parameters using `:paramName` syntax, e.g.,
  *   "/users/:id"
  * @param description
  *   A description of the endpoint for generated API documentation (see `OpenAPI`)
  *
  * Example usage:
  * {{{
  * class UserController:
  *   @Endpoint(HttpMethod.GET, "/users")
  *   def listUsers(): Seq[User] = ???
  *
  *   @Endpoint(HttpMethod.GET, "/users/:id")
  *   def getUser(id: String): User = ???
  *
  *   @Endpoint(HttpMethod.POST, "/users")
  *   def createUser(request: Request): User = ???
  * }}}
  */
case class Endpoint(method: HttpMethod, path: String, description: String = "")
    extends StaticAnnotation

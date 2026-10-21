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
import wvlet.uni.surface.{Annotation, ClassMethodSurface, Surface}
import wvlet.uni.test.UniTest

class DualAnnotatedController:
  @foreign.Endpoint("POST", "/foreign/messages")
  @Endpoint(HttpMethod.POST, "/v1/messages")
  def messages(): String = "messages"

  @Endpoint(HttpMethod.GET, "/v1/models")
  @foreign.Endpoint("GET", "/foreign/models")
  def models(): String = "models"

  @foreign.Endpoint("GET", "/foreign/only")
  def foreignOnly(): String = "foreign"

class EndpointAnnotationTest extends UniTest:

  test("ignore an @Endpoint from another package, whatever the annotation order") {
    val routes = Router.of[DualAnnotatedController].routes
    routes.map(r => (r.method, r.pathPattern, r.methodSurface.name)).toSet shouldBe
      Set((HttpMethod.POST, "/v1/messages", "messages"), (HttpMethod.GET, "/v1/models", "models"))
  }

  test("fail instead of dropping a route whose @Endpoint cannot be read") {
    val surface = Surface.of[DualAnnotatedController]
    val method  =
      Surface.methodsOf[DualAnnotatedController].find(_.name == "models").get match
        case m: ClassMethodSurface =>
          m
    val broken = method.copy(annotations =
      Seq(Annotation("Endpoint", classOf[Endpoint].getName, Map("path" -> "/v1/models")))
    )
    val e = intercept[IllegalArgumentException] {
      RouterMacros.extractRoutes(surface, Seq(broken))
    }
    e.getMessage shouldContain "models"
  }

end EndpointAnnotationTest

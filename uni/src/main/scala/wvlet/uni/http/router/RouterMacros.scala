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

import scala.quoted.*

/**
  * Macros for building Router instances at compile-time.
  */
object RouterMacros:

  /**
    * Build an [[Router]] from a controller type at compile-time. Methods annotated with
    * [[Endpoint]] become routes; other methods are ignored.
    */
  inline def buildRouter[T]: Router =
    ${
      buildRouterImpl[T]
    }

  private def buildRouterImpl[T: Type](using Quotes): Expr[Router] =
    '{
      val surface        = Surface.of[T]
      val methodSurfaces = Surface.methodsOf[T]
      val routes         = RouterMacros.extractRoutes(surface, methodSurfaces)
      Router(routes, None)
    }

  /**
    * Build an [[RxRouter]] from a controller type at compile-time. Every public method on `T`
    * becomes an RPC endpoint — `RxRouter` is the convention-based, RPC-only sibling of [[Router]].
    * Use [[Router]] when you want explicit `@Endpoint` routes.
    */
  inline def buildRxRouter[T]: RxRouter =
    ${
      buildRxRouterImpl[T]
    }

  private def buildRxRouterImpl[T: Type](using Quotes): Expr[RxRouter] =
    '{
      val surface        = Surface.of[T]
      val methodSurfaces = Surface
        .methodsOf[T]
        .filterNot(ms => RouterMacros.ObjectMethodNames.contains(ms.name))
      RxRouter.EndpointNode(surface, methodSurfaces, pathPrefix = "")
    }

  /**
    * Extract routes from method surfaces at runtime. This method filters methods annotated with
    * `@Endpoint` and creates Route instances.
    */
  def extractRoutes(controllerSurface: Surface, methodSurfaces: Seq[MethodSurface]): Seq[Route] =
    methodSurfaces.flatMap { ms =>
      ms.findAnnotation("Endpoint")
        .flatMap { annot =>
          val methodOpt = annot
            .get("method")
            .flatMap {
              case m: HttpMethod =>
                Some(m)
              case _ =>
                None
            }
          val pathOpt = annot.getAs[String]("path")

          (methodOpt, pathOpt) match
            case (Some(method), Some(path)) =>
              val pathComponents = PathComponent.parse(path)
              Some(Route(method, path, pathComponents, controllerSurface, ms))
            case _ =>
              None
        }
    }

  // Members inherited from java.lang.Object / scala.Any / scala.Product. Surface.methodsOf
  // already filters them by owner, but we also exclude by name as belt-and-suspenders against
  // unusual user-supplied method surfaces and future Surface changes.
  private[router] val ObjectMethodNames: Set[String] = Set(
    // java.lang.Object / scala.Any
    "toString",
    "hashCode",
    "equals",
    "getClass",
    "wait",
    "notify",
    "notifyAll",
    "clone",
    "finalize",
    // scala.Product (case classes)
    "canEqual",
    "productArity",
    "productElement",
    "productElementName",
    "productIterator",
    "productPrefix"
  )

end RouterMacros

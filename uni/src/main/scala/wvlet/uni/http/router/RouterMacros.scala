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
    * Build a Router from a controller type at compile-time.
    */
  inline def buildRouter[T]: Router =
    ${
      buildRouterImpl[T]
    }

  private def buildRouterImpl[T: Type](using Quotes): Expr[Router] =
    import quotes.reflect.*

    // Build the expression using runtime method discovery
    '{
      val surface        = Surface.of[T]
      val methodSurfaces = Surface.methodsOf[T]
      val routes         = RouterMacros.extractRoutes(surface, methodSurfaces)
      Router(routes, None)
    }

  /**
    * Build an [[RxRouter]] from a controller type at compile-time. If the type is annotated with
    * `@RPC`, every public method becomes an RPC endpoint (POST). Otherwise, methods annotated with
    * `@Endpoint` are picked up just like [[buildRouter]].
    */
  inline def buildRxRouter[T]: RxRouter =
    ${
      buildRxRouterImpl[T]
    }

  private def buildRxRouterImpl[T: Type](using Quotes): Expr[RxRouter] =
    import quotes.reflect.*

    val rpcAnnot = TypeRepr
      .of[T]
      .typeSymbol
      .annotations
      .find { a =>
        a.tpe.typeSymbol == TypeRepr.of[wvlet.uni.http.rpc.RPC].typeSymbol
      }

    val rpcPathExpr: Expr[Option[String]] =
      rpcAnnot match
        case Some(annot) =>
          // Extract the `path` argument literal from `@RPC(path = "/v1")` if present.
          val applyArgs =
            annot match
              case Apply(_, args) =>
                args
              case _ =>
                Nil
          val literalPath = applyArgs
            .collectFirst {
              case NamedArg("path", Literal(StringConstant(p))) =>
                p
              case Literal(StringConstant(p)) =>
                p
            }
            .getOrElse("")
          '{
            Some(
              ${
                Expr(literalPath)
              }
            )
          }
        case None =>
          '{
            None
          }

    '{
      val surface        = Surface.of[T]
      val methodSurfaces = Surface.methodsOf[T]
      val routes         = RouterMacros.extractRoutesForRx(
        surface,
        methodSurfaces,
        ${
          rpcPathExpr
        }
      )
      RxRouter.EndpointNode(surface, methodSurfaces, routes)
    }

  end buildRxRouterImpl

  /**
    * Extract routes from method surfaces at runtime. This method filters methods annotated with
    * `@Endpoint` and creates Route instances.
    */
  def extractRoutes(controllerSurface: Surface, methodSurfaces: Seq[MethodSurface]): Seq[Route] =
    methodSurfaces.flatMap { ms =>
      ms.findAnnotation("Endpoint")
        .flatMap { annot =>
          // Extract the method and path from the annotation
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

  /**
    * Extract routes for an [[RxRouter]]. When `rpcPathPrefix` is `Some(_)`, the controller was
    * annotated with `@RPC` and every public method becomes an RPC endpoint (HTTP POST, path
    * `{rpcPathPrefix}/{controllerFullName}/{method}`). Otherwise, falls back to the
    * `@Endpoint`-driven scan used by [[buildRouter]].
    */
  def extractRoutesForRx(
      controllerSurface: Surface,
      methodSurfaces: Seq[MethodSurface],
      rpcPathPrefix: Option[String]
  ): Seq[Route] =
    rpcPathPrefix match
      case Some(rawPrefix) =>
        val prefix = rawPrefix.stripSuffix("/")
        methodSurfaces.map { ms =>
          val rpcPath        = s"${prefix}/${controllerSurface.fullName}/${ms.name}"
          val pathComponents = PathComponent.parse(rpcPath)
          Route(HttpMethod.POST, rpcPath, pathComponents, controllerSurface, ms)
        }
      case None =>
        extractRoutes(controllerSurface, methodSurfaces)

end RouterMacros

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
          // Extract the `path` argument from `@RPC(path = ...)` if present. Accept both inline
          // string literals and references to constant `final val`s by asking the compiler for
          // the term's constant-folded value.
          val applyArgs =
            annot match
              case Apply(_, args) =>
                args
              case _ =>
                Nil

          def constantString(term: Term): Option[String] =
            term.tpe.widenTermRefByName match
              case ConstantType(StringConstant(s)) =>
                Some(s)
              case _ =>
                term match
                  case Literal(StringConstant(s)) =>
                    Some(s)
                  case Inlined(_, _, inner) =>
                    constantString(inner)
                  case Typed(inner, _) =>
                    constantString(inner)
                  case _ =>
                    None

          val resolvedPath = applyArgs
            .collectFirst {
              case NamedArg("path", t) =>
                constantString(t)
              case t: Term if constantString(t).isDefined =>
                constantString(t)
            }
            .flatten
            .getOrElse("")
          '{
            Some(
              ${
                Expr(resolvedPath)
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
        // Surface.methodsOf already filters out methods owned by Any / java.lang.Object /
        // scala.Product, but defend against future changes (and against unusual user-supplied
        // method surfaces) by excluding methods named identically to those base members.
        val rpcMethods = methodSurfaces.filterNot(ms => ObjectMethodNames.contains(ms.name))
        val duplicates = rpcMethods.groupBy(_.name).filter(_._2.size > 1).keys.toSeq.sorted
        if duplicates.nonEmpty then
          throw IllegalArgumentException(
            s"Overloaded RPC methods are not supported in @RPC trait '${controllerSurface
                .fullName}': " + duplicates.mkString(", ")
          )
        rpcMethods.map { ms =>
          val rpcPath        = s"${prefix}/${controllerSurface.fullName}/${ms.name}"
          val pathComponents = PathComponent.parse(rpcPath)
          Route(HttpMethod.POST, rpcPath, pathComponents, controllerSurface, ms)
        }
      case None =>
        extractRoutes(controllerSurface, methodSurfaces)

  // Members inherited from java.lang.Object / scala.Any / scala.Product. Surface.methodsOf
  // already filters them by owner, but we also exclude by name as belt-and-suspenders against
  // unusual user-supplied method surfaces and future Surface changes.
  private val ObjectMethodNames: Set[String] = Set(
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

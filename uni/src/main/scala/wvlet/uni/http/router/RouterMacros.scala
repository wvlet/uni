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
import wvlet.uni.http.Request
import wvlet.uni.surface.{MethodSurface, Surface}
import wvlet.uni.weaver.Weaver

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
    val bodyWeavers = bodyWeaversOf[T]
    '{
      val surface        = Surface.of[T]
      val methodSurfaces = Surface.methodsOf[T]
      val routes         = RouterMacros.extractRoutes(
        surface,
        methodSurfaces,
        ${
          bodyWeavers
        }
      )
      Router(routes)
    }

  /**
    * Resolve Weavers for the parameters of `@Endpoint` methods that may be read from the request
    * body, by method name and then parameter name. Resolving them here, where `Router.of` is
    * expanded, lets a custom `given Weaver[A]` in `A`'s companion or in scope at the call site
    * decode the body. For a case class, sealed trait, or enum without a given, `Weaver.of` derives
    * one that still uses the givens of its fields. Other types fall back to `Weaver.fromSurface` at
    * runtime.
    */
  private def bodyWeaversOf[T: Type](using Quotes): Expr[Map[String, Map[String, Weaver[?]]]] =
    import quotes.reflect.*

    val tpe          = TypeRepr.of[T]
    val endpointType = TypeRepr.of[Endpoint].typeSymbol
    // Overloads share a name, so their weavers cannot be told apart by it; they use runtime
    // weavers instead
    val endpointMethods =
      tpe
        .typeSymbol
        .methodMembers
        .filter(_.hasAnnotation(endpointType))
        .groupBy(_.name)
        .collect { case (_, Seq(m)) =>
          m
        }
        .toList

    def isLibraryType(t: TypeRepr): Boolean =
      val name = t.typeSymbol.fullName
      name.startsWith("scala.") || name.startsWith("java.")

    def weaverFor(paramType: TypeRepr): Option[Expr[Weaver[?]]] =
      if paramType <:< TypeRepr.of[Request] || paramType <:< TypeRepr.of[AnyVal] ||
        paramType <:< TypeRepr.of[String]
      then
        None
      else
        paramType.asType match
          case '[t] =>
            Expr.summon[Weaver[t]] match
              case Some(w) =>
                Some(w)
              case None =>
                // Weaver.of always derives a case class (fields without a Weaver use runtime
                // weavers) or an enum. A sealed trait needs a Weaver for every child, so it uses
                // the runtime weaver rather than failing to compile
                val sym       = paramType.typeSymbol
                val derivable =
                  (sym.flags.is(Flags.Case) && sym.isClassDef && !sym.flags.is(Flags.Module)) ||
                    paramType <:< TypeRepr.of[scala.reflect.Enum]
                if !isLibraryType(paramType) && derivable then
                  Some(
                    '{
                      Weaver.of[t]
                    }
                  )
                else
                  None

    val entries: List[Expr[(String, Map[String, Weaver[?]])]] = endpointMethods.flatMap { m =>
      tpe.memberType(m) match
        case MethodType(paramNames, paramTypes, _) =>
          val params = paramNames
            .zip(paramTypes)
            .flatMap { (name, paramType) =>
              weaverFor(paramType).map(w =>
                '{
                  (
                    ${
                      Expr(name)
                    },
                    ${
                      w
                    }
                  )
                }
              )
            }
          if params.isEmpty then
            None
          else
            Some(
              '{
                (
                  ${
                    Expr(m.name)
                  },
                  Map(
                    ${
                      Varargs(params)
                    }*
                  )
                )
              }
            )
        case _ =>
          // Generic or curried endpoint methods use runtime weavers
          None
    }
    '{
      Map(
        ${
          Varargs(entries)
        }*
      )
    }

  end bodyWeaversOf

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
    * uni's `@Endpoint` and creates Route instances.
    *
    * The annotation is matched by its full name, so an `@Endpoint` from another framework on the
    * same method (e.g., kept for documentation generation) is ignored. A uni `@Endpoint` whose
    * method or path cannot be read fails instead of silently dropping the route.
    */
  def extractRoutes(
      controllerSurface: Surface,
      methodSurfaces: Seq[MethodSurface],
      bodyWeavers: Map[String, Map[String, Weaver[?]]] = Map.empty
  ): Seq[Route] = methodSurfaces.flatMap { ms =>
    ms.annotations
      .find(_.fullName == EndpointAnnotationName)
      .map { annot =>
        val method =
          annot.get("method") match
            case Some(m: HttpMethod) =>
              m
            case other =>
              throw IllegalArgumentException(
                s"Invalid @Endpoint method on ${controllerSurface.name}.${ms.name}: ${other}"
              )
        val path =
          annot.get("path") match
            case Some(p: String) =>
              p
            case other =>
              throw IllegalArgumentException(
                s"Invalid @Endpoint path on ${controllerSurface.name}.${ms.name}: ${other}"
              )
        Route(
          method,
          path,
          PathComponent.parse(path),
          controllerSurface,
          ms,
          bodyWeavers.getOrElse(ms.name, Map.empty)
        )
      }
  }

  private val EndpointAnnotationName = classOf[Endpoint].getName

  // Members inherited from java.lang.Object / scala.Any / scala.Product. Surface.methodsOf
  // already filters them by owner, but we also exclude by name as belt-and-suspenders against
  // unusual user-supplied method surfaces and future Surface changes.
  //
  // Public because the buildRxRouter macro expands `ObjectMethodNames.contains(...)` at the
  // call site, which by definition lives outside this package.
  val ObjectMethodNames: Set[String] = Set(
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

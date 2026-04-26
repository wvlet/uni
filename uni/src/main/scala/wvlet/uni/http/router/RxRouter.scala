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

/**
  * Declarative router built from a Scala trait/class via [[RxRouter.of]]. Every public method on
  * the underlying type is exposed as an HTTP POST endpoint with path
  * `{pathPrefix}/{controllerFullName}/{methodName}`. The path prefix defaults to empty; use
  * [[RxRouter.withPathPrefix]] to namespace a router (e.g. `"/v1"`).
  *
  * RxRouter is convention-based: the trait does not need a marker annotation. For explicit
  * `@Endpoint`-driven routing, use [[Router]] instead.
  *
  * Example:
  * {{{
  *   import wvlet.uni.http.router.{RxRouter, RxRouterProvider}
  *
  *   trait MyApi:
  *     def hello(name: String): String
  *
  *   object MyApi extends RxRouterProvider:
  *     override def router: RxRouter = RxRouter.of[MyApi].withPathPrefix("/v1")
  * }}}
  */
trait RxRouter:
  /** Display name for this router node (typically the controller's simple class name). */
  def name: String

  /** Child routers (empty for leaf nodes). */
  def children: List[RxRouter]

  /** True if this is a leaf node (a single endpoint group). */
  def isLeaf: Boolean

  /** Underlying flat list of routes that this RxRouter contributes. */
  def toRoutes: Seq[Route]

  /**
    * Returns a copy of this router whose endpoint paths are prefixed with the given string. Stem
    * nodes propagate the prefix to all children.
    */
  def withPathPrefix(prefix: String): RxRouter

  override def toString: String = printNode(0)

  private def printNode(indentLevel: Int): String =
    val ws  = "  " * indentLevel
    val out = Seq.newBuilder[String]
    out += s"${ws}- Router[${name}]"
    if isLeaf then
      for r <- toRoutes do
        out += s"${ws}  + ${r.description}"
    for c <- children do
      out += c.printNode(indentLevel + 1)
    out.result().mkString("\n")

end RxRouter

object RxRouter:

  /**
    * Build an [[RxRouter]] from the given service type at compile time. Every public method on `T`
    * becomes an HTTP POST endpoint at `/{T.fullName}/{methodName}`. Customize the namespace with
    * [[RxRouter.withPathPrefix]].
    */
  inline def of[T]: RxRouter = RouterMacros.buildRxRouter[T]

  /**
    * Combine multiple routers into a single router. Returns a single-child stem when a single
    * router is passed.
    */
  def of(routers: RxRouter*): RxRouter =
    if routers.size == 1 then
      routers.head
    else
      StemNode(routers.toList)

  /**
    * A leaf RxRouter wrapping a controller surface and its method surfaces. Routes are computed
    * lazily so [[withPathPrefix]] can rebuild them without re-running the macro.
    */
  case class EndpointNode(
      controllerSurface: Surface,
      methodSurfaces: Seq[MethodSurface],
      pathPrefix: String
  ) extends RxRouter:
    override def name: String             = controllerSurface.name
    override def children: List[RxRouter] = Nil
    override def isLeaf: Boolean          = true

    override def toRoutes: Seq[Route] =
      val duplicates = methodSurfaces.groupBy(_.name).filter(_._2.size > 1).keys.toSeq.sorted
      if duplicates.nonEmpty then
        throw IllegalArgumentException(
          s"Overloaded RPC methods are not supported in RxRouter for '${controllerSurface
              .fullName}': " + duplicates.mkString(", ")
        )
      val prefix = pathPrefix.stripSuffix("/")
      methodSurfaces.map { ms =>
        val rpcPath        = s"${prefix}/${controllerSurface.fullName}/${ms.name}"
        val pathComponents = PathComponent.parse(rpcPath)
        Route(HttpMethod.POST, rpcPath, pathComponents, controllerSurface, ms)
      }

    override def withPathPrefix(prefix: String): RxRouter = copy(pathPrefix = prefix)

  end EndpointNode

  /** A composition of multiple child routers. */
  case class StemNode(override val children: List[RxRouter]) extends RxRouter:
    override def name: String                             = f"stem-${this.hashCode()}%08x"
    override def isLeaf: Boolean                          = false
    override def toRoutes: Seq[Route]                     = children.flatMap(_.toRoutes)
    override def withPathPrefix(prefix: String): RxRouter = copy(children =
      children.map(_.withPathPrefix(prefix))
    )

  end StemNode

end RxRouter

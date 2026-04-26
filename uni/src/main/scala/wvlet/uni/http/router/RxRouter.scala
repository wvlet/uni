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

import wvlet.uni.surface.{MethodSurface, Surface}

/**
  * Declarative router built from a Scala trait/class annotated with [[wvlet.uni.http.rpc.RPC]]
  * (every public method becomes an RPC endpoint) or [[Endpoint]] (per-method endpoints).
  *
  * Mirrors the API surface of `wvlet.airframe.http.RxRouter` so code being migrated from airframe
  * can change imports without restructuring.
  *
  * Example:
  * {{{
  *   import wvlet.uni.http.rpc.RPC
  *   import wvlet.uni.http.router.{RxRouter, RxRouterProvider}
  *
  *   @RPC
  *   trait MyApi:
  *     def hello(name: String): String
  *
  *   object MyApi extends RxRouterProvider:
  *     override def router: RxRouter = RxRouter.of[MyApi]
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
    * Build a router from the given controller type at compile time. `T` should be annotated with
    * [[wvlet.uni.http.rpc.RPC]] (every public method becomes an RPC endpoint) or have one or more
    * [[Endpoint]]-annotated methods.
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

  /** A leaf RxRouter wrapping a controller surface and its method surfaces. */
  case class EndpointNode(
      controllerSurface: Surface,
      methodSurfaces: Seq[MethodSurface],
      routes: Seq[Route]
  ) extends RxRouter:
    override def name: String             = controllerSurface.name
    override def children: List[RxRouter] = Nil
    override def isLeaf: Boolean          = true
    override def toRoutes: Seq[Route]     = routes
  end EndpointNode

  /** A composition of multiple child routers. */
  case class StemNode(override val children: List[RxRouter]) extends RxRouter:
    override def name: String         = f"${this.hashCode()}%08x"
    override def isLeaf: Boolean      = false
    override def toRoutes: Seq[Route] = children.flatMap(_.toRoutes)
  end StemNode

end RxRouter

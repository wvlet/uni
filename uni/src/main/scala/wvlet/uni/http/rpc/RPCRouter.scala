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
package wvlet.uni.http.rpc

import wvlet.uni.rx.Rx
import wvlet.uni.surface.MethodSurface
import wvlet.uni.surface.Surface
import wvlet.uni.weaver.Weaver

/**
  * RPC Router that builds routes from a service trait using Surface reflection.
  *
  * Usage:
  * {{{
  * trait UserService:
  *   def getUser(id: Long): User
  *
  * class UserServiceImpl extends UserService:
  *   def getUser(id: Long): User = ...
  *
  * val router = RPCRouter.of[UserService](UserServiceImpl())
  * }}}
  *
  * Routes are generated as: POST /{serviceName}/{methodName}
  */
object RPCRouter:
  /**
    * Create an RPC router from a service trait and implementation.
    *
    * Uses Surface.methodsOf[T] (inline macro) to extract method metadata at compile time, then
    * builds Weavers from Surface at runtime.
    */
  inline def of[T](instance: T): RPCRouter =
    val surface = Surface.of[T]
    new RPCRouter(Surface.methodsOf[T], instance, surface.fullName)

class RPCRouter(methods: Seq[MethodSurface], val instance: Any, val serviceName: String):
  // Classes whose methods should not be exposed as RPC endpoints
  private val excludedOwners: Set[Class[?]] = Set(classOf[Object], classOf[Any])

  /**
    * Method codecs derived from Surface. Each codec combines Weavers for parameter types and return
    * type.
    */
  val codecs: Map[String, MethodCodec] =
    val filteredMethods = methods.filter(m =>
      m.isPublic && !excludedOwners.contains(m.owner.rawType)
    )

    // Detect overloaded methods (same name, different signatures)
    val methodsByName = filteredMethods.groupBy(_.name)
    val overloaded    = methodsByName.filter(_._2.size > 1).keys
    if overloaded.nonEmpty then
      throw IllegalArgumentException(
        s"RPC does not support method overloading. Overloaded methods: ${overloaded.mkString(", ")}"
      )

    filteredMethods
      .map { m =>
        val paramWeavers = m.args.map(p => Weaver.fromSurface(p.surface)).toIndexedSeq
        // Unwrap Rx[T] return type to get the inner type for the weaver
        val actualReturnType =
          if classOf[Rx[?]].isAssignableFrom(m.returnType.rawType) && m.returnType.typeArgs.nonEmpty
          then
            m.returnType.typeArgs.head
          else
            m.returnType
        val returnWeaver = Weaver.fromSurface(actualReturnType)
        m.name -> MethodCodec(m, paramWeavers, returnWeaver)
      }
      .toMap

  /**
    * All RPC routes. Path format: /{serviceName}/{methodName}
    */
  val routes: Seq[RPCRoute] =
    codecs
      .map { (name, codec) =>
        RPCRoute(serviceName, name, s"/${serviceName}/${name}", codec)
      }
      .toSeq

  /**
    * Lookup a route by method name.
    */
  def findRoute(methodName: String): Option[RPCRoute] = routes.find(_.methodName == methodName)

end RPCRouter

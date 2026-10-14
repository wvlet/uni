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

import wvlet.uni.surface.MethodSurface

/**
  * Selects the methods of a service trait that become RPC endpoints. Shared by [[RPCRouter]] and
  * [[RPCClient]] so the server and client always agree on the endpoint set.
  *
  * A method is exposed when it is public and
  *   - is not annotated with [[rpcIgnore]], and
  *   - is not inherited from a library trait (`scala.*`, `java.*`, or uni's logging mixins such as
  *     `LogSupport`). Methods inherited from the application's own base traits stay exposed, so
  *     `trait UserApi extends CrudApi[User]` serves the `CrudApi` methods too.
  */
object RPCMethodFilter:
  private val excludedOwners: Set[Class[?]] = Set(classOf[Object], classOf[Any])

  private val excludedDeclaringPrefixes: Seq[String] = Seq("scala.", "java.", "wvlet.uni.log.")

  def isRPCMethod(m: MethodSurface): Boolean =
    m.isPublic && !excludedOwners.contains(m.owner.rawType) &&
      !m.hasAnnotation("wvlet.uni.http.rpc.rpcIgnore") &&
      !m.declaringType.exists(t => excludedDeclaringPrefixes.exists(t.startsWith))

  def rpcMethods(methods: Seq[MethodSurface]): Seq[MethodSurface] = methods.filter(isRPCMethod)

end RPCMethodFilter

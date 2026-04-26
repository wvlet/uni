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
package wvlet.uni.reflect

import java.lang.reflect.InvocationTargetException

/**
  * A wrapper for a class that can be instantiated reflectively. Mirrors the API of
  * `scala.scalajs.reflect.InstantiatableClass`.
  */
final class InstantiatableClass private[reflect] (val runtimeClass: Class[?]):
  val declaredConstructors: List[InvokableConstructor] =
    runtimeClass.getConstructors.map(c => new InvokableConstructor(c)).toList

  /**
    * Instantiates this class using its public zero-argument constructor.
    */
  def newInstance(): Any =
    try
      runtimeClass.getDeclaredConstructor().newInstance()
    catch
      case e: InvocationTargetException if e.getCause != null =>
        throw e.getCause
      case e: NoSuchMethodException =>
        throw new InstantiationException(runtimeClass.getName).initCause(e)
      case _: IllegalAccessException =>
        // The constructor exists but is not public; expose as a missing zero-arg ctor.
        throw new InstantiationException(runtimeClass.getName).initCause(
          new NoSuchMethodException(s"${runtimeClass.getName}.<init>()")
        )

  /**
    * Looks up a public constructor identified by the types of its formal parameters.
    */
  def getConstructor(parameterTypes: Class[?]*): Option[InvokableConstructor] =
    try
      Some(new InvokableConstructor(runtimeClass.getConstructor(parameterTypes*)))
    catch
      case _: NoSuchMethodException =>
        None

end InstantiatableClass

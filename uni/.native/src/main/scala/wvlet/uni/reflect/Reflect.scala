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

import scala.scalanative.reflect.Reflect as ScalaNativeReflect

/**
  * Scala Native implementation of [[Reflect]]. Delegates to the linker-provided
  * `scala.scalanative.reflect.Reflect`. Classes/objects must be annotated with
  * [[wvlet.uni.reflect.annotation.EnableReflectiveInstantiation]] (or inherit it) for the Scala
  * Native linker to retain their metadata.
  *
  * The `loader: ClassLoader` overloads are accepted for source compatibility with the JVM API but
  * are ignored on Scala Native.
  */
object Reflect:
  def lookupLoadableModuleClass(fqcn: String): Option[LoadableModuleClass] = ScalaNativeReflect
    .lookupLoadableModuleClass(fqcn)

  def lookupLoadableModuleClass(fqcn: String, loader: ClassLoader): Option[LoadableModuleClass] =
    lookupLoadableModuleClass(fqcn)

  def lookupInstantiatableClass(fqcn: String): Option[InstantiatableClass] = ScalaNativeReflect
    .lookupInstantiatableClass(fqcn)

  def lookupInstantiatableClass(fqcn: String, loader: ClassLoader): Option[InstantiatableClass] =
    lookupInstantiatableClass(fqcn)

  /**
    * Returns the singleton companion object of `cls` via [[lookupLoadableModuleClass]]. Scala
    * Native requires the class (or one of its supertypes) to be annotated with
    * [[wvlet.uni.reflect.annotation.EnableReflectiveInstantiation]].
    */
  def companionOf(cls: Class[?]): Option[Any] =
    val name          = cls.getName
    val companionName =
      if name.endsWith("$") then
        name
      else
        s"${name}$$"
    lookupLoadableModuleClass(companionName).map(_.loadModule())

end Reflect

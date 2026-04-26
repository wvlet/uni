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

import scala.scalajs.reflect.Reflect as SJSReflect

/**
  * Scala.js implementation of [[Reflect]]. Delegates to the linker-provided
  * `scala.scalajs.reflect.Reflect`. Classes/objects must be annotated with
  * [[wvlet.uni.reflect.annotation.EnableReflectiveInstantiation]] (or inherit it) for the Scala.js
  * linker to retain their metadata.
  *
  * The `loader: ClassLoader` overloads are accepted for source compatibility with the JVM API but
  * are ignored on Scala.js.
  */
object Reflect:
  def lookupLoadableModuleClass(fqcn: String): Option[LoadableModuleClass] = SJSReflect
    .lookupLoadableModuleClass(fqcn)

  def lookupLoadableModuleClass(fqcn: String, loader: ClassLoader): Option[LoadableModuleClass] =
    lookupLoadableModuleClass(fqcn)

  def lookupInstantiatableClass(fqcn: String): Option[InstantiatableClass] = SJSReflect
    .lookupInstantiatableClass(fqcn)

  def lookupInstantiatableClass(fqcn: String, loader: ClassLoader): Option[InstantiatableClass] =
    lookupInstantiatableClass(fqcn)

  /**
    * Returns the singleton companion object of `cls` via [[lookupLoadableModuleClass]]. The
    * companion module (i.e. the runtime class of `object Foo`) must be annotated with
    * [[wvlet.uni.reflect.annotation.EnableReflectiveInstantiation]] (or inherit it). Annotating
    * only the class itself is not enough — `class Foo` and `object Foo` are independent runtime
    * classes and Scala.js retains metadata only for explicitly annotated module classes.
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

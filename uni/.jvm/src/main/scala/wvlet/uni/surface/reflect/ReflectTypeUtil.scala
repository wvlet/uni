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
package wvlet.uni.surface.reflect

/**
  * JVM-only reflection helpers for Surface. These rely on the JVM class loader and are not
  * available on Scala.js or Scala Native.
  */
object ReflectTypeUtil:

  /**
    * Returns the singleton companion object of the given class, if one exists. Useful for accessing
    * values declared on the companion of a class via reflection.
    *
    * For a class `Foo`, the companion is found by loading `Foo$` and reading its `MODULE$` field.
    */
  def companionObject(cls: Class[?]): Option[Any] =
    val name          = cls.getName
    val companionName =
      if name.endsWith("$") then
        name
      else
        s"${name}$$"
    try
      val loader       = Option(cls.getClassLoader).getOrElse(ClassLoader.getSystemClassLoader)
      val companionCls = Class.forName(companionName, true, loader)
      val moduleField  = companionCls.getField("MODULE$")
      Option(moduleField.get(null))
    catch
      case _: ClassNotFoundException | _: NoSuchFieldException =>
        None

end ReflectTypeUtil

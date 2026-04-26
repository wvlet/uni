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

import wvlet.uni.reflect.annotation.EnableReflectiveInstantiation

import java.lang.reflect.Modifier
import scala.collection.mutable

/**
  * Cross-platform reflective lookup for Scala modules (objects) and instantiatable classes.
  *
  * This is a Scala 3 port of `org.portablescala.reflect.Reflect`. On JVM the implementation uses
  * `java.lang.reflect`; on Scala.js and Scala Native it delegates to the platform-built-in
  * `scala.scalajs.reflect.Reflect` / `scala.scalanative.reflect.Reflect`.
  *
  * To make a class or object discoverable on Scala.js / Scala Native, annotate it (or one of its
  * super types) with [[wvlet.uni.reflect.annotation.EnableReflectiveInstantiation]]. The same rule
  * is applied on JVM so behavior matches across all three platforms.
  *
  * Example:
  * {{{
  *   import wvlet.uni.reflect.Reflect
  *   import wvlet.uni.reflect.annotation.EnableReflectiveInstantiation
  *
  *   @EnableReflectiveInstantiation
  *   trait Plugin
  *
  *   object MyPlugin extends Plugin
  *
  *   Reflect.lookupLoadableModuleClass("com.example.MyPlugin$").map(_.loadModule())
  * }}}
  */
object Reflect:

  /**
    * Reflectively looks up a loadable module class using the system class loader.
    *
    * @param fqcn
    *   fully-qualified name of the module class, including its trailing `$`.
    */
  def lookupLoadableModuleClass(fqcn: String): Option[LoadableModuleClass] =
    lookupLoadableModuleClass(fqcn, defaultLoader)

  /**
    * Reflectively looks up a loadable module class using the given class loader.
    *
    * @param fqcn
    *   fully-qualified name of the module class, including its trailing `$`.
    * @param loader
    *   class loader used to resolve `fqcn`.
    */
  def lookupLoadableModuleClass(fqcn: String, loader: ClassLoader): Option[LoadableModuleClass] =
    load(fqcn, loader).filter(isModuleClass).map(new LoadableModuleClass(_))

  /**
    * Reflectively looks up an instantiatable class using the system class loader.
    *
    * @param fqcn
    *   fully-qualified name of the class.
    */
  def lookupInstantiatableClass(fqcn: String): Option[InstantiatableClass] =
    lookupInstantiatableClass(fqcn, defaultLoader)

  /**
    * Reflectively looks up an instantiatable class using the given class loader.
    *
    * @param fqcn
    *   fully-qualified name of the class.
    * @param loader
    *   class loader used to resolve `fqcn`.
    */
  def lookupInstantiatableClass(fqcn: String, loader: ClassLoader): Option[InstantiatableClass] =
    load(fqcn, loader).filter(isInstantiatableClass).map(new InstantiatableClass(_))

  /**
    * Returns the singleton companion object of `cls` via direct reflection. This bypasses the
    * `@EnableReflectiveInstantiation` requirement of [[lookupLoadableModuleClass]] and is useful
    * when porting JVM-only code that previously relied on `airframe.surface.reflect`.
    *
    * On Scala.js / Scala Native this method requires the class to be annotated with
    * [[wvlet.uni.reflect.annotation.EnableReflectiveInstantiation]] so the linker keeps the
    * companion's metadata.
    */
  def companionOf(cls: Class[?]): Option[Any] =
    val name          = cls.getName
    val companionName =
      if name.endsWith("$") then
        name
      else
        s"${name}$$"
    val loader = Option(cls.getClassLoader).getOrElse(defaultLoader)
    try
      val companionCls = Class.forName(companionName, false, loader)
      val fld          = companionCls.getField("MODULE$")
      if (fld.getModifiers & Modifier.STATIC) != 0 then
        Option(fld.get(null))
      else
        None
    catch
      case _: ClassNotFoundException | _: NoSuchFieldException =>
        None

  // -- private helpers ------------------------------------------------------

  private def defaultLoader: ClassLoader = Option(Thread.currentThread.getContextClassLoader)
    .getOrElse(getClass.getClassLoader)

  private def isModuleClass(clazz: Class[?]): Boolean =
    try
      val fld = clazz.getField("MODULE$")
      clazz.getName.endsWith("$") && (fld.getModifiers & Modifier.STATIC) != 0
    catch
      case _: NoSuchFieldException =>
        false

  private def isInstantiatableClass(clazz: Class[?]): Boolean =
    // A local class has a non-null *enclosing* class but a null *declaring* class.
    def isLocalClass: Boolean = clazz.getEnclosingClass != clazz.getDeclaringClass

    (clazz.getModifiers & Modifier.ABSTRACT) == 0 && clazz.getConstructors.length > 0 &&
    !isModuleClass(clazz) && !isLocalClass

  private def load(fqcn: String, loader: ClassLoader): Option[Class[?]] =
    try
      // initialize=false so module-class constructors only run when loadModule() is called.
      val clazz = Class.forName(fqcn, false, loader)
      if inheritsAnnotation(clazz) then
        Some(clazz)
      else
        None
    catch
      case _: ClassNotFoundException =>
        None

  private def inheritsAnnotation(clazz: Class[?]): Boolean =
    val cache = mutable.Map.empty[Class[?], Boolean]

    def check(c: Class[?]): Boolean = cache.getOrElseUpdate(c, walk(c))

    def walk(c: Class[?]): Boolean =
      if c.getAnnotation(classOf[EnableReflectiveInstantiation]) != null then
        true
      else
        (Iterator(c.getSuperclass) ++ c.getInterfaces.iterator).filter(_ != null).exists(check)

    check(clazz)

end Reflect

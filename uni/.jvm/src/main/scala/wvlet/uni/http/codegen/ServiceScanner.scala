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
package wvlet.uni.http.codegen

import wvlet.uni.log.LogSupport

import java.lang.reflect.{Method, Modifier}

/**
  * Scans compiled .class files via Java reflection to extract service trait metadata for code
  * generation. Replaces TastyServiceScanner — no scala3-tasty-inspector dependency needed.
  */
object ServiceScanner extends LogSupport:

  /**
    * Scan a service class by name and extract a ServiceDef.
    *
    * @param className
    *   Fully-qualified class name (e.g., "com.example.api.UserService")
    * @param classLoader
    *   ClassLoader that can find the compiled class
    */
  def scan(className: String, classLoader: ClassLoader): ServiceDef =
    val clazz       = classLoader.loadClass(className)
    val packageName = extractPackageName(className)
    val simpleName  = clazz.getSimpleName

    val excludedNames = Set(
      "hashCode",
      "equals",
      "toString",
      "getClass",
      "notify",
      "notifyAll",
      "wait",
      "clone",
      "finalize",
      "$init$"
    )

    val methods =
      clazz
        .getMethods
        .filter { m =>
          val mod = m.getModifiers
          !excludedNames.contains(m.getName) && Modifier.isPublic(mod) &&
          Modifier.isAbstract(mod) && m.getDeclaringClass != classOf[Object]
        }
        .map(extractMethodDef(_, className))
        .toSeq

    ServiceDef(packageName, simpleName, methods)

  end scan

  private def extractPackageName(className: String): String =
    val lastDot = className.lastIndexOf('.')
    if lastDot >= 0 then
      className.substring(0, lastDot)
    else
      ""

  private def extractMethodDef(m: Method, serviceFullName: String): MethodDef =
    val params = m
      .getParameters
      .toSeq
      .map { p =>
        ParamDef(
          name = p.getName,
          typeName = classToTypeRef(p.getType),
          hasDefault = false,
          isOption = classOf[Option[?]].isAssignableFrom(p.getType)
        )
      }

    val returnType = classToTypeRef(m.getReturnType)

    MethodDef(
      name = m.getName,
      httpMethod = "POST",
      path = s"/${serviceFullName}/${m.getName}",
      params = params,
      returnType = returnType,
      isRPC = true
    )

  private def classToTypeRef(clazz: Class[?]): TypeRef =
    val fullName =
      clazz.getName match
        case "void" =>
          "scala.Unit"
        case "int" =>
          "scala.Int"
        case "long" =>
          "scala.Long"
        case "double" =>
          "scala.Double"
        case "float" =>
          "scala.Float"
        case "boolean" =>
          "scala.Boolean"
        case "byte" =>
          "scala.Byte"
        case "short" =>
          "scala.Short"
        case "char" =>
          "scala.Char"
        case other =>
          other

    val shortName = fullName.split('.').last
    TypeRef(fullName, shortName)

end ServiceScanner

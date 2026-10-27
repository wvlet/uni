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
package wvlet.uni.surface

/**
  * Represents an annotation with its parameters extracted at compile time. This allows reading
  * annotation values without JVM reflection.
  *
  * @param name
  *   Simple name of the annotation class
  * @param fullName
  *   Fully qualified name of the annotation class
  * @param params
  *   Map of parameter names to their values. Supports primitive types (String, Int, Long, Float,
  *   Double, Boolean, Byte, Short, Char), Class[?], and arrays of these types.
  */
case class Annotation(name: String, fullName: String, params: Map[String, Any] = Map.empty)
    extends Serializable:
  /**
    * Get a parameter value by name
    */
  def get(paramName: String): Option[Any] = params.get(paramName)

  /**
    * Get a parameter value with expected type
    */
  def getAs[T](paramName: String): Option[T] = params
    .get(paramName)
    .flatMap(v => scala.util.Try(v.asInstanceOf[T]).toOption)

  /**
    * Check if this annotation has the given name
    */
  def is(annotationName: String): Boolean = name == annotationName || fullName == annotationName

  override def toString: String =
    if params.isEmpty then
      s"@${name}"
    else
      s"@${name}(${params.map((k, v) => s"${k}=${v}").mkString(", ")})"

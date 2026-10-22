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

import scala.language.existentials

/**
  */
import scala.language.existentials

/**
  * ObjectFactory is a trait for creating new instances of objects. It provides a generic way to
  * instantiate objects with given arguments.
  */
trait ObjectFactory extends Serializable:
  def newInstance(args: Seq[Any]): Any

object ObjectFactory:

  /**
    * Used internally for creating a new ObjectFactory instance from a given generic function
    * @param f
    * @return
    */
  def newFactory(f: Seq[Any] => Any): ObjectFactory =
    new ObjectFactory:
      override def newInstance(args: Seq[Any]): Any = f(args)

object Primitive:
  import java.lang as jl

  private[surface] val primitiveTable =
    val b = Map.newBuilder[Class[?], PrimitiveSurface]
    b += classOf[jl.Boolean]           -> Boolean
    b += classOf[Boolean]              -> Boolean
    b += classOf[jl.Short]             -> Short
    b += classOf[Short]                -> Short
    b += classOf[jl.Byte]              -> Byte
    b += classOf[Byte]                 -> Byte
    b += classOf[jl.Character]         -> Char
    b += classOf[Char]                 -> Char
    b += classOf[jl.Integer]           -> Int
    b += classOf[Int]                  -> Int
    b += classOf[jl.Float]             -> Float
    b += classOf[Float]                -> Float
    b += classOf[jl.Long]              -> Long
    b += classOf[Long]                 -> Long
    b += classOf[jl.Double]            -> Double
    b += classOf[Double]               -> Double
    b += classOf[jl.String]            -> String
    b += classOf[String]               -> String
    b += classOf[BigInt]               -> BigInt
    b += classOf[java.math.BigInteger] -> BigInteger
    b.result()

  def apply(cl: Class[?]): PrimitiveSurface = primitiveTable(cl)

  sealed abstract class PrimitiveSurface(override val rawType: Class[?])
      extends GenericSurface(rawType):
    override def isPrimitive: Boolean = true

  case object Int extends PrimitiveSurface(classOf[Int]):
    override def name: String     = "Int"
    override def fullName: String = "Int"

  case object Byte extends PrimitiveSurface(classOf[Byte]):
    override def name: String     = "Byte"
    override def fullName: String = "Byte"

  case object Long extends PrimitiveSurface(classOf[Long]):
    override def name: String     = "Long"
    override def fullName: String = "Long"

  case object Short extends PrimitiveSurface(classOf[Short]):
    override def name: String     = "Short"
    override def fullName: String = "Short"

  case object Boolean extends PrimitiveSurface(classOf[Boolean]):
    override def name: String     = "Boolean"
    override def fullName: String = "Boolean"

  case object Float extends PrimitiveSurface(classOf[Float]):
    override def name: String     = "Float"
    override def fullName: String = "Float"

  case object Double extends PrimitiveSurface(classOf[Double]):
    override def name: String     = "Double"
    override def fullName: String = "Double"

  case object Char extends PrimitiveSurface(classOf[Char]):
    override def name: String     = "Char"
    override def fullName: String = "Char"

  case object String extends PrimitiveSurface(classOf[String])

  case object Unit extends PrimitiveSurface(classOf[Unit]):
    override def name: String     = "Unit"
    override def fullName: String = "Unit"

  case object BigInt     extends PrimitiveSurface(classOf[BigInt])
  case object BigInteger extends PrimitiveSurface(classOf[java.math.BigInteger])

end Primitive

case class Alias(override val name: String, override val fullName: String, ref: Surface)
    extends GenericSurface(
      ref.rawType,
      ref.typeArgs,
      ref.params,
      ref.objectFactory,
      ref.annotations
    ):
  override def toString: String     = s"${name}:=${ref.name}"
  override def isAlias: Boolean     = true
  override def isPrimitive: Boolean = ref.isPrimitive
  override def isOption: Boolean    = ref.isOption
  override def dealias: Surface     = ref.dealias

case class HigherKindedTypeSurface(
    override val name: String,
    override val fullName: String,
    ref: Surface,
    override val typeArgs: Seq[Surface]
) extends GenericSurface(ref.rawType, typeArgs, ref.params, ref.objectFactory):
  override def toString: String =
    val s =
      if typeArgs.isEmpty then
        name
      else
        s"${name}[${typeArgs.mkString(",")}]"
    TypeName.sanitizeTypeName(s)

  override def isAlias: Boolean     = false
  override def isPrimitive: Boolean = ref.isPrimitive
  override def isOption: Boolean    = ref.isOption
  override def dealias: Surface     = ref.dealias

case object ExistentialType extends GenericSurface(classOf[Any]):
  override def name: String     = "?"
  override def fullName: String = "?"

case class ArraySurface(override val rawType: Class[?], elementSurface: Surface)
    extends GenericSurface(rawType, Seq(elementSurface)):
  override def name: String     = s"Array[${elementSurface.name}]"
  override def fullName: String = s"Array[${elementSurface.fullName}]"
  override def toString: String = name

case class OptionSurface(override val rawType: Class[?], elementSurface: Surface)
    extends GenericSurface(rawType, Seq(elementSurface)):
  override def isOption: Boolean = true

case class JavaEnumSurface(override val rawType: Class[?]) extends GenericSurface(rawType)

/**
  * Enum-like surface for Scala 2.x and Scala 3
  * @param rawType
  * @param stringExtractor
  */
case class EnumSurface(
    override val rawType: Class[?],
    stringExtractor: (Class[?], String) => Option[Any]
) extends GenericSurface(rawType)

case class TupleSurface(override val rawType: Class[?], override val typeArgs: Seq[Surface])
    extends GenericSurface(rawType, typeArgs) {}

case class TaggedSurface(base: Surface, tag: Surface) extends Surface:
  override def toString: String       = name
  override def rawType: Class[?]      = base.rawType
  override def typeArgs: Seq[Surface] = base.typeArgs
  override def params: Seq[Parameter] = base.params
  override def name: String           = s"${base.name}@@${tag.name}"
  override def fullName: String       = s"${base.fullName}@@${tag.fullName}"
  override def isOption: Boolean      = base.isOption
  override def isAlias: Boolean       = base.isAlias
  override def isPrimitive: Boolean   = base.isPrimitive
  override def dealias: Surface       = base.dealias

  override def objectFactory: Option[ObjectFactory] = base.objectFactory
  override def annotations: Seq[Annotation]         = base.annotations

/**
  * Represents Scala 3 intersection types, in which the left type is the primary type. This is
  * because applying intersection is challenging without knowing the application context.
  * @param left
  * @param right
  */
case class IntersectionSurface(left: Surface, right: Surface) extends Surface:
  override def toString: String                     = name
  override def rawType: Class[?]                    = left.rawType
  override def typeArgs: Seq[Surface]               = left.typeArgs
  override def params: Seq[Parameter]               = left.params
  override def name: String                         = s"${left.name}&${right.name}"
  override def fullName: String                     = s"${left.fullName}&${right.fullName}"
  override def isOption: Boolean                    = left.isOption
  override def isAlias: Boolean                     = left.isAlias
  override def isPrimitive: Boolean                 = left.isPrimitive
  override def dealias: Surface                     = left.dealias
  override def objectFactory: Option[ObjectFactory] = left.objectFactory

/**
  * Represents Scala 3 union types, in which the left type is the primary type. This is because
  * deciding which side of types in the union is difficult without knowing the application context.
  * @param left
  * @param right
  */
case class UnionSurface(left: Surface, right: Surface) extends Surface:
  override def toString: String                     = name
  override def rawType: Class[?]                    = left.rawType
  override def typeArgs: Seq[Surface]               = left.typeArgs
  override def params: Seq[Parameter]               = left.params
  override def name: String                         = s"${left.name}|${right.name}"
  override def fullName: String                     = s"${left.fullName}|${right.fullName}"
  override def isOption: Boolean                    = left.isOption
  override def isAlias: Boolean                     = left.isAlias
  override def isPrimitive: Boolean                 = left.isPrimitive
  override def dealias: Surface                     = UnionSurface(left.dealias, right.dealias)
  override def objectFactory: Option[ObjectFactory] = left.objectFactory.orElse(right.objectFactory)

case object AnyRefSurface extends GenericSurface(classOf[AnyRef]):
  override def name: String = "AnyRef"

/**
  * Base class for generic surfaces with type args
  *
  * @param rawType
  * @param typeArgs
  * @param params
  * @param objectFactory
  * @param annotations
  *   Annotations on the class
  */
class GenericSurface(
    override val rawType: Class[?],
    override val typeArgs: Seq[Surface] = Seq.empty,
    override val params: Seq[Parameter] = Seq.empty,
    override val objectFactory: Option[ObjectFactory] = None,
    override val annotations: Seq[Annotation] = Seq.empty
) extends Surface:
  // Keeps code generated by earlier Surface macros, which call this signature, linking
  def this(
      rawType: Class[?],
      typeArgs: Seq[Surface],
      params: Seq[Parameter],
      objectFactory: Option[ObjectFactory]
  ) = this(rawType, typeArgs, params, objectFactory, Seq.empty)

  private def getClassName: String =
    try
      TypeName.sanitizeTypeName(rawType.getSimpleName)
    catch
      case e: InternalError =>
        // Scala REPL use class name like $line3.$read$$iw$$iw$A, which causes InternalError at getSimpleName
        TypeName.sanitizeTypeName(rawType.getName)

  override def name: String =
    val clsName = TypeName.sanitizeTypeName(getClassName)
    val s       =
      if typeArgs.isEmpty then
        clsName
      else
        s"${clsName}[${typeArgs.map(_.name).mkString(",")}]"
    TypeName.sanitizeTypeName(s)

  override def fullName: String =
    val clsName = TypeName.sanitizeTypeName(rawType.getName)
    val s       =
      if typeArgs.isEmpty then
        clsName
      else
        s"${clsName}[${typeArgs.map(_.fullName).mkString(",")}]"
    TypeName.sanitizeTypeName(s)

  override def isOption: Boolean    = false
  override def isAlias: Boolean     = false
  override def isPrimitive: Boolean = false

  override def toString: String = name

  override def equals(obj: Any): Boolean =
    obj match
      case f: Surface =>
        this.fullName.equals(f.fullName)
      case _ =>
        false

  override def hashCode(): Int = fullName.hashCode

end GenericSurface

/**
  * Surface placeholder for supporting recursive types
  * @param rawType
  */
case class LazySurface(override val rawType: Class[?], fullName: String) extends Surface:
  // Resolved the final type from the full surface name
  protected def ref: Surface = Surface.getCached(fullName)

  def name: String =
    val s =
      if typeArgs.isEmpty then
        rawType.getSimpleName
      else
        s"${rawType.getSimpleName}[${typeArgs.map(_.name).mkString(",")}]"
    TypeName.sanitizeTypeName(s)

  override def toString: String                     = name
  override def params                               = ref.params
  override def typeArgs: Seq[Surface]               = ref.typeArgs
  override def isOption                             = ref.isOption
  override def isAlias                              = ref.isAlias
  override def isPrimitive                          = ref.isPrimitive
  override def objectFactory: Option[ObjectFactory] = ref.objectFactory
  override def annotations: Seq[Annotation]         = ref.annotations

case class ClassMethodSurface(
    mod: Int,
    owner: Surface,
    name: String,
    returnType: Surface,
    args: Seq[MethodParameter],
    methodCaller: Option[(Any, Seq[Any]) => Any],
    override val annotations: Seq[Annotation] = Seq.empty,
    override val declaringType: Option[String] = None
) extends MethodSurface:
  override def call(obj: Any, x: Any*) =
    def unsupported =
      throw new UnsupportedOperationException(s"Calling method ${name} is not supported: ${this}")

    methodCaller
      .map { caller =>
        caller(obj, x.toSeq)
      }
      .getOrElse {
        unsupported
      }

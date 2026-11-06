package wvlet.uni.weaver

import scala.quoted.*
import wvlet.uni.surface.EnumSurface
import wvlet.uni.surface.Surface
import wvlet.uni.weaver.codec.CaseClassWeaver
import wvlet.uni.weaver.codec.EnumWeaver
import wvlet.uni.weaver.codec.SealedTraitWeaver
import wvlet.uni.weaver.codec.SingletonWeaver

/**
  * Compile-time derivation of Weaver for case classes and sealed traits using Scala 3 macros.
  */
object WeaverDerivation:

  /**
    * Derive a Weaver for type A at compile-time.
    */
  inline def deriveWeaver[A]: Weaver[A] =
    ${
      deriveWeaverImpl[A]
    }

  private def deriveWeaverImpl[A: Type](using Quotes): Expr[Weaver[A]] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[A]
    val symbol = tpe.typeSymbol
    val flags  = symbol.flags

    // A parameterized enum case (e.g., `ENode.Split`) is a case class that is also a subtype of
    // scala.reflect.Enum, so check case classes before enums
    if flags.is(Flags.Case) then
      deriveCaseClassWeaver[A]
    // Enums are also sealed, so check them before sealed traits. An enum whose cases are all
    // singletons packs as a string; an enum with a parameterized case (a class, not a term)
    // needs its fields, so it uses the sealed-trait format.
    else if tpe <:< TypeRepr.of[scala.reflect.Enum] then
      if symbol.children.forall(_.isTerm) then
        deriveEnumWeaver[A]
      else
        deriveSealedTraitWeaver[A]
    // Check if sealed trait or sealed abstract class
    else if flags.is(Flags.Sealed) && (flags.is(Flags.Trait) || flags.is(Flags.Abstract)) then
      deriveSealedTraitWeaver[A]
    else
      report.errorAndAbort(
        s"Weaver.derived can only be used with case classes, sealed traits, or enums, but ${symbol
            .fullName} is neither"
      )

  end deriveWeaverImpl

  private def deriveCaseClassWeaver[A: Type](using Quotes): Expr[Weaver[A]] =
    import quotes.reflect.*

    val tpe    = TypeRepr.of[A]
    val symbol = tpe.typeSymbol

    // Get the surface for this type
    val surfaceExpr =
      '{
        Surface.of[A]
      }

    // Get the constructor parameters
    val params = symbol.primaryConstructor.paramSymss.flatten.filterNot(_.isTypeParam)

    // Prefer an in-scope `given Weaver[t]`; fall back to a Surface-driven runtime weaver so
    // open hierarchies (abstract classes, `Option[Animal]`, etc.) compile without registration.
    val fieldWeaverExprs: List[Expr[Weaver[?]]] = params.map { param =>
      val paramType = tpe.memberType(param)
      paramType.asType match
        case '[t] =>
          Expr.summon[Weaver[t]] match
            case Some(weaver) =>
              weaver.asExprOf[Weaver[?]]
            case None =>
              '{
                Weaver.fromSurface(Surface.of[t]).asInstanceOf[Weaver[t]]
              }.asExprOf[Weaver[?]]
    }

    // Build IndexedSeq of field weavers
    val fieldWeaversExpr: Expr[IndexedSeq[Weaver[?]]] =
      '{
        IndexedSeq(
          ${
            Varargs(fieldWeaverExprs)
          }*
        )
      }

    // Create the CaseClassWeaver
    '{
      new CaseClassWeaver[A]($surfaceExpr, $fieldWeaversExpr)
    }

  end deriveCaseClassWeaver

  private def deriveSealedTraitWeaver[A: Type](using Quotes): Expr[Weaver[A]] =
    import quotes.reflect.*

    val tpe       = TypeRepr.of[A]
    val symbol    = tpe.typeSymbol
    val traitName = symbol.name

    // Get all direct children of the sealed trait
    val children = symbol.children

    if children.isEmpty then
      report.errorAndAbort(
        s"Sealed trait ${symbol
            .fullName} has no children. Add case classes or case objects that extend it."
      )

    // Build child weaver entries: (name, (weaver, Option[singleton]))
    val childEntries: List[Expr[(String, (Weaver[? <: A], Option[A]))]] = children.map { childSym =>
      val childName = childSym.name.stripSuffix("$")
      // Case objects are modules; enum cases without parameters are vals
      val isSingleton = childSym.flags.is(Flags.Module) || childSym.isTerm
      val entry: Expr[(Weaver[? <: A], Option[A])] =
        if isSingleton then
          // Cast instead of asExprOf: in a generic trait (`Box[T]`), `case object NoBox extends
          // Box[Int]` does not conform to `Box[T]`
          val ref = Ref(childSym).asExpr
          '{
            val instance = $ref.asInstanceOf[A]
            (SingletonWeaver[A](instance), Some(instance))
          }
        else
          childSym.typeRef.asType match
            case '[t] =>
              // Enum cases can't have `derives Weaver`, so derive a child without a given here
              val weaverExpr = Expr
                .summon[Weaver[t]]
                .getOrElse {
                  val isGeneric = childSym
                    .primaryConstructor
                    .paramSymss
                    .flatten
                    .exists(_.isTypeParam)
                  if !childSym.flags.is(Flags.Case) && !childSym.flags.is(Flags.Sealed) || isGeneric
                  then
                    report.errorAndAbort(
                      s"No Weaver found for child type '${childName}' of ${symbol.fullName}. " +
                        "Make sure it has 'derives Weaver'."
                    )
                  deriveWeaverImpl[t]
                }
              '{
                ($weaverExpr.asInstanceOf[Weaver[? <: A]], None)
              }
      '{
        (
          ${
            Expr(childName)
          },
          $entry
        )
      }
    }

    // Build the Map expression
    val mapExpr: Expr[Map[String, (Weaver[? <: A], Option[A])]] =
      '{
        Map(
          ${
            Varargs(childEntries)
          }*
        )
      }

    // Create the SealedTraitWeaver
    '{
      new SealedTraitWeaver[A](
        ${
          Expr(traitName)
        },
        $mapExpr
      )
    }

  end deriveSealedTraitWeaver

  private def deriveEnumWeaver[A: Type](using Quotes): Expr[Weaver[A]] =
    // The compile-time check (tpe <:< TypeRepr.of[scala.reflect.Enum]) guarantees
    // Surface.of[A] returns EnumSurface, so the cast is safe.
    '{
      EnumWeaver[A](Surface.of[A].asInstanceOf[EnumSurface])
    }

end WeaverDerivation

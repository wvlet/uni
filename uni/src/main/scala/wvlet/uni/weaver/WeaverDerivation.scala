package wvlet.uni.weaver

import scala.quoted.*
import wvlet.uni.surface.EnumSurface
import wvlet.uni.surface.Surface
import wvlet.uni.weaver.codec.CaseClassWeaver
import wvlet.uni.weaver.codec.EnumWeaver
import wvlet.uni.weaver.codec.SealedTraitWeaver

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

    // Check for Scala 3 enum first (enums are also sealed, so check before sealed trait)
    if tpe <:< TypeRepr.of[scala.reflect.Enum] then
      deriveEnumWeaver[A]
    // Check if sealed trait or sealed abstract class
    else if flags.is(Flags.Sealed) && (flags.is(Flags.Trait) || flags.is(Flags.Abstract)) then
      deriveSealedTraitWeaver[A]
    else if flags.is(Flags.Case) then
      deriveCaseClassWeaver[A]
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
      val childName    = childSym.name.stripSuffix("$")
      val isCaseObject = childSym.flags.is(Flags.Module)
      val childType    =
        if isCaseObject then
          childSym.termRef
        else
          childSym.typeRef

      childType.asType match
        case '[t] =>
          Expr.summon[Weaver[t]] match
            case Some(weaverExpr) =>
              val singletonExpr: Expr[Option[A]] =
                if isCaseObject then
                  val moduleRef = Ref(childSym)
                  '{
                    Some(
                      ${
                        moduleRef.asExprOf[t]
                      }.asInstanceOf[A]
                    )
                  }
                else
                  '{
                    None
                  }

              '{
                (
                  ${
                    Expr(childName)
                  },
                  (
                    ${
                      weaverExpr
                    }.asInstanceOf[Weaver[? <: A]],
                    $singletonExpr
                  )
                )
              }
            case None =>
              val targetType =
                if isCaseObject then
                  "case object"
                else
                  "child type"
              report.errorAndAbort(
                s"No Weaver found for ${targetType} '${childName}' of sealed trait ${symbol
                    .fullName}. " + s"Make sure it has 'derives Weaver'."
              )
      end match
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

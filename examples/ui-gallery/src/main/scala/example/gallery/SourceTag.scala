package example.gallery

import org.scalajs.dom
import wvlet.uni.dom.all.*

import scala.collection.mutable
import scala.quoted.*

/**
  * Ties each uni-ui component in a story to the line of code that creates it. `SourceTag(story)` is
  * a macro: at compile time it finds every expression in the story's code that builds a component
  * from `wvlet.uni.ui`, and adds `data-src-kind` (the component's name) and `data-src-line` (its
  * line in the source file) to it. The gallery reads those attributes to link a part of the preview
  * to its code and back.
  *
  * Story `def`s are `inline`, so the macro sees their code. The code panel drops that modifier
  * (project/StoryRegions.scala), so the code readers copy is a plain method.
  */
object SourceTag:
  /** A component the story's code creates, and on which line. */
  case class Tag(kind: String, line: Int)

  /** Code whose components carry their source lines, with the components it creates. */
  case class Tagged[A](value: A, tags: Seq[Tag])

  /** Called by the code the macro writes; not meant for stories themselves. */
  def tag(e: RxElement, kind: String, line: Int): RxElement =
    // Set the attributes on mount: a closed dialog renders a placeholder that isn't an element
    new RxElement(e.modifiers):
      override def beforeRender: Unit       = e.beforeRender
      override def render: RxElement        = e.render
      override def onMount(node: Any): Unit =
        node match
          case el: dom.Element =>
            el.setAttribute("data-src-kind", kind)
            el.setAttribute("data-src-line", line.toString)
          case _ =>
        e.onMount(node)

      override def beforeUnmount: Unit = e.beforeUnmount

  /**
    * Tag the components that `code` creates, and list them. `code` is usually a function that
    * builds the story, such as `() => buttons` or `(p: ButtonProps) => buttonProps(p)`.
    */
  inline def apply[A](inline code: A): Tagged[A] =
    ${
      tagged('code)
    }

  private def tagged[A: Type](code: Expr[A])(using Quotes): Expr[Tagged[A]] =
    import quotes.reflect.*

    val found = mutable.ListBuffer.empty[(String, Int, Int)]

    val rxElement = TypeRepr.of[RxElement]
    val tagObject = Symbol.requiredModule("example.gallery.SourceTag")
    val tagMethod = tagObject.moduleClass.declaredMethod("tag").head

    // The expression under the wrappers an `inline def` call leaves, such as `buttonProps(p)`
    def core(t: Term): Term =
      t match
        case Inlined(_, _, expansion) =>
          core(expansion)
        case Block(Nil, expr) =>
          core(expr)
        case Typed(expr, _) =>
          // The inline def's declared result type, such as `: RxElement`; varargs stay as they are
          expr match
            case Repeated(_, _) =>
              t
            case _ =>
              core(expr)
        case other =>
          other

    def isComponent(t: Term): Boolean =
      val c   = core(t)
      val tpe = c.tpe.widen
      tpe <:< rxElement && tpe.typeSymbol.fullName.startsWith("wvlet.uni.ui.") && (
        c match
          case _: Ident =>
            false // a reference to a component built elsewhere, such as a val
          case _ =>
            true
      )

    // A tagged component is a plain RxElement, so tag only where an RxElement fits
    def fits(param: TypeRepr): Boolean =
      param match
        case ByNameType(u) =>
          rxElement <:< u
        case p =>
          rxElement <:< p

    def wrap(t: Term): Term =
      val c    = core(t)
      val kind = c.tpe.widen.typeSymbol.name
      val line = c.pos.startLine + 1
      found += ((kind, line, c.pos.startColumn))
      // Built with reflection, not a quote: a quote would re-own the lambdas inside `t`
      Ref(tagObject)
        .select(tagMethod)
        .appliedToArgs(List(t, Literal(StringConstant(kind)), Literal(IntConstant(line))))

    object Tagger extends TreeMap:
      private def arg(a: Term, param: TypeRepr)(owner: Symbol): Term =
        a match
          case NamedArg(name, value) =>
            NamedArg.copy(a)(name, arg(value, param)(owner))
          case Typed(Repeated(elems, elemTpt), tpt) =>
            val elemType = elemTpt.tpe
            Typed.copy(a)(
              Repeated.copy(a.asInstanceOf[Typed].expr)(
                elems.map(e => arg(e, elemType)(owner)),
                elemTpt
              ),
              tpt
            )
          case _ =>
            val t = transformTerm(a)(owner)
            if isComponent(a) && fits(param) then
              wrap(t)
            else
              t

      override def transformTerm(tree: Term)(owner: Symbol): Term =
        tree match
          case Apply(fn, args) =>
            val params =
              fn.tpe.widen match
                case MethodType(_, types, _) =>
                  types
                case _ =>
                  Nil
            val newArgs = args
              .zipWithIndex
              .map { (a, i) =>
                params.lift(i) match
                  case Some(p) =>
                    arg(a, p)(owner)
                  case None =>
                    transformTerm(a)(owner)
              }
            Apply.copy(tree)(transformTerm(fn)(owner), newArgs)
          case _ =>
            super.transformTerm(tree)(owner)

      // A lambda whose result is a component, such as `(p: ButtonProps) => buttonProps(p)`
      override def transformStatement(tree: Statement)(owner: Symbol): Statement =
        tree match
          case dd @ DefDef(name, params, tpt, Some(rhs)) if name == "$anonfun" =>
            val body = transformTerm(rhs)(dd.symbol)
            DefDef.copy(dd)(
              name,
              params,
              tpt,
              Some(
                if isComponent(rhs) && fits(tpt.tpe) then
                  wrap(body)
                else
                  body
              )
            )
          case _ =>
            super.transformStatement(tree)(owner)

    end Tagger

    val value = Tagger.transformTerm(code.asTerm)(Symbol.spliceOwner).asExprOf[A]
    val tags  = Expr.ofSeq(
      found
        .distinct
        .sortBy((_, l, c) => (l, c))
        .toSeq
        .map((k, l, _) =>
          '{
            Tag(
              ${
                Expr(k)
              },
              ${
                Expr(l)
              }
            )
          }
        )
    )
    '{
      Tagged(
        ${
          value
        },
        ${
          tags
        }
      )
    }

  end tagged

end SourceTag

package example.gallery

import scala.quoted.*

/**
  * One entry of a component's API: how to create it, an option (a method that returns the
  * component, such as `withLabel(l: String)`), or another method (such as `Toaster.info`), with the
  * first sentence of its doc comment.
  */
case class ApiEntry(component: String, kind: String, signature: String, doc: String)

/**
  * Lists a uni-ui component's API at compile time from its declarations, so the gallery's API
  * tables can't drift from the code. The build reads doc comments from TASTy (`-Xread-docs`).
  */
object ApiDoc:
  inline def of[C]: Seq[ApiEntry] =
    ${
      entries[C]
    }

  private val lifecycle = Set(
    "render",
    "beforeRender",
    "onMount",
    "beforeUnmount",
    "copy",
    "toString",
    "equals",
    "hashCode",
    "traverseModifiers",
    "addModifier",
    "add",
    "apply"
  )

  private def entries[C: Type](using Quotes): Expr[Seq[ApiEntry]] =
    import quotes.reflect.*

    val cls  = TypeRepr.of[C].typeSymbol
    val name = cls.name

    def firstSentence(doc: String): String =
      val text =
        doc
          .stripPrefix("/**")
          .stripSuffix("*/")
          .linesIterator
          .map(_.trim.stripPrefix("*").trim)
          .takeWhile(l => !l.startsWith("@") && !l.startsWith("{{{"))
          .mkString(" ")
          .replaceAll("""\[\[([^\]]+)\]\]""", "$1")
          .trim
      val end = text.indexOf(". ")
      if end >= 0 then
        text.take(end + 1)
      else
        text

    // Types as Scala code reads them: `DomNode*`, `() => Unit`, `A => B`
    def typeText(t: TypeRepr): String =
      t.widen match
        case AnnotatedType(AppliedType(_, List(e)), _) =>
          s"${typeText(e)}*"
        case AppliedType(f, args) if f.typeSymbol.fullName.startsWith("scala.Function") =>
          val params = args.init.map(typeText)
          val in     =
            if params.size == 1 then
              params.head
            else
              params.mkString("(", ", ", ")")
          s"${in} => ${typeText(args.last)}"
        case ByNameType(u) =>
          s"=> ${typeText(u)}"
        case other =>
          other.show(using Printer.TypeReprShortCode)

    def signature(owner: TypeRepr, m: Symbol, display: String): String =
      val params =
        m.paramSymss
          .filterNot(_.exists(_.isTypeParam))
          .map(ps =>
            ps.map(p => s"${p.name}: ${typeText(owner.memberType(p))}").mkString("(", ", ", ")")
          )
          .mkString
      s"${display}${params}"

    def isPublic(m: Symbol): Boolean =
      !m.flags.is(Flags.Private) && !m.flags.is(Flags.Protected) && !m.flags.is(Flags.Synthetic) &&
        !m.name.contains("$") && m.privateWithin.isEmpty

    def result(t: TypeRepr): TypeRepr =
      t match
        case MethodType(_, _, res) =>
          result(res)
        case PolyType(_, _, res) =>
          result(res)
        case ByNameType(res) =>
          result(res)
        case other =>
          other

    def doc(m: Symbol): String = m.docstring.map(firstSentence).getOrElse("")

    val self    = cls.typeRef
    val members = cls
      .declaredMethods
      .filter(m => isPublic(m) && !lifecycle.contains(m.name) && !m.isClassConstructor)
      .sortBy(_.name)
      .map { m =>
        val kind =
          if result(self.memberType(m)).typeSymbol == cls then
            "option"
          else
            "method"
        (kind, signature(self, m, s".${m.name}"), doc(m))
      }

    val module   = cls.companionModule
    val creators =
      if module.isNoSymbol then
        Nil
      else
        module
          .moduleClass
          .declaredMethods
          .filter(m => m.name == "apply" && isPublic(m))
          .map(m => ("create", signature(module.termRef, m, name), doc(m)))
    // A class without a companion apply is created with its constructor
    val constructor =
      if creators.nonEmpty then
        Nil
      else
        Seq(("create", signature(self, cls.primaryConstructor, name), doc(cls.primaryConstructor)))

    val all = (creators ++ constructor ++ members).distinctBy(_._2)
    Expr.ofSeq(
      all.map((k, s, d) =>
        '{
          ApiEntry(
            ${
              Expr(name)
            },
            ${
              Expr(k)
            },
            ${
              Expr(s)
            },
            ${
              Expr(d)
            }
          )
        }
      )
    )

  end entries

end ApiDoc

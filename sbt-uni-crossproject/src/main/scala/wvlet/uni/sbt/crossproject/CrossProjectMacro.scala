/*
 * sbt-uni-crossproject: a minimal sbt 2.x re-implementation of portable-scala/sbt-crossproject,
 * supporting only the CrossType.Pure layout used by uni. Licensed under Apache License 2.0.
 */
package wvlet.uni.sbt.crossproject

import scala.annotation.tailrec
import scala.quoted.*

/**
 * Implements the `crossProject(...)` macro. Like sbt 2.x's own `project` macro, it derives the
 * cross-project id from the enclosing `val` name, so `val core = crossProject(...)` yields
 * sub-projects `coreJVM`, `coreJS`, `coreNative`. The enclosing-val lookup mirrors sbt's
 * `KeyMacro.definingValName` so it behaves identically.
 */
private[crossproject] object CrossProjectMacro {

  def crossProjectImpl(
      platforms: Expr[Seq[Platform]]
  )(using Quotes): Expr[CrossProject.Builder] = {
    val name = definingValName(
      "crossProject must be directly assigned to a val, " +
        "such as `val x = crossProject(JVMPlatform, JSPlatform)`."
    )
    '{ CrossProject(${ name }, new java.io.File(${ name }))(${ platforms }*) }
  }

  private def definingValName(errorMsg: String)(using Quotes): Expr[String] = {
    import quotes.reflect.*
    val term = enclosingTerm
    if term.isValDef then Expr(term.name)
    else report.errorAndAbort(errorMsg)
  }

  private def enclosingTerm(using qctx: Quotes): qctx.reflect.Symbol = {
    import qctx.reflect.*
    @tailrec
    def enclosingTerm0(sym: Symbol): Symbol =
      sym match {
        case sym if sym.flags.is(Flags.Macro)     => enclosingTerm0(sym.owner)
        case sym if sym.flags.is(Flags.Synthetic) => enclosingTerm0(sym.owner)
        case sym if !sym.isTerm                    => enclosingTerm0(sym.owner)
        case _                                     => sym
      }
    enclosingTerm0(Symbol.spliceOwner)
  }

}

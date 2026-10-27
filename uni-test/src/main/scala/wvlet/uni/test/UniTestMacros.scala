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
package wvlet.uni.test

import wvlet.uni.design.Session
import wvlet.uni.surface.Surface

import scala.quoted.*

/**
  * Expands `test(name) { body }`. A body that is a function literal with parameters, such as
  * `(client: HttpSyncClient) => ...`, gets each argument from the test's Design session. Any other
  * body is registered as is.
  *
  * A macro rather than overloads per arity: a body of type Nothing (ending in `pending(...)` or
  * `fail(...)`) conforms to every function type, which makes such overloads ambiguous.
  */
private[test] object UniTestMacros:

  transparent inline def isFunctionLiteral(inline body: Any): Boolean =
    ${
      isFunctionLiteralImpl('body)
    }

  private def isFunctionLiteralImpl(body: Expr[Any])(using Quotes): Expr[Boolean] =
    import quotes.reflect.*
    val isLiteral = lambdaParams(body).isDefined
    val tpe       = body.asTerm.tpe.widen
    // A function value that is not a literal would be registered as a test that never calls it
    if !isLiteral && !(tpe =:= TypeRepr.of[Nothing]) && tpe.isFunctionType then
      report.errorAndAbort(
        "A test body that takes arguments must be a function literal with typed parameters, such as { (a: A) => ... }",
        body
      )
    Expr(isLiteral)

  private def lambdaParams(body: Expr[Any])(using Quotes): Option[List[quotes.reflect.ValDef]] =
    import quotes.reflect.*
    def loop(t: Term): Option[List[ValDef]] =
      t match
        case Inlined(_, Nil, inner) =>
          loop(inner)
        case Typed(inner, _) =>
          loop(inner)
        case Block(Nil, inner) =>
          loop(inner)
        case Block(List(DefDef(_, List(TermParamClause(params)), _, _)), Closure(_, _))
            if params.nonEmpty =>
          Some(params)
        case _ =>
          None
    loop(body.asTerm)

  def sessionTest(
      self: Expr[UniTest],
      name: Expr[String],
      tags: Expr[Seq[TestTag]],
      body: Expr[Any]
  )(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    val params = lambdaParams(body).getOrElse(
      report.errorAndAbort("Expected a function literal with typed parameters", body)
    )
    '{
      ${
        self
      }.registerSessionTest(
        ${
          name
        },
        ${
          tags
        }
      ) { (session: Session) =>
        ${
          val args = params.map { p =>
            p.tpt.tpe.asType match
              case '[t] =>
                '{
                  session.get[t](Surface.of[t])
                }.asTerm
          }
          // Re-own the function literal under this closure, so its local definitions stay in scope
          val fn = body.asTerm.changeOwner(Symbol.spliceOwner)
          Select.unique(fn, "apply").appliedToArgs(args).asExprOf[Any]
        }
      }
    }

  end sessionTest

end UniTestMacros

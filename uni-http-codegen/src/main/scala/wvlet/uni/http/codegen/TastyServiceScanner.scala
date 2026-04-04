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

import scala.quoted.*
import scala.tasty.inspector.*

/**
  * Scans compiled .tasty files to extract service trait metadata for code generation. Uses Scala
  * 3's TASTy Inspector API to read typed AST information including method signatures and
  * annotations.
  */
object TastyServiceScanner extends LogSupport:

  /**
    * Scan a .tasty file and extract a ServiceDef for the specified trait.
    *
    * @param tastyFilePath
    *   Path to the .tasty file
    * @param classpath
    *   Classpath entries needed to resolve types
    * @return
    *   ServiceDef extracted from the trait
    */
  def scan(tastyFilePath: String, classpath: List[String]): ServiceDef =
    val result    = collection.mutable.Buffer.empty[ServiceDef]
    val inspector = ServiceInspector(result)
    TastyInspector.inspectTastyFiles(List(tastyFilePath))(inspector)
    result
      .headOption
      .getOrElse:
        throw IllegalStateException(s"No service trait found in ${tastyFilePath}")

  /**
    * Scan multiple .tasty files.
    */
  def scanAll(tastyFilePaths: List[String], classpath: List[String]): Seq[ServiceDef] =
    val result    = collection.mutable.Buffer.empty[ServiceDef]
    val inspector = ServiceInspector(result)
    TastyInspector.inspectTastyFiles(tastyFilePaths)(inspector)
    result.toSeq

  private class ServiceInspector(result: collection.mutable.Buffer[ServiceDef]) extends Inspector:
    def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
      import quotes.reflect.*

      for tasty <- tastys do
        val tree = tasty.ast
        inspectTree(tree)

    private def inspectTree(using Quotes)(tree: quotes.reflect.Tree): Unit =
      import quotes.reflect.*

      tree match
        case PackageClause(pid, stats) =>
          stats.foreach(inspectTree)
        case cd: ClassDef if cd.symbol.flags.is(Flags.Trait) =>
          val serviceDef = extractServiceDef(cd)
          result += serviceDef
        case _ =>
        // Skip non-trait definitions

    private def extractServiceDef(using Quotes)(cd: quotes.reflect.ClassDef): ServiceDef =
      import quotes.reflect.*

      val symbol      = cd.symbol
      val fullName    = symbol.fullName
      val packageName = extractPackageName(fullName)
      val simpleName  = symbol.name

      val methods = cd
        .body
        .collect:
          case dd: DefDef if isServiceMethod(dd) =>
            extractMethodDef(dd, fullName)

      ServiceDef(packageName, simpleName, methods)

    end extractServiceDef

    private def extractPackageName(fullName: String): String =
      val lastDot = fullName.lastIndexOf('.')
      if lastDot >= 0 then
        fullName.substring(0, lastDot)
      else
        ""

    /**
      * Filter out synthetic methods, constructors, and Object/Any inherited methods
      */
    private def isServiceMethod(using Quotes)(dd: quotes.reflect.DefDef): Boolean =
      import quotes.reflect.*

      val name          = dd.name
      val symbol        = dd.symbol
      val excludedNames = Set(
        "<init>",
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
      !excludedNames.contains(name) && !symbol.flags.is(Flags.Synthetic) &&
      !symbol.flags.is(Flags.Private) && !symbol.flags.is(Flags.Protected) &&
      symbol.flags.is(Flags.Deferred) // Only abstract methods

    private def extractMethodDef(using
        Quotes
    )(dd: quotes.reflect.DefDef, serviceFullName: String): MethodDef =
      import quotes.reflect.*

      val name          = dd.name
      val params        = extractParams(dd)
      val rawReturnType = extractTypeRef(dd.returnTpt.tpe)

      // Unwrap Rx[T] to get the inner type
      val (returnType, isAsync) =
        if rawReturnType.fullName == "wvlet.uni.rx.Rx" && rawReturnType.typeArgs.nonEmpty then
          (rawReturnType.typeArgs.head, true)
        else
          (rawReturnType, false)

      // Check for @Endpoint annotation
      val endpointAnnotation = findEndpointAnnotation(dd.symbol)

      endpointAnnotation match
        case Some((httpMethod, path)) =>
          MethodDef(
            name = name,
            httpMethod = httpMethod,
            path = path,
            params = params,
            returnType = returnType,
            isRPC = false
          )
        case None =>
          // RPC method: POST to /{serviceFullName}/{methodName}
          MethodDef(
            name = name,
            httpMethod = "POST",
            path = s"/${serviceFullName}/${name}",
            params = params,
            returnType = returnType,
            isRPC = true
          )

    end extractMethodDef

    private def extractParams(using Quotes)(dd: quotes.reflect.DefDef): Seq[ParamDef] =
      import quotes.reflect.*

      dd.paramss
        .flatMap:
          case TermParamClause(params) =>
            params.map: vd =>
              val tpe   = extractTypeRef(vd.tpt.tpe)
              val isOpt = tpe.isOption
              ParamDef(
                name = vd.name,
                typeName = tpe,
                hasDefault = vd.symbol.flags.is(Flags.HasDefault),
                isOption = isOpt
              )
          case _ =>
            // Skip type parameter clauses
            Seq.empty

    private def extractTypeRef(using
        Quotes
    )(tpe: quotes.reflect.TypeRepr): wvlet.uni.http.codegen.TypeRef =
      import quotes.reflect.*

      tpe.dealias.simplified match
        case AppliedType(tycon, args) =>
          val base = extractTypeRef(tycon)
          wvlet
            .uni
            .http
            .codegen
            .TypeRef(
              fullName = base.fullName,
              shortName = base.shortName,
              typeArgs = args.map(extractTypeRef)
            )
        case tpe =>
          val fullName  = tpe.typeSymbol.fullName
          val shortName = tpe.typeSymbol.name
          // Normalize common names
          val normalizedShort =
            shortName match
              case "String" =>
                "String"
              case other =>
                other
          wvlet.uni.http.codegen.TypeRef(fullName, normalizedShort)

    private def findEndpointAnnotation(using
        Quotes
    )(symbol: quotes.reflect.Symbol): Option[(String, String)] =
      import quotes.reflect.*

      symbol
        .annotations
        .collectFirst:
          case ann if ann.tpe.typeSymbol.fullName == "wvlet.uni.http.router.Endpoint" =>
            ann match
              case Apply(_, List(methodArg, Literal(StringConstant(path)))) =>
                val httpMethod = extractHttpMethodFromArg(methodArg)
                (httpMethod, path)
              case _ =>
                ("GET", "/")

    private def extractHttpMethodFromArg(using Quotes)(arg: quotes.reflect.Term): String =
      import quotes.reflect.*

      arg match
        case Select(_, name) =>
          // e.g., HttpMethod.GET -> "GET"
          name
        case _ =>
          "GET"

  end ServiceInspector

end TastyServiceScanner

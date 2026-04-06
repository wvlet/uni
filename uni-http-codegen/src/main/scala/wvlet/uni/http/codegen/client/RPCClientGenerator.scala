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
package wvlet.uni.http.codegen.client

import wvlet.uni.http.codegen.*
import wvlet.uni.text.CodeFormatter
import wvlet.uni.text.CodeFormatter.*
import wvlet.uni.text.CodeFormatterConfig

/**
  * Generates type-safe RPC client source code from a ServiceDef. RPC clients send all requests as
  * POST with JSON body in the {"request": {...}} envelope format.
  *
  * Uses CodeFormatter's Doc tree for structured code generation, avoiding ad-hoc string escaping.
  */
object RPCClientGenerator:

  private val formatter = CodeFormatter(CodeFormatterConfig(indentWidth = 2, maxLineWidth = 100))

  /**
    * Generate complete Scala source for an RPC client object.
    */
  def generate(service: ServiceDef, config: CodegenConfig): String =
    val doc = buildDoc(service, config)
    formatter.format(doc) + "\n"

  private def buildDoc(service: ServiceDef, config: CodegenConfig): Doc =
    val targetPackage = config.resolvedTargetPackage
    val objectName    = s"${service.serviceName}Client"

    val packageDecl =
      if targetPackage.nonEmpty then
        text(s"package ${targetPackage}") / empty
      else
        empty

    val imports = lines(
      List(
        text("import wvlet.uni.http.*"),
        text("import wvlet.uni.rx.Rx"),
        text("import wvlet.uni.weaver.Weaver"),
        text("import wvlet.uni.weaver.codec.PrimitiveWeaver.given")
      )
    )

    val clients = List.concat(
      Option.when(config.clientType == ClientType.Sync || config.clientType == ClientType.Both)(
        buildSyncClient(service)
      ),
      Option.when(config.clientType == ClientType.Async || config.clientType == ClientType.Both)(
        buildAsyncClient(service)
      )
    )
    val objectBody = verticalAppend(clients, empty)

    packageDecl / imports / empty / indentedBlock(s"object ${objectName}", objectName, objectBody)

  end buildDoc

  /**
    * Build an indented Scala 3 block: header + nested body + end marker
    *
    * @param header
    *   Full header text (e.g., "object Foo", "class Bar(x: Int)")
    * @param endLabel
    *   Label for the end marker (e.g., "Foo", "Bar")
    */
  private def indentedBlock(header: String, endLabel: String, body: Doc): Doc =
    text(s"${header}:") + nest(newline / body) / empty / text(s"end ${endLabel}")

  private def buildSyncClient(service: ServiceDef): Doc =
    val methods = service.methods.map(buildSyncMethod).toList
    val body    = verticalAppend(methods, empty)
    indentedBlock("class SyncClient(client: HttpSyncClient)", "SyncClient", body)

  private def buildAsyncClient(service: ServiceDef): Doc =
    val methods = service.methods.map(buildAsyncMethod).toList
    val body    = verticalAppend(methods, empty)
    indentedBlock("class AsyncClient(client: HttpAsyncClient)", "AsyncClient", body)

  private def buildSyncMethod(method: MethodDef): Doc =
    val sig     = methodSignature(method)
    val reqBody = buildRequestBody(method)

    if method.returnType.isUnit then
      text(s"def ${sig}: Unit =") + nest(newline + reqBody / text("client.send(req)") / text("()"))
    else
      text(s"def ${sig}: ${method.returnType.render} =") +
        nest(newline + reqBody / text("val resp = client.send(req)") / decodeResponse(method))

  private def buildAsyncMethod(method: MethodDef): Doc =
    val sig     = methodSignature(method)
    val reqBody = buildRequestBody(method)

    if method.returnType.isUnit then
      text(s"def ${sig}: Rx[Unit] =") +
        nest(newline + reqBody / text("client.send(req).map(_ => ())"))
    else
      text(s"def ${sig}: Rx[${method.returnType.render}] =") +
        nest(newline + reqBody / decodeAsyncResponse(method))

  private def buildRequestBody(method: MethodDef): Doc =
    val postReq = text(s"""Request.post("${method.path}")""")
    if method.params.isEmpty then
      text("val req =") + ws + postReq / nest(text(""".withJsonContent("{\"request\":{}}")"""))
    else
      val jsonParts = method
        .params
        .map: p =>
          val weaverType = p.typeName.render
          text(s""""\\\"${p.name}\\\":" + summon[Weaver[${weaverType}]].toJson(${p.name})""")
      val seqItems = cl(jsonParts.toList*)

      text("val jsonParts = Seq(") + nest(newline + seqItems) / text(")") / text("val req =") + ws +
        postReq /
        nest(text(""".withJsonContent("{\"request\":{" + jsonParts.mkString(",") + "}}")"""))

  private def decodeResponse(method: MethodDef): Doc =
    val rt = method.returnType.render
    text(s"Weaver.of[${rt}].fromJson(") +
      nest(
        newline +
          text(
            """resp.contentAsString.getOrElse(throw IllegalStateException("Empty response body"))"""
          )
      ) / text(")")

  private def decodeAsyncResponse(method: MethodDef): Doc =
    val rt = method.returnType.render
    text("client.send(req).map { resp =>") +
      nest(
        newline + text(s"Weaver.of[${rt}].fromJson(") +
          nest(
            newline +
              text(
                """resp.contentAsString.getOrElse(throw IllegalStateException("Empty response body"))"""
              )
          ) / text(")")
      ) / text("}")

  private def methodSignature(method: MethodDef): String =
    val paramList = renderParamList(method.params)
    s"${method.name}${paramList}"

  private def renderParamList(params: Seq[ParamDef]): String =
    if params.isEmpty then
      ""
    else
      val rendered = params.map(p => s"${p.name}: ${p.typeName.render}")
      s"(${rendered.mkString(", ")})"

end RPCClientGenerator

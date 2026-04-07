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
  * Generates type-safe RPC client source code from a ServiceDef. The generated code delegates to
  * RPCClient at runtime, which uses Surface.methodsOf[T] + MethodCodec for serialization.
  *
  * Generated code shape:
  * {{{
  * object UserServiceClient:
  *   private val rpc = RPCClient.build(Surface.of[UserService], Surface.methodsOf[UserService])
  *
  *   class SyncClient(client: HttpSyncClient):
  *     def getUser(id: Long): User = rpc.callSync(client, "getUser", Seq(id))
  * }}}
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
    val apiFQCN       = service.fullName

    val packageDecl =
      if targetPackage.nonEmpty then
        text(s"package ${targetPackage}") / empty
      else
        empty

    val imports = lines(
      List(
        text("import wvlet.uni.http.*"),
        text("import wvlet.uni.http.rpc.RPCClient"),
        text("import wvlet.uni.rx.Rx"),
        text("import wvlet.uni.surface.Surface")
      )
    )

    val rpcField =
      text(s"private val rpc = RPCClient.build(") +
        nest(newline + text(s"Surface.of[${apiFQCN}],") / text(s"Surface.methodsOf[${apiFQCN}]")) /
        text(")")

    val clients = List.concat(
      Option.when(config.clientType == ClientType.Sync || config.clientType == ClientType.Both)(
        buildSyncClient(service)
      ),
      Option.when(config.clientType == ClientType.Async || config.clientType == ClientType.Both)(
        buildAsyncClient(service)
      )
    )
    val objectBody = verticalAppend(rpcField :: clients, empty)

    packageDecl / imports / empty / indentedBlock(s"object ${objectName}", objectName, objectBody)

  end buildDoc

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
    val sig      = methodSignature(method)
    val rt       = method.returnType.render
    val callArgs = renderCallArgs(method)

    if method.returnType.isUnit then
      text(s"def ${sig}: Unit =") +
        nest(newline + text(s"""rpc.callSync[Unit](client, "${method.name}", ${callArgs})"""))
    else
      text(s"def ${sig}: ${rt} =") +
        nest(newline + text(s"""rpc.callSync[${rt}](client, "${method.name}", ${callArgs})"""))

  private def buildAsyncMethod(method: MethodDef): Doc =
    val sig      = methodSignature(method)
    val rt       = method.returnType.render
    val callArgs = renderCallArgs(method)

    if method.returnType.isUnit then
      text(s"def ${sig}: Rx[Unit] =") +
        nest(newline + text(s"""rpc.callAsync[Unit](client, "${method.name}", ${callArgs})"""))
    else
      text(s"def ${sig}: Rx[${rt}] =") +
        nest(newline + text(s"""rpc.callAsync[${rt}](client, "${method.name}", ${callArgs})"""))

  private def renderCallArgs(method: MethodDef): String =
    if method.params.isEmpty then
      "Seq.empty"
    else
      val args = method.params.map(_.name).mkString(", ")
      s"Seq(${args})"

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

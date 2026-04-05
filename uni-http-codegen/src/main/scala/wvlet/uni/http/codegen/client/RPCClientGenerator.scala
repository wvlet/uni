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

/**
  * Generates type-safe RPC client source code from a ServiceDef. RPC clients send all requests as
  * POST with JSON body in the {"request": {...}} envelope format.
  */
object RPCClientGenerator:

  /**
    * Generate complete Scala source for an RPC client object.
    *
    * @param service
    *   Service definition extracted from trait
    * @param config
    *   Code generation configuration
    * @return
    *   Generated Scala source code string
    */
  def generate(service: ServiceDef, config: CodegenConfig): String =
    val targetPackage = config.resolvedTargetPackage
    val objectName    = s"${service.serviceName}Client"

    val sb = StringBuilder()

    if targetPackage.nonEmpty then
      sb.append(s"package ${targetPackage}\n\n")

    sb.append("import wvlet.uni.http.*\n")
    sb.append("import wvlet.uni.rx.Rx\n")
    sb.append("import wvlet.uni.weaver.Weaver\n")
    sb.append("\n")

    sb.append(s"object ${objectName}:\n\n")

    if config.clientType == ClientType.Sync || config.clientType == ClientType.Both then
      sb.append(generateSyncClient(service))
      sb.append("\n")

    if config.clientType == ClientType.Async || config.clientType == ClientType.Both then
      sb.append(generateAsyncClient(service))
      sb.append("\n")

    sb.append(s"end ${objectName}\n")
    sb.toString

  end generate

  private def generateSyncClient(service: ServiceDef): String =
    val sb = StringBuilder()
    sb.append("  class SyncClient(client: HttpSyncClient):\n")
    for method <- service.methods do
      sb.append(generateSyncMethod(method))
      sb.append("\n")
    sb.append("  end SyncClient\n")
    sb.toString

  private def generateAsyncClient(service: ServiceDef): String =
    val sb = StringBuilder()
    sb.append("  class AsyncClient(client: HttpAsyncClient):\n")
    for method <- service.methods do
      sb.append(generateAsyncMethod(method))
      sb.append("\n")
    sb.append("  end AsyncClient\n")
    sb.toString

  private def generateSyncMethod(method: MethodDef): String =
    val sb         = StringBuilder()
    val paramList  = renderParamList(method.params)
    val returnType = method.returnType.render

    if method.returnType.isUnit then
      sb.append(s"    def ${method.name}${paramList}: Unit =\n")
      sb.append(generateRequestBody(method))
      sb.append(s"      client.send(req)\n")
      sb.append(s"      ()\n")
    else
      sb.append(s"    def ${method.name}${paramList}: ${returnType} =\n")
      sb.append(generateRequestBody(method))
      sb.append(s"      val resp = client.send(req)\n")
      sb.append(
        s"      Weaver.of[${returnType}].fromJSONValue(resp.contentAsJson.getOrElse(throw IllegalStateException(\"Empty response body\")))\n"
      )

    sb.toString

  private def generateAsyncMethod(method: MethodDef): String =
    val sb         = StringBuilder()
    val paramList  = renderParamList(method.params)
    val returnType = method.returnType.render

    if method.returnType.isUnit then
      sb.append(s"    def ${method.name}${paramList}: Rx[Unit] =\n")
      sb.append(generateRequestBody(method))
      sb.append(s"      client.send(req).map(_ => ())\n")
    else
      sb.append(s"    def ${method.name}${paramList}: Rx[${returnType}] =\n")
      sb.append(generateRequestBody(method))
      sb.append(
        s"      client.send(req).map(resp => Weaver.of[${returnType}].fromJSONValue(resp.contentAsJson.getOrElse(throw IllegalStateException(\"Empty response body\"))))\n"
      )

    sb.toString

  private def generateRequestBody(method: MethodDef): String =
    val sb = StringBuilder()

    if method.params.isEmpty then
      sb.append(s"""      val req = Request.post("${method.path}")\n""")
      sb.append(s"""        .withJsonContent("""")
      sb.append("""{"request":{}}""")
      sb.append(s"""")\n""")
    else
      sb.append(s"      val jsonParts = Seq(\n")
      val parts = method
        .params
        .map: p =>
          val weaverType = p.typeName.render
          s"""        "\"${p.name}\":" + Weaver.of[${weaverType}].toJson(${p.name})"""
      sb.append(parts.mkString(",\n"))
      sb.append("\n      )\n")
      sb.append(s"""      val req = Request.post("${method.path}")\n""")
      sb.append(
        s"""        .withJsonContent("{\"request\":{" + jsonParts.mkString(",") + "}}")\n"""
      )

    sb.toString

  private def renderParamList(params: Seq[ParamDef]): String =
    if params.isEmpty then
      ""
    else
      val rendered = params.map: p =>
        s"${p.name}: ${p.typeName.render}"
      s"(${rendered.mkString(", ")})"

end RPCClientGenerator

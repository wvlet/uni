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
package wvlet.uni.http.rpc

import wvlet.uni.http.*
import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.JSONObject
import wvlet.uni.rx.Rx
import wvlet.uni.surface.{MethodSurface, Surface}
import wvlet.uni.weaver.Weaver

/**
  * RPC client that dispatches method calls via HTTP using the same path/envelope convention as
  * RPCRouter. This is the client-side mirror of RPCRouter.
  *
  * Usage in generated code:
  * {{{
  * object UserServiceClient:
  *   private val rpcClient = RPCClient.build(Surface.of[UserService], Surface.methodsOf[UserService])
  *
  *   class SyncClient(client: HttpSyncClient):
  *     def getUser(id: Long): User =
  *       rpcClient.callSync(client, "getUser", Seq(id))
  * }}}
  */
class RPCClient(val serviceName: String, val codecs: Map[String, MethodCodec]):

  /**
    * Make a synchronous RPC call.
    *
    * @param client
    *   HTTP client to send the request
    * @param methodName
    *   Name of the RPC method
    * @param args
    *   Method arguments in order
    * @return
    *   Decoded return value
    */
  def callSync[A](client: HttpSyncClient, methodName: String, args: Seq[Any]): A =
    val codec = lookupCodec(methodName)
    val req   = buildRequest(methodName, codec, args)
    val resp  = client.send(req)
    decodeResponse(codec, resp).asInstanceOf[A]

  /**
    * Make an asynchronous RPC call.
    */
  def callAsync[A](client: HttpAsyncClient, methodName: String, args: Seq[Any]): Rx[A] =
    val codec = lookupCodec(methodName)
    val req   = buildRequest(methodName, codec, args)
    client.send(req).map(resp => decodeResponse(codec, resp).asInstanceOf[A])

  private def lookupCodec(methodName: String): MethodCodec = codecs.getOrElse(
    methodName,
    throw IllegalArgumentException(
      s"Unknown RPC method: ${serviceName}.${methodName}. Available: ${codecs.keys.mkString(", ")}"
    )
  )

  private def buildRequest(methodName: String, codec: MethodCodec, args: Seq[Any]): Request =
    val paramFields = codec
      .method
      .args
      .zip(args)
      .map { (param, value) =>
        val weaver = Weaver.fromSurface(param.surface)
        param.name -> JSON.parse(weaver.asInstanceOf[Weaver[Any]].toJson(value))
      }
    val requestBody = JSONObject(Seq("request" -> JSONObject(paramFields)))
    Request.post(s"/${serviceName}/${methodName}").withJsonContent(requestBody)

  private def decodeResponse(codec: MethodCodec, resp: HttpResponse): Any =
    if codec.method.returnType.rawType == classOf[Unit] then
      ()
    else
      val json = resp.contentAsString.getOrElse(throw IllegalStateException("Empty response body"))
      codec.returnWeaver.asInstanceOf[Weaver[Any]].fromJson(json)

end RPCClient

object RPCClient:

  /**
    * Build an RPCClient from Surface metadata. Call this with inline Surface.methodsOf[T].
    */
  def build(serviceSurface: Surface, methods: Seq[MethodSurface]): RPCClient =
    val excludedOwners: Set[Class[?]] = Set(classOf[Object], classOf[Any])

    val filteredMethods = methods.filter(m =>
      m.isPublic && !excludedOwners.contains(m.owner.rawType)
    )

    val codecs =
      filteredMethods
        .map { m =>
          val paramWeavers     = m.args.map(p => Weaver.fromSurface(p.surface)).toIndexedSeq
          val actualReturnType =
            if classOf[Rx[?]].isAssignableFrom(m.returnType.rawType) &&
              m.returnType.typeArgs.nonEmpty
            then
              m.returnType.typeArgs.head
            else
              m.returnType
          val returnWeaver = Weaver.fromSurface(actualReturnType)
          m.name -> MethodCodec(m, paramWeavers, returnWeaver)
        }
        .toMap

    RPCClient(serviceSurface.fullName, codecs)

end RPCClient

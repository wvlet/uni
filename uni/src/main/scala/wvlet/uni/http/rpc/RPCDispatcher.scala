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

import wvlet.uni.http.HttpHeader
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx

/**
  * Transport-neutral dispatcher for RPC requests.
  *
  * Given one or more [[RPCRouter]]s, this resolves an incoming [[Request]] to a route (`POST
  * /{serviceName}/{methodName}`), decodes the JSON envelope, invokes the method (awaiting `Rx[T]`
  * results), and produces a [[Response]]. The wire format is identical to what the [[RPCClient]]
  * expects, so any transport that can deliver a `Request` and return a `Response` works: HTTP
  * (netty's `RPCHandler`), Electron IPC (`wvlet.uni.electron.ElectronRPCServer`), etc.
  *
  * @param routers
  *   service routers to dispatch across. Paths are unique per service, so multiple services can be
  *   served through a single dispatcher.
  */
class RPCDispatcher(routers: Seq[RPCRouter]) extends LogSupport:

  // Pre-build a path -> (instance, route) lookup for O(1) dispatch across all routers.
  private val routeMap: Map[String, (Any, RPCRoute)] =
    routers.flatMap(router => router.routes.map(r => r.path -> (router.instance, r))).toMap

  /**
    * Resolve and invoke the RPC method for the given request. Always completes with a [[Response]];
    * RPC failures are encoded as error responses (matching the over-HTTP behavior) rather than
    * failing the returned `Rx`.
    */
  def dispatch(request: Request): Rx[Response] =
    // Only POST is allowed for RPC
    if request.method != HttpMethod.POST then
      Rx.single(
        RPCStatus
          .INVALID_REQUEST_U1
          .newException(s"RPC requires POST method, got: ${request.method}")
          .toResponse
      )
    else
      routeMap.get(request.path) match
        case Some((instance, route)) =>
          handle(instance, route, request)
        case None =>
          debug(s"RPC method not found: ${request.path}")
          Rx.single(
            RPCStatus.NOT_FOUND_U5.newException(s"RPC method not found: ${request.path}").toResponse
          )

  private def handle(instance: Any, route: RPCRoute, request: Request): Rx[Response] =
    try
      // Decode parameters from the request body
      val json = request.content.asString.getOrElse("")
      val args = route.codec.decodeParams(json)

      // Invoke the method
      val result = route.codec.method.call(instance, args*)

      // Handle result - may be Rx for async methods
      result match
        case rx: Rx[?] =>
          rx.map(v => successResponse(v, route))
            .recover { case e =>
              errorResponse(e)
            }
        case value =>
          Rx.single(successResponse(value, route))
    catch
      case e: RPCException =>
        debug(s"RPC error: ${e.status} - ${e.message}")
        Rx.single(e.toResponse)
      case e: Exception =>
        warn(s"Unexpected error in RPC handler: ${e.getMessage}", e)
        Rx.single(errorResponse(e))

  private def successResponse(value: Any, route: RPCRoute): Response =
    val json = route.codec.encodeResult(value)
    Response
      .ok
      .addHeader(HttpHeader.XRPCStatus, RPCStatus.SUCCESS_S0.code.toString)
      .withJsonContent(json)

  private def errorResponse(e: Throwable): Response =
    e match
      case rpc: RPCException =>
        rpc.toResponse
      case _ =>
        RPCStatus.INTERNAL_ERROR_I0.newException(e.getMessage, e).toResponse

end RPCDispatcher

object RPCDispatcher:
  /**
    * Create a dispatcher serving the given routers.
    */
  def apply(routers: RPCRouter*): RPCDispatcher = new RPCDispatcher(routers.toSeq)

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
package wvlet.uni.http.netty

import wvlet.uni.http.HttpHeader
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.http.rpc.RPCException
import wvlet.uni.http.rpc.RPCRoute
import wvlet.uni.http.rpc.RPCRouter
import wvlet.uni.http.rpc.RPCStatus
import wvlet.uni.log.LogSupport
import wvlet.uni.rx.Rx

/**
  * HTTP handler for RPC requests.
  *
  * Handles requests to RPC endpoints defined by RPCRouter:
  *   - Only POST method is allowed
  *   - Path format: /{serviceName}/{methodName}
  *   - Request body: {"request": {...params...}}
  *   - Response: JSON result or RPCException
  *
  * @param router
  *   The RPC router containing route definitions
  */
class RPCHandler(router: RPCRouter) extends RxHttpHandler with LogSupport:

  // Pre-build route lookup map for O(1) access
  private val routeMap: Map[String, RPCRoute] = router.routes.map(r => r.path -> r).toMap

  override def handle(request: Request): Rx[Response] =
    // Only POST method is allowed for RPC
    if request.method != HttpMethod.POST then
      Rx.single(
        RPCStatus
          .INVALID_REQUEST_U1
          .newException(s"RPC requires POST method, got: ${request.method}")
          .toResponse
      )
    else
      routeMap.get(request.path) match
        case Some(route) =>
          handleRPC(request, route)
        case None =>
          debug(s"RPC method not found: ${request.path}")
          Rx.single(
            RPCStatus.NOT_FOUND_U5.newException(s"RPC method not found: ${request.path}").toResponse
          )

  private def handleRPC(request: Request, route: RPCRoute): Rx[Response] =
    try
      // Decode parameters from request body
      val json = request.content.asString.getOrElse("")
      val args = route.codec.decodeParams(json)

      // Invoke the method
      val result = route.codec.method.call(router.instance, args*)

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

end RPCHandler

object RPCHandler:

  /**
    * Create an RPC handler from a router.
    */
  def apply(router: RPCRouter): RPCHandler = new RPCHandler(router)

end RPCHandler

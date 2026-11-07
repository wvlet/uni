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

import wvlet.uni.http.ContentType
import wvlet.uni.http.HttpHeader
import wvlet.uni.http.HttpMethod
import wvlet.uni.http.Request
import wvlet.uni.http.Response
import wvlet.uni.http.ServerSentEvent
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
  * A request that accepts `text/event-stream` (sent by [[RPCAsyncClient.stream]]) gets every value
  * of the method's `Rx` as a Server-Sent Event, instead of only the first one. Each event's data is
  * the JSON of one value. A failure after the stream started is sent as an
  * [[RPCDispatcher.ErrorEvent]] event whose data is the RPC error message JSON, and ends the
  * stream. Failures before the method returns are sent as ordinary error responses.
  *
  * @param routers
  *   service routers to dispatch across. Paths are unique per service, so multiple services can be
  *   served through a single dispatcher.
  * @param detailedErrors
  *   include stack traces of [[RPCException]]s, and the messages and stack traces of unexpected
  *   exceptions, in error responses. Off by default because such details leak internals; they are
  *   always logged on the server.
  */
class RPCDispatcher(routers: Seq[RPCRouter], detailedErrors: Boolean = false) extends LogSupport:

  /**
    * Include stack traces, and the messages of unexpected exceptions, in error responses (for
    * development only).
    */
  def withDetailedErrors(enabled: Boolean): RPCDispatcher = new RPCDispatcher(routers, enabled)

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
          .toResponse(detailedErrors)
      )
    else
      routeMap.get(request.path) match
        case Some((instance, route)) =>
          handle(instance, route, request)
        case None =>
          debug(s"RPC method not found: ${request.path}")
          Rx.single(
            RPCStatus
              .NOT_FOUND_U5
              .newException(s"RPC method not found: ${request.path}")
              .toResponse(detailedErrors)
          )

  private def handle(instance: Any, route: RPCRoute, request: Request): Rx[Response] =
    try
      // Decode parameters from the request body
      val json = request.content.asString.getOrElse("")
      val args = route.codec.decodeParams(json, Some(instance))

      // Invoke the method
      val result = route.codec.method.call(instance, args*)

      // Handle result - may be Rx for async methods
      result match
        case rx: Rx[?] if RPCDispatcher.acceptsEventStream(request) =>
          Rx.single(streamResponse(rx, route))
        case value if RPCDispatcher.acceptsEventStream(request) =>
          Rx.single(streamResponse(Rx.single(value), route))
        case rx: Rx[?] =>
          rx.map(v => successResponse(v, route))
            .recover {
              case e: RPCException =>
                e.toResponse(detailedErrors)
              case e =>
                warn(s"Unexpected error in RPC handler: ${e.getMessage}", e)
                errorResponse(e)
            }
        case value =>
          Rx.single(successResponse(value, route))
    catch
      case e: RPCException =>
        debug(s"RPC error: ${e.status} - ${e.message}")
        Rx.single(e.toResponse(detailedErrors))
      case e: Exception =>
        warn(s"Unexpected error in RPC handler: ${e.getMessage}", e)
        Rx.single(errorResponse(e))

  private def successResponse(value: Any, route: RPCRoute): Response =
    val json = route.codec.encodeResult(value)
    Response
      .ok
      .addHeader(HttpHeader.XRPCStatus, RPCStatus.SUCCESS_S0.code.toString)
      .withJsonContent(json)

  private def streamResponse(rx: Rx[?], route: RPCRoute): Response =
    val events: Rx[ServerSentEvent] = rx
      .map(v => ServerSentEvent.data(route.codec.encodeResult(v)))
      .recoverWith { case e: Throwable =>
        // recoverWith, not recover: the error event must also complete the stream
        Rx.single(ServerSentEvent.data(errorMessageJson(e)).withEvent(RPCDispatcher.ErrorEvent))
      }
    Response
      .eventStream(events)
      .addHeader(HttpHeader.XRPCStatus, RPCStatus.SUCCESS_S0.code.toString)

  /** The JSON body [[errorResponse]] would send for `e`, for an error event in a stream. */
  private def errorMessageJson(e: Throwable): String =
    if !e.isInstanceOf[RPCException] then
      warn(s"Unexpected error in RPC stream: ${e.getMessage}", e)
    errorResponse(e).contentAsString.getOrElse("")

  private def errorResponse(e: Throwable): Response =
    e match
      case rpc: RPCException =>
        rpc.toResponse(detailedErrors)
      case _ =>
        if detailedErrors then
          val wrapped = RPCStatus.INTERNAL_ERROR_I0.newException(String.valueOf(e.getMessage), e)
          // Report where the handler failed, not where it was wrapped, as the cause is not sent
          wrapped.setStackTrace(e.getStackTrace)
          wrapped.toResponse(detailedErrors)
        else
          // Hide the stack trace as well as the message, as both can leak internals
          RPCStatus.INTERNAL_ERROR_I0.newException("Internal error", e).noStackTrace.toResponse

end RPCDispatcher

object RPCDispatcher:
  /**
    * Create a dispatcher serving the given routers.
    */
  def apply(routers: RPCRouter*): RPCDispatcher = new RPCDispatcher(routers.toSeq)

  /** The SSE event type of a failure in a streaming RPC response. */
  val ErrorEvent: String = "rpc-error"

  /**
    * Whether the caller asked for a streaming response: `text/event-stream` is the first media type
    * it accepts. A client that lists it after another type (`application/json, text/event-stream`)
    * keeps getting the unary JSON answer, as before streaming RPC existed.
    */
  def acceptsEventStream(request: Request): Boolean = request
    .header(HttpHeader.Accept)
    .exists(
      _.split(',')
        .headOption
        .exists(_.split(';').head.trim.equalsIgnoreCase(ContentType.TextEventStream.value))
    )

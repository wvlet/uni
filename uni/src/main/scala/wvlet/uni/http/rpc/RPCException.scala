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

import wvlet.uni.http.{HttpHeader, Response}
import wvlet.uni.json.JSON
import wvlet.uni.msgpack.spi.MsgPack
import wvlet.uni.weaver.{Weaver, WeaverConfig}

import scala.util.Try

/**
  * RPC exception for reporting errors across RPC boundaries.
  *
  * Create using: `RPCStatus.INVALID_REQUEST_U1.newException("error message")`
  */
case class RPCException(
    status: RPCStatus = RPCStatus.INTERNAL_ERROR_I0,
    message: String = "",
    cause: Option[Throwable] = None,
    appErrorCode: Option[Int] = None,
    private val includeStackTrace: Option[Boolean] = None
) extends Exception(s"[${status}] ${message}", cause.orNull):

  /**
    * Suppress stack trace in the RPC error response, even when the server runs with
    * `detailedErrors` enabled.
    */
  def noStackTrace: RPCException = copy(includeStackTrace = Some(false))

  /**
    * Whether [[toMessage]], [[toJson]], [[toMsgPack]], and [[toResponse]] include the stack trace.
    * Off unless `includeStackTrace = Some(true)` is set, as stack frames (class names, file names,
    * line numbers) reveal server internals to clients. An `RPCDispatcher` created with
    * `withDetailedErrors(true)` also includes them when the status allows it.
    */
  def shouldReportStackTrace: Boolean = includeStackTrace.getOrElse(false)

  /**
    * Convert to serializable error message
    */
  def toMessage: RPCErrorMessage = toMessage(shouldReportStackTrace)

  private def toMessage(reportStackTrace: Boolean): RPCErrorMessage =
    val stackTrace =
      if reportStackTrace then
        Some(getStackTrace.toSeq.map(RPCStackTraceElement.fromJava))
      else
        None
    RPCErrorMessage(
      code = status.code,
      codeName = status.name,
      message = message,
      stackTrace = stackTrace,
      appErrorCode = appErrorCode
    )

  /**
    * Serialize to JSON
    */
  def toJson: String = toMessage.toJson

  /**
    * Serialize to MessagePack
    */
  def toMsgPack: MsgPack = toMessage.toMsgPack

  /**
    * Convert to HTTP response with RPC status header
    */
  def toResponse: Response = toResponse(toMessage)

  /**
    * Convert to HTTP response for a server. With `detailedErrors`, the stack trace is included
    * unless the status hides it ([[RPCStatus.shouldReportStackTrace]]) or `includeStackTrace` is
    * set explicitly.
    */
  private[rpc] def toResponse(detailedErrors: Boolean): Response = toResponse(
    toMessage(includeStackTrace.getOrElse(detailedErrors && status.shouldReportStackTrace))
  )

  private def toResponse(m: RPCErrorMessage): Response = Response(status.httpStatus)
    .addHeader(HttpHeader.XRPCStatus, status.code.toString)
    .withJsonContent(m.toJson)

end RPCException

/**
  * A stack frame reported in [[RPCErrorMessage]]. The field names match Airframe's
  * `GenericStackTraceElement`, so error bodies are wire-compatible with Airframe RPC.
  */
case class RPCStackTraceElement(
    className: String,
    methodName: String,
    fileName: Option[String] = None,
    lineNumber: Int = -1
):
  def toJava: StackTraceElement = StackTraceElement(
    className,
    methodName,
    fileName.orNull,
    lineNumber
  )

object RPCStackTraceElement:
  def fromJava(e: StackTraceElement): RPCStackTraceElement = RPCStackTraceElement(
    className = e.getClassName,
    methodName = e.getMethodName,
    fileName = Option(e.getFileName),
    lineNumber = e.getLineNumber
  )

/**
  * Serializable RPC error message model
  *
  * @param stackTrace
  *   the server-side stack trace, present only when the server opted in (see
  *   [[RPCException.shouldReportStackTrace]]). Clients restore it when present.
  */
case class RPCErrorMessage(
    code: Int = RPCStatus.UNKNOWN_I1.code,
    codeName: String = RPCStatus.UNKNOWN_I1.name,
    message: String = "",
    stackTrace: Option[Seq[RPCStackTraceElement]] = None,
    appErrorCode: Option[Int] = None
):
  def toJson: String     = RPCErrorMessage.weaver.toJson(this, RPCErrorMessage.weaverConfig)
  def toMsgPack: MsgPack = RPCErrorMessage.weaver.weave(this, RPCErrorMessage.weaverConfig)

object RPCErrorMessage:
  given weaver: Weaver[RPCErrorMessage] = Weaver.derived[RPCErrorMessage]

  // Omit unset optional fields (e.g., stackTrace) instead of writing nulls, as Airframe does
  private val weaverConfig = WeaverConfig().withOmitNoneFields(true)

  def fromJson(json: String): RPCErrorMessage        = weaver.fromJson(json)
  def fromMsgPack(msgpack: MsgPack): RPCErrorMessage = weaver.unweave(msgpack)

end RPCErrorMessage

object RPCException:

  private def fromRPCErrorMessage(m: RPCErrorMessage): RPCException =
    val status =
      try
        RPCStatus.ofCode(m.code)
      catch
        case _: IllegalArgumentException =>
          RPCStatus.UNKNOWN_I1
    val ex = RPCException(
      status = status,
      message = m.message,
      cause = None,
      appErrorCode = m.appErrorCode
    )
    // Recover the server-side stack trace
    m.stackTrace.foreach(frames => ex.setStackTrace(frames.map(_.toJava).toArray))
    ex

  def fromJson(json: String): RPCException =
    val m = RPCErrorMessage.fromJson(json)
    fromRPCErrorMessage(m)

  def fromMsgPack(msgpack: MsgPack): RPCException =
    val m = RPCErrorMessage.fromMsgPack(msgpack)
    fromRPCErrorMessage(m)

  /**
    * Extract RPCException from HTTP response
    */
  def fromResponse(response: Response): RPCException =
    val rpcStatusOpt = response.header(HttpHeader.XRPCStatus).flatMap(x => Try(x.toInt).toOption)
    def safeOfCode(code: Int): RPCStatus =
      try
        RPCStatus.ofCode(code)
      catch
        case _: IllegalArgumentException =>
          RPCStatus.UNKNOWN_I1

    rpcStatusOpt match
      case Some(rpcStatusCode) =>
        try
          val contentOpt = response.contentAsString
          if contentOpt.forall(_.isBlank) then
            val status = safeOfCode(rpcStatusCode)
            status.newException(status.name)
          else
            RPCException.fromJson(contentOpt.get)
        catch
          case e: Throwable =>
            safeOfCode(rpcStatusCode).newException(
              s"Failed to parse RPC error details: ${e.getMessage}",
              e
            )
      case None =>
        // No RPC status header - derive from HTTP status
        val rpcStatus = RPCStatus.fromHttpStatus(response.status)
        rpcStatus.newException(response.contentAsString.getOrElse(s"RPC error: ${response.status}"))

end RPCException

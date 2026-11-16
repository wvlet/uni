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
package wvlet.uni.http

import wvlet.uni.json.JSON
import wvlet.uni.msgpack.spi.MsgPack
import wvlet.uni.weaver.{Weaver, WeaverConfig}

import scala.util.Try

/**
  * Exception for reporting a [[ServiceStatus]] to the caller across a service boundary.
  *
  * Create using: `ServiceStatus.INVALID_REQUEST_U1.newException("error message")`
  */
case class ServiceException(
    status: ServiceStatus = ServiceStatus.INTERNAL_ERROR_I0,
    message: String = "",
    cause: Option[Throwable] = None,
    appErrorCode: Option[Int] = None,
    private val includeStackTrace: Option[Boolean] = None
) extends Exception(s"[${status}] ${message}", cause.orNull):

  /**
    * Suppress stack trace in the RPC error response, even when the server runs with
    * `detailedErrors` enabled.
    */
  def noStackTrace: ServiceException = copy(includeStackTrace = Some(false))

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
  def toMessage: ServiceErrorMessage = toMessage(shouldReportStackTrace)

  private def toMessage(reportStackTrace: Boolean): ServiceErrorMessage =
    val stackTrace =
      if reportStackTrace then
        Some(getStackTrace.toSeq.map(ServiceStackTraceElement.fromJava))
      else
        None
    ServiceErrorMessage(
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
    * unless the status hides it ([[ServiceStatus.shouldReportStackTrace]]) or `includeStackTrace`
    * is set explicitly.
    */
  private[http] def toResponse(detailedErrors: Boolean): Response = toResponse(
    toMessage(includeStackTrace.getOrElse(detailedErrors && status.shouldReportStackTrace))
  )

  private def toResponse(m: ServiceErrorMessage): Response = Response(status.httpStatus)
    .addHeader(HttpHeader.XRPCStatus, status.code.toString)
    .withJsonContent(m.toJson)

end ServiceException

/**
  * A stack frame reported in [[ServiceErrorMessage]]. The field names match Airframe's
  * `GenericStackTraceElement`, so error bodies are wire-compatible with Airframe RPC.
  */
case class ServiceStackTraceElement(
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

object ServiceStackTraceElement:
  def fromJava(e: StackTraceElement): ServiceStackTraceElement = ServiceStackTraceElement(
    className = e.getClassName,
    methodName = e.getMethodName,
    fileName = Option(e.getFileName),
    lineNumber = e.getLineNumber
  )

/**
  * Serializable error message model (wire-compatible with Airframe RPC)
  *
  * @param stackTrace
  *   the server-side stack trace, present only when the server opted in (see
  *   [[ServiceException.shouldReportStackTrace]]). Clients restore it when present.
  */
case class ServiceErrorMessage(
    code: Int = ServiceStatus.UNKNOWN_I1.code,
    codeName: String = ServiceStatus.UNKNOWN_I1.name,
    message: String = "",
    stackTrace: Option[Seq[ServiceStackTraceElement]] = None,
    appErrorCode: Option[Int] = None
):
  def toJson: String     = ServiceErrorMessage.weaver.toJson(this, ServiceErrorMessage.weaverConfig)
  def toMsgPack: MsgPack = ServiceErrorMessage.weaver.weave(this, ServiceErrorMessage.weaverConfig)

object ServiceErrorMessage:
  given weaver: Weaver[ServiceErrorMessage] = Weaver.derived[ServiceErrorMessage]

  // Omit unset optional fields (e.g., stackTrace) instead of writing nulls, as Airframe does
  private val weaverConfig = WeaverConfig().withOmitNoneFields(true)

  def fromJson(json: String): ServiceErrorMessage        = weaver.fromJson(json)
  def fromMsgPack(msgpack: MsgPack): ServiceErrorMessage = weaver.unweave(msgpack)

end ServiceErrorMessage

object ServiceException:

  private def fromErrorMessage(m: ServiceErrorMessage): ServiceException =
    val status =
      try
        ServiceStatus.ofCode(m.code)
      catch
        case _: IllegalArgumentException =>
          ServiceStatus.UNKNOWN_I1
    val ex = ServiceException(
      status = status,
      message = m.message,
      cause = None,
      appErrorCode = m.appErrorCode
    )
    // Recover the server-side stack trace
    m.stackTrace.foreach(frames => ex.setStackTrace(frames.map(_.toJava).toArray))
    ex

  def fromJson(json: String): ServiceException =
    val m = ServiceErrorMessage.fromJson(json)
    fromErrorMessage(m)

  def fromMsgPack(msgpack: MsgPack): ServiceException =
    val m = ServiceErrorMessage.fromMsgPack(msgpack)
    fromErrorMessage(m)

  /**
    * Extract ServiceException from HTTP response
    */
  def fromResponse(response: Response): ServiceException =
    val rpcStatusOpt = response.header(HttpHeader.XRPCStatus).flatMap(x => Try(x.toInt).toOption)
    def safeOfCode(code: Int): ServiceStatus =
      try
        ServiceStatus.ofCode(code)
      catch
        case _: IllegalArgumentException =>
          ServiceStatus.UNKNOWN_I1

    rpcStatusOpt match
      case Some(rpcStatusCode) =>
        try
          val contentOpt = response.contentAsString
          if contentOpt.forall(_.isBlank) then
            val status = safeOfCode(rpcStatusCode)
            status.newException(status.name)
          else
            ServiceException.fromJson(contentOpt.get)
        catch
          case e: Throwable =>
            safeOfCode(rpcStatusCode).newException(
              s"Failed to parse RPC error details: ${e.getMessage}",
              e
            )
      case None =>
        // No RPC status header - derive from HTTP status
        val rpcStatus = ServiceStatus.fromHttpStatus(response.status)
        rpcStatus.newException(response.contentAsString.getOrElse(s"RPC error: ${response.status}"))

end ServiceException

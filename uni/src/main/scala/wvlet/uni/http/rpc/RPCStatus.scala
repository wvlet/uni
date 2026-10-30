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

import wvlet.uni.http.HttpStatus

/**
  * RPC status type classification.
  *
  * Status types categorize RPC errors into groups that indicate whether the client should retry:
  *   - SUCCESS: Request completed successfully
  *   - USER_ERROR: Client error, not retryable (maps to 4xx HTTP)
  *   - INTERNAL_ERROR: Server error, generally retryable (maps to 5xx HTTP)
  *   - RESOURCE_EXHAUSTED: Resource limits exceeded, retry after backoff (maps to 429 HTTP)
  */
enum RPCStatusType(val prefix: String, val minCode: Int, val maxCode: Int):

  /** Successful responses */
  case SUCCESS extends RPCStatusType("S", 0, 1000)

  /** User/client errors - not retryable in general */
  case USER_ERROR extends RPCStatusType("U", 1000, 2000)

  /** Server internal errors - generally retryable */
  case INTERNAL_ERROR extends RPCStatusType("I", 2000, 3000)

  /** Resource exhausted or quota exceeded - retry after backoff */
  case RESOURCE_EXHAUSTED extends RPCStatusType("R", 3000, 4000)

  def codeRange: (Int, Int) = (minCode, maxCode)

  def isValidCode(code: Int): Boolean = minCode <= code && code < maxCode

  def isValidHttpStatus(httpStatus: HttpStatus): Boolean =
    this match
      case SUCCESS =>
        httpStatus.isSuccessful
      case USER_ERROR =>
        httpStatus.isClientError
      case INTERNAL_ERROR =>
        httpStatus.isServerError
      case RESOURCE_EXHAUSTED =>
        httpStatus == HttpStatus.TooManyRequests_429

end RPCStatusType

object RPCStatusType:

  def ofPrefix(prefix: Char): RPCStatusType =
    val p = prefix.toString
    RPCStatusType
      .values
      .find(_.prefix == p)
      .getOrElse(throw IllegalArgumentException(s"Unknown RPCStatusType prefix: ${prefix}"))

  def unapply(s: String): Option[RPCStatusType] =
    val name = s.toUpperCase
    RPCStatusType.values.find(_.toString == name)

end RPCStatusType

import RPCStatusType.*

/**
  * Standard RPC status codes for generic RPC service implementation.
  *
  * Status naming convention: {DESCRIPTION}_{TYPE}{NUMBER}
  *   - TYPE: S (success), U (user error), I (internal error), R (resource exhausted)
  *   - NUMBER: Sequential number within the type category
  *
  * Examples: SUCCESS_S0, INVALID_REQUEST_U1, INTERNAL_ERROR_I0, RESOURCE_EXHAUSTED_R0
  */
enum RPCStatus(val statusType: RPCStatusType, val httpStatus: HttpStatus):

  // ============ Success ============
  case SUCCESS_S0 extends RPCStatus(SUCCESS, HttpStatus.Ok_200)

  // ============ User Errors (non-retryable, 4xx) ============

  /** Generic user error */
  case USER_ERROR_U0 extends RPCStatus(USER_ERROR, HttpStatus.BadRequest_400)

  /** Invalid RPC request - user should not retry */
  case INVALID_REQUEST_U1 extends RPCStatus(USER_ERROR, HttpStatus.BadRequest_400)

  /** RPC request arguments have invalid values */
  case INVALID_ARGUMENT_U2 extends RPCStatus(USER_ERROR, HttpStatus.BadRequest_400)

  /** Syntax error in an RPC argument */
  case SYNTAX_ERROR_U3 extends RPCStatus(USER_ERROR, HttpStatus.BadRequest_400)

  /** Invalid range data in request argument */
  case OUT_OF_RANGE_U4 extends RPCStatus(USER_ERROR, HttpStatus.BadRequest_400)

  /** Requested resource or RPC method not found */
  case NOT_FOUND_U5 extends RPCStatus(USER_ERROR, HttpStatus.NotFound_404)

  /** Resource creation failed - already exists */
  case ALREADY_EXISTS_U6 extends RPCStatus(USER_ERROR, HttpStatus.Conflict_409)

  /** Requested RPC method not supported */
  case NOT_SUPPORTED_U7 extends RPCStatus(USER_ERROR, HttpStatus.MethodNotAllowed_405)

  /** Requested RPC method not implemented */
  case UNIMPLEMENTED_U8 extends RPCStatus(USER_ERROR, HttpStatus.NotImplemented_501)

  /** Precondition not met - client should not retry until state is fixed */
  case UNEXPECTED_STATE_U9 extends RPCStatus(USER_ERROR, HttpStatus.PreconditionFailed_412)

  /** Inconsistent state - client should not retry until state is fixed */
  case INCONSISTENT_STATE_U10 extends RPCStatus(USER_ERROR, HttpStatus.PreconditionFailed_412)

  /** Request cancelled by client */
  case CANCELLED_U11 extends RPCStatus(USER_ERROR, HttpStatus.BadRequest_400)

  /** Request aborted (deadlock, transaction conflict) - retry at higher level */
  case ABORTED_U12 extends RPCStatus(USER_ERROR, HttpStatus.Conflict_409)

  /** User not authenticated */
  case UNAUTHENTICATED_U13 extends RPCStatus(USER_ERROR, HttpStatus.Unauthorized_401)

  /** User lacks permission to access resource */
  case PERMISSION_DENIED_U14 extends RPCStatus(USER_ERROR, HttpStatus.Forbidden_403)

  // ============ Internal Errors (retryable, 5xx) ============

  /** Internal failure - user can retry */
  case INTERNAL_ERROR_I0 extends RPCStatus(INTERNAL_ERROR, HttpStatus.InternalServerError_500)

  /** Unknown internal error */
  case UNKNOWN_I1 extends RPCStatus(INTERNAL_ERROR, HttpStatus.InternalServerError_500)

  /** Service unavailable */
  case UNAVAILABLE_I2 extends RPCStatus(INTERNAL_ERROR, HttpStatus.ServiceUnavailable_503)

  /** Service did not respond in time */
  case TIMEOUT_I3 extends RPCStatus(INTERNAL_ERROR, HttpStatus.GatewayTimeout_504)

  /** Request cannot be processed within deadline - client may retry */
  case DEADLINE_EXCEEDED_I4 extends RPCStatus(INTERNAL_ERROR, HttpStatus.GatewayTimeout_504)

  /** Request interrupted at service */
  case INTERRUPTED_I5 extends RPCStatus(INTERNAL_ERROR, HttpStatus.InternalServerError_500)

  /** Service starting up - retry after delay */
  case SERVICE_STARTING_UP_I6 extends RPCStatus(INTERNAL_ERROR, HttpStatus.ServiceUnavailable_503)

  /** Service shutting down */
  case SERVICE_SHUTTING_DOWN_I7 extends RPCStatus(INTERNAL_ERROR, HttpStatus.ServiceUnavailable_503)

  /** Data loss or corruption */
  case DATA_LOSS_I8 extends RPCStatus(INTERNAL_ERROR, HttpStatus.InternalServerError_500)

  // ============ Resource Exhausted (429) ============

  /** Resource for completing request is insufficient */
  case RESOURCE_EXHAUSTED_R0 extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Service experiencing insufficient memory */
  case OUT_OF_MEMORY_R1 extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Too many requests - retry after delay */
  case EXCEEDED_RATE_LIMIT_R2 extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** CPU usage limit reached */
  case EXCEEDED_CPU_LIMIT_R3 extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Memory usage limit reached */
  case EXCEEDED_MEMORY_LIMIT_R4
      extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Running time limit reached */
  case EXCEEDED_TIME_LIMIT_R5 extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Data size limit reached */
  case EXCEEDED_DATA_SIZE_LIMIT_R6
      extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Storage size limit reached */
  case EXCEEDED_STORAGE_LIMIT_R7
      extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /** Budget exhausted */
  case EXCEEDED_BUDGET_R8 extends RPCStatus(RESOURCE_EXHAUSTED, HttpStatus.TooManyRequests_429)

  /**
    * Integer-based error code derived from the name
    */
  lazy val code: Int = RPCStatus.extractErrorCode(toString)

  /**
    * Status name (e.g., "INVALID_REQUEST_U1")
    */
  def name: String = toString

  def isSuccess: Boolean = statusType == RPCStatusType.SUCCESS
  def isFailure: Boolean = !isSuccess

  /**
    * Whether a server running with `detailedErrors` reports stack traces for this status.
    * Auth-related errors never report them. Without `detailedErrors`, no status does.
    */
  def shouldReportStackTrace: Boolean =
    this match
      case UNAUTHENTICATED_U13 | PERMISSION_DENIED_U14 =>
        false
      case _ =>
        true

  /**
    * Create a new RPCException with this status
    */
  def newException(
      message: String,
      cause: Throwable = null,
      appErrorCode: Option[Int] = None
  ): RPCException = RPCException(
    status = this,
    message = message,
    cause = Option(cause),
    appErrorCode = appErrorCode
  )

end RPCStatus

object RPCStatus:

  // Lazy to avoid initialization order issues
  private lazy val codeTable: Map[Int, RPCStatus]        = values.map(x => x.code -> x).toMap
  private lazy val codeNameTable: Map[String, RPCStatus] = values.map(x => x.name -> x).toMap

  // HTTP status to RPC status mapping
  private lazy val httpStatusMapping: Map[HttpStatus, RPCStatus] = Map(
    // 2xx Success
    HttpStatus.Ok_200        -> SUCCESS_S0,
    HttpStatus.Created_201   -> SUCCESS_S0,
    HttpStatus.Accepted_202  -> SUCCESS_S0,
    HttpStatus.NoContent_204 -> SUCCESS_S0,
    // 4xx Client errors
    HttpStatus.BadRequest_400          -> INVALID_REQUEST_U1,
    HttpStatus.Unauthorized_401        -> UNAUTHENTICATED_U13,
    HttpStatus.Forbidden_403           -> PERMISSION_DENIED_U14,
    HttpStatus.NotFound_404            -> NOT_FOUND_U5,
    HttpStatus.MethodNotAllowed_405    -> NOT_SUPPORTED_U7,
    HttpStatus.Conflict_409            -> ABORTED_U12,
    HttpStatus.PreconditionFailed_412  -> UNEXPECTED_STATE_U9,
    HttpStatus.UnprocessableEntity_422 -> INVALID_REQUEST_U1,
    HttpStatus.TooManyRequests_429     -> RESOURCE_EXHAUSTED_R0,
    // 5xx Server errors
    HttpStatus.InternalServerError_500 -> INTERNAL_ERROR_I0,
    HttpStatus.NotImplemented_501      -> UNIMPLEMENTED_U8,
    HttpStatus.BadGateway_502          -> UNAVAILABLE_I2,
    HttpStatus.ServiceUnavailable_503  -> UNAVAILABLE_I2,
    HttpStatus.GatewayTimeout_504      -> DEADLINE_EXCEEDED_I4
  )

  def ofCode(code: Int): RPCStatus = codeTable.getOrElse(
    code,
    throw IllegalArgumentException(s"Invalid RPCStatus code: ${code}")
  )

  def ofCodeName(name: String): RPCStatus = codeNameTable.getOrElse(
    name,
    throw IllegalArgumentException(s"Invalid RPCStatus name: ${name}")
  )

  /**
    * Map HttpStatus to RPCStatus
    */
  def fromHttpStatus(httpStatus: HttpStatus): RPCStatus =
    httpStatusMapping.get(httpStatus) match
      case Some(status) =>
        status
      case None =>
        if httpStatus.isSuccessful then
          SUCCESS_S0
        else if httpStatus.isClientError then
          USER_ERROR_U0
        else if httpStatus.isServerError then
          INTERNAL_ERROR_I0
        else
          UNKNOWN_I1

  /**
    * Extract error code from the status name suffix
    */
  private[rpc] def extractErrorCode(name: String): Int =
    val separatorPos = name.lastIndexOf("_")
    if separatorPos == -1 then
      throw AssertionError(s"Invalid code name ${name}. It must end with (S/U/I/R)[0-9]+")

    val suffix = name.substring(separatorPos + 1)
    if suffix.length < 2 then
      throw AssertionError(s"Invalid code suffix ${name}. It must have a suffix (S/U/I/R)[0-9]+")

    try
      val errorCode  = suffix.substring(1).toInt
      val statusType = RPCStatusType.ofPrefix(suffix.charAt(0))
      statusType.minCode + errorCode
    catch
      case e: NumberFormatException =>
        throw AssertionError(
          s"Invalid code suffix ${name}. It must have an integer suffix: ${e.getMessage}"
        )

end RPCStatus
